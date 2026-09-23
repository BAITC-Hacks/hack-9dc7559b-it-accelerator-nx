package com.hackalem.domain.attachments;
import com.hackalem.domain.port.AttachmentPort;
import static com.hackalem.domain.port.Contracts.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
@Component @Profile("!contract & !test")
public class ReviewedAttachmentPort implements AttachmentPort {
    private final AttachmentService attachments;
    public ReviewedAttachmentPort(AttachmentService attachments){this.attachments=attachments;}
    public ReviewedItems getReviewedItems(String attachmentId,String version,TrustedScope scope){return attachments.reviewed(attachmentId,version,scope);}
}
