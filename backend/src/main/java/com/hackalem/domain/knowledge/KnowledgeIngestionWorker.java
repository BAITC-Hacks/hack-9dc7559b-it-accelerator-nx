package com.hackalem.domain.knowledge;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded worker; provider latency never holds the shared chat scheduling thread. */
@Component @Profile("!contract & !test")
public class KnowledgeIngestionWorker {
    private final KnowledgeRepository repository;
    private final KnowledgeService service;
    private final boolean enabled;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),Thread.ofPlatform().name("knowledge-ingestion-",0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    public KnowledgeIngestionWorker(KnowledgeRepository repository, KnowledgeService service,
                                    @Value("${app.knowledge.worker-enabled:true}") boolean enabled) {
        this.repository = repository; this.service = service; this.enabled = enabled;
    }
    @Scheduled(fixedDelay=2000)
    public void tick() {
        if (!enabled || !busy.compareAndSet(false,true)) return;
        try {
            worker.execute(()->{
                try {
                    repository.expireExhaustedJobs();
                    for (var job:repository.recoverableJobs()) service.process(job);
                } finally { busy.set(false); }
            });
        } catch (RejectedExecutionException ex) { busy.set(false); }
    }
    @PreDestroy public void shutdown() { worker.shutdownNow(); }
}
