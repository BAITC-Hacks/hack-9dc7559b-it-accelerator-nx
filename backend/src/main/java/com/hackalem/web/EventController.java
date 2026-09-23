package com.hackalem.web;
import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.chat.*;
import com.hackalem.ai.agent.Limits;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class EventController {
    private final ChatService chat;private final EventJournal journal;private final Limits limits;
    private final Semaphore slots;private final ExecutorService readers;
    public EventController(ChatService chat,EventJournal journal,Limits limits,MeterRegistry metrics,@Value("${app.events.max-subscribers}") int max){this.chat=chat;this.journal=journal;this.limits=limits;slots=new Semaphore(max);readers=Executors.newVirtualThreadPerTaskExecutor();metrics.gauge("chat.sse.active",slots,s->max-s.availablePermits());}
    @GetMapping(value="/api/runs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(responseCode="200",content=@Content(mediaType="text/event-stream",schema=@Schema(implementation=ChatEvent.class)))
    public SseEmitter streamRun(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@RequestHeader(value="Last-Event-ID",required=false) String cursor,jakarta.servlet.http.HttpServletResponse response){
        var run=chat.run(scope,id);long after=0;boolean invalidEpoch=false;
        if(cursor!=null){String[] bits=cursor.split(":",-1);if(bits.length!=2)throw new ApiException(400,"invalid_event_cursor");long epoch=ChatService.counter(bits[0]);after=ChatService.counter(bits[1]);invalidEpoch=epoch!=Long.parseLong(run.epoch()) || after>Long.parseLong(run.sequence());}
        limits.rate("sse",scope.principalId().toString(),60);
        if(!slots.tryAcquire())throw ApiException.unavailable("sse_capacity");
        response.setHeader("Cache-Control","no-cache, no-transform");response.setHeader("X-Accel-Buffering","no");
        SseEmitter emitter=new SseEmitter(65000L);AtomicBoolean closed=new AtomicBoolean();
        Runnable close=()->{if(closed.compareAndSet(false,true))slots.release();};
        emitter.onCompletion(close);emitter.onTimeout(close);emitter.onError(e->close.run());
        long start=after;boolean reset=invalidEpoch;
        readers.submit(()->{
            long seq=start;long heartbeat=System.nanoTime();
            try{
                if(reset){snapshot(emitter,chat.run(scope,id),true);return;}
                while(!closed.get()){
                    var events=journal.read(id,seq);var current=chat.run(scope,id);
                    if(events.isEmpty() && seq<Long.parseLong(current.sequence()))events=journal.read(id,seq);
                    if((!events.isEmpty() && Long.parseLong(events.getFirst().seq())!=seq+1) || (events.isEmpty() && seq<Long.parseLong(current.sequence()))){snapshot(emitter,current,true);break;}
                    for(var event:events){
                        if(Long.parseLong(event.epoch())>Long.parseLong(current.epoch()))current=chat.run(scope,id);
                        if(!event.epoch().equals(current.epoch())){snapshot(emitter,current,true);return;}
                        emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
                        seq=Long.parseLong(event.seq());
                    }
                    if(ChatService.terminal(current.status()) && seq>=Long.parseLong(current.sequence()))break;
                    if(System.nanoTime()-heartbeat>TimeUnit.SECONDS.toNanos(10)){emitter.send(SseEmitter.event().comment("heartbeat"));heartbeat=System.nanoTime();}
                    Thread.sleep(200);
                }
            }catch(Exception e){emitter.completeWithError(e);}finally{emitter.complete();close.run();}
        });
        return emitter;
    }
    private void snapshot(SseEmitter emitter,RunSnapshot run,boolean unavailable)throws java.io.IOException{
        var event=new ChatEvent(run.epoch()+":"+run.sequence(),run.id(),run.sequence(),run.epoch(),"replay_unavailable","1",new TerminalPayload(run,unavailable));
        emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
    }
    @PreDestroy public void close(){readers.shutdownNow();}
}
