package com.hackalem.domain.attachments;
import java.util.List;
import java.util.Optional;
import static com.hackalem.domain.attachments.AttachmentModels.*;
/** D2 adapter boundary; never mutates cart. All results are authoritative catalog records. */
public interface AttachmentCatalog {
    List<Product> search(String owner, String query);
    Optional<Product> product(String id);
}
