package com.hackalem.domain.chat;
import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;

/** PG outbox commits with state. Redis Streams are a bounded, shared replay cache. */
@Service
public class EventJournal {
    private final JdbcTemplate db;private final StringRedisTemplate redis;private final Json json;private final MeterRegistry metrics;private final int ttl,maxLength;
    public EventJournal(JdbcTemplate db,StringRedisTemplate redis,Json json,MeterRegistry metrics,@Value("${app.events.ttl-seconds}") int ttl,@Value("${app.events.max-length}") int maxLength){this.db=db;this.redis=redis;this.json=json;this.metrics=metrics;this.ttl=ttl;this.maxLength=maxLength;}
    public List<ChatEvent> read(UUID run,long after){return db.query("SELECT body::text FROM run_events WHERE run_id=? AND seq>? ORDER BY seq LIMIT 100",(r,n)->json.read(r.getString(1),ChatEvent.class),run,after);}
    private static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>("""
        local prev=redis.call('GET',KEYS[2])
        if prev and tonumber(prev)>=tonumber(ARGV[1]) then return 0 end
        redis.call('XADD',KEYS[1],'MAXLEN','=',ARGV[3],ARGV[1]..'-0','body',ARGV[2])
        redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[4]);redis.call('EXPIRE',KEYS[1],ARGV[4]);return 1
        """,Long.class);
    @Scheduled(fixedDelay=500)
    public void replicate(){
        // Outbox remains authoritative if Redis disappears; no generation or tool is replayed.
        var runs=db.queryForList("SELECT id FROM chat_runs WHERE created_at>now()-make_interval(secs=>?) AND event_seq>0 ORDER BY created_at DESC LIMIT 100",UUID.class,ttl);
        for(UUID run:runs){
            try{
                String cursor=redis.opsForValue().get("ekt:event-seq:"+run);long after=cursor==null?0:Long.parseLong(cursor);
                for(var event:read(run,after))redis.execute(PUBLISH,List.of("ekt:events:"+run,"ekt:event-seq:"+run),event.seq(),json.write(event),String.valueOf(maxLength),String.valueOf(ttl));
            }catch(Exception e){metrics.counter("chat.events.redis.errors").increment();break;}
        }
    }
    @Scheduled(fixedDelay=60000)
    public void prune(){
        db.update("DELETE FROM run_events WHERE created_at<now()-make_interval(secs=>?)",ttl);
        db.update("DELETE FROM run_events e USING chat_runs r WHERE e.run_id=r.id AND e.seq<=r.event_seq-?",maxLength);
    }
}
