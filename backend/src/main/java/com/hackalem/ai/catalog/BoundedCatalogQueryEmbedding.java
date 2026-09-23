package com.hackalem.ai.catalog;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
@Component
public class BoundedCatalogQueryEmbedding {
    private final CatalogEmbeddingProvider provider;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"catalog-query-embedding");t.setDaemon(true);return t;});
    public BoundedCatalogQueryEmbedding(CatalogEmbeddingProvider provider){this.provider=provider;}
    public float[] embed(String text){var work=executor.submit(()->provider.embedQuery(text));
        try{return work.get(3,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Embedding interrupted",e);}
        catch(ExecutionException|TimeoutException e){throw new IllegalStateException("Embedding unavailable",e);}
        finally{work.cancel(true);}}
    @PreDestroy public void close(){executor.shutdownNow();}
}
