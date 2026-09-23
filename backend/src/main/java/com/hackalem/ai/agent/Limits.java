package com.hackalem.ai.agent;
import com.hackalem.web.ApiException;
import com.hackalem.domain.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Atomic shared budgets. Redis outage fails closed; no local fallback on another replica. */
@Service
public class Limits {
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final int globalRpm, principalRpm, globalTokens, concurrency;
    public Limits(StringRedisTemplate redis,MeterRegistry metrics,
        @Value("${app.limits.global-rpm}") int globalRpm,@Value("${app.limits.principal-rpm}") int principalRpm,
        @Value("${app.limits.tokens-per-minute}") int globalTokens,@Value("${app.limits.concurrency}") int concurrency) {
        this.redis=redis;this.metrics=metrics;this.globalRpm=globalRpm;this.principalRpm=principalRpm;
        this.globalTokens=globalTokens;this.concurrency=concurrency;
    }
    private static final DefaultRedisScript<Long> RATE=new DefaultRedisScript<>("""
        local n=tonumber(redis.call('GET',KEYS[1]) or '0')
        if n>=tonumber(ARGV[1]) then return 0 end
        redis.call('INCR',KEYS[1]); redis.call('EXPIRE',KEYS[1],120); return 1
        """,Long.class);
    public void rate(String kind,String principal,int max) {
        try {
            Long ok=redis.execute(RATE,List.of("ekt:rate:"+kind+":"+Json.hash(principal)+":"+Instant.now().getEpochSecond()/60),String.valueOf(max));
            if(!Long.valueOf(1).equals(ok)) throw new ApiException(429,"rate_limit");
        } catch(ApiException e) { throw e; } catch(Exception e) { throw ApiException.unavailable("limiter_unavailable"); }
    }
    private static final DefaultRedisScript<Long> RESERVE=new DefaultRedisScript<>("""
        local now=tonumber(ARGV[1]); redis.call('ZREMRANGEBYSCORE',KEYS[4],'-inf',now)
        if redis.call('EXISTS',KEYS[5])==1 then return 1 end
        if tonumber(redis.call('GET',KEYS[1]) or '0')>=tonumber(ARGV[2]) or
           tonumber(redis.call('GET',KEYS[2]) or '0')>=tonumber(ARGV[3]) or
           tonumber(redis.call('GET',KEYS[3]) or '0')+tonumber(ARGV[4])>tonumber(ARGV[5]) or
           redis.call('ZCARD',KEYS[4])>=tonumber(ARGV[6]) then return 0 end
        redis.call('INCR',KEYS[1]);redis.call('EXPIRE',KEYS[1],120)
        redis.call('INCR',KEYS[2]);redis.call('EXPIRE',KEYS[2],120)
        redis.call('INCRBY',KEYS[3],ARGV[4]);redis.call('EXPIRE',KEYS[3],120)
        redis.call('ZADD',KEYS[4],now+tonumber(ARGV[8]),ARGV[7]);redis.call('EXPIRE',KEYS[4],180)
        redis.call('SET',KEYS[5],KEYS[3],'EX',180); return 1
        """,Long.class);
    public void reserve(UUID run,UUID owner,int tokens,int deadlineSeconds) {
        String window=String.valueOf(Instant.now().getEpochSecond()/60);
        metrics.counter("agent.provider.offered").increment();
        try {
            Long ok=redis.execute(RESERVE,List.of("ekt:rpm:"+window,"ekt:rpm:"+owner+":"+window,
                "ekt:tpm:"+window,"ekt:active","ekt:reservation:"+run),
                String.valueOf(Instant.now().getEpochSecond()),String.valueOf(globalRpm),String.valueOf(principalRpm),
                String.valueOf(tokens),String.valueOf(globalTokens),String.valueOf(concurrency),run.toString(),String.valueOf(deadlineSeconds));
            if(!Long.valueOf(1).equals(ok)) {metrics.counter("agent.provider.rejected").increment();throw new ApiException(429,"provider_budget_exhausted");}
            metrics.counter("agent.provider.admitted").increment();
        } catch(ApiException e) {throw e;} catch(Exception e) {throw ApiException.unavailable("limiter_unavailable");}
    }
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("""
        local budget=redis.call('GET',KEYS[2])
        if budget and tonumber(ARGV[2])>0 and redis.call('EXISTS',budget)==1 then
          local current=tonumber(redis.call('GET',budget));redis.call('DECRBY',budget,math.min(current,tonumber(ARGV[2]))) end
        redis.call('DEL',KEYS[2]);redis.call('ZREM',KEYS[1],ARGV[1]); return 1
        """,Long.class);
    public void release(UUID run,int unusedTokens) {
        try {redis.execute(RELEASE,List.of("ekt:active","ekt:reservation:"+run),run.toString(),String.valueOf(unusedTokens));}
        catch(Exception e) {metrics.counter("agent.limiter.release.errors").increment();}
    }
}
