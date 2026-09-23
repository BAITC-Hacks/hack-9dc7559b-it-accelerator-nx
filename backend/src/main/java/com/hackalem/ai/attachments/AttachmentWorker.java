package com.hackalem.ai.attachments;
import com.hackalem.domain.attachments.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class AttachmentWorker {
    private final AttachmentRepository files;private final AttachmentExtractor extractor;private final AttachmentMatcher matcher;private final boolean enabled;
    public AttachmentWorker(AttachmentRepository files,AttachmentExtractor extractor,AttachmentMatcher matcher,@Value("${app.attachments.worker-enabled:true}") boolean enabled){this.files=files;this.extractor=extractor;this.matcher=matcher;this.enabled=enabled;}
    @Scheduled(fixedDelay=1000) public void tick(){if(enabled)processOne();}
    /** A single synchronous claim per scheduler bounds concurrency per replica. */
    public boolean processOne(){
        var candidate=files.claim();if(candidate.isEmpty())return false;var job=candidate.get();
        var stored=files.internal(job.attachmentId());if(stored.isEmpty())return true;
        try{var extraction=extractor.extract(stored.get().bytes(),stored.get().extension());var rows=matcher.match(stored.get().owner(),extraction);files.publish(job,rows,extraction.warnings(),null);}
        catch(AttachmentException e){files.publish(job,java.util.List.of(),java.util.List.of(),e.code());}
        catch(Exception e){files.publish(job,java.util.List.of(),java.util.List.of(),"PROCESSING_FAILED");}
        return true;
    }
}
