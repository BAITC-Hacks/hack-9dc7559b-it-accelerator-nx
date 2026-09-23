package com.hackalem.web.attachments;
import com.hackalem.domain.attachments.*;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static com.hackalem.domain.attachments.AttachmentModels.*;
@RestController @RequestMapping("/api")
public class AttachmentController {
    private final AttachmentService attachments;
    public AttachmentController(AttachmentService attachments){this.attachments=attachments;}
    @GetMapping("/attachments/capabilities") public Capabilities capabilities(){return attachments.capabilities();}
    @PostMapping(value="/conversations/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted upload(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@RequestPart("file") MultipartFile file)throws IOException{
        if(file.getSize()>AttachmentLimits.BYTES)throw new ApiException(413,"file_too_large");
        byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes(AttachmentLimits.BYTES+1);}
        return attachments.upload(scope,id,file.getOriginalFilename(),file.getContentType(),bytes);
    }
    @GetMapping("/attachments/{id}") public Snapshot status(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){return attachments.status(scope,id.toString());}
    @PostMapping("/attachments/{id}/review") public Snapshot review(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@Valid @RequestBody ReviewRequest request){return attachments.review(scope,id.toString(),request);}
    @PostMapping("/attachments/{id}/reprocess") @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted reprocess(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@Valid @RequestBody ReprocessRequest request){return attachments.reprocess(scope,id.toString(),request);}
    @DeleteMapping("/attachments/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){attachments.delete(scope,id.toString());}
    @GetMapping("/attachments/{id}/source") public ResponseEntity<byte[]> source(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){
        var source=attachments.source(scope,id.toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(source.filename(),StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType(source.mime())).body(source.bytes());
    }
}
