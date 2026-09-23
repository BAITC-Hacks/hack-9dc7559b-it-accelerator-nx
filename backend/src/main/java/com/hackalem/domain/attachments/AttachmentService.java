package com.hackalem.domain.attachments;

import com.hackalem.ai.attachments.*;
import com.hackalem.domain.chat.ChatService;
import com.hackalem.domain.port.Contracts;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import static com.hackalem.domain.attachments.AttachmentModels.*;

@Service
public class AttachmentService {
    private final AttachmentRepository files;private final AttachmentValidator validator;private final AttachmentExtractor extractor;
    private final ChatService chat;private final AttachmentCatalog catalog;private final TransactionTemplate tx;
    public AttachmentService(AttachmentRepository files,AttachmentValidator validator,AttachmentExtractor extractor,ChatService chat,AttachmentCatalog catalog,TransactionTemplate tx){this.files=files;this.validator=validator;this.extractor=extractor;this.chat=chat;this.catalog=catalog;this.tx=tx;}
    public Accepted upload(TrustedScope scope,UUID conversation,String filename,String mime,byte[] bytes){
        requireScope(scope);chat.conversation(scope,conversation,false);
        var valid=validator.validate(filename,mime,bytes);
        return files.create(scope,conversation,valid.filename(),valid.mime(),valid.extension(),valid.bytes());
    }
    public Capabilities capabilities(){return new Capabilities(AttachmentValidator.MIMES.keySet().stream().sorted().toList(),AttachmentLimits.BYTES,AttachmentLimits.ROWS,AttachmentLimits.PAGES,AttachmentLimits.PIXELS,extractor.ocrAvailable(),extractor.visionAvailable(),true);}
    public Snapshot status(TrustedScope scope,String id){return tx.execute(s->snapshot(access(scope,id,true)));}
    public Stored source(TrustedScope scope,String id){return access(scope,id,false);}
    public void delete(TrustedScope scope,String id){
        requireScope(scope);var current=files.ownedIncludingDeleted(id,scope);chat.conversation(scope,UUID.fromString(current.conversationId()),false);files.delete(id,scope);
    }
    public Snapshot review(TrustedScope scope,String id,ReviewRequest request){
        return tx.execute(s->{
            var current=access(scope,id,true);
            long expected;try{expected=Long.parseLong(request.expectedVersion());}catch(Exception e){throw new ApiException(400,"invalid_version");}
            if(current.version()!=expected)throw ApiException.conflict("stale_attachment");
            if(request.selections()==null||request.selections().size()>AttachmentLimits.ROWS)throw new ApiException(400,"invalid_selections");
            var seen=new HashSet<String>();
            for(var selection:request.selections()){
                if(!seen.add(selection.rowId()))throw new ApiException(400,"duplicate_row_selection");
                if(selection.selected())validateSelection(selection);
            }
            files.review(id,scope,expected,request.selections());return snapshot(access(scope,id,true));
        });
    }
    public Contracts.ReviewedItems reviewed(String id,String version,TrustedScope scope){
        return tx.execute(s->{
            var current=access(scope,id,true);
            if(!Long.toString(current.version()).equals(version))throw ApiException.conflict("stale_attachment");
            List<Contracts.Selection> selected=new ArrayList<>();List<Contracts.SourceRef> sources=new ArrayList<>();
            Map<String,ExtractedRow> rows=new HashMap<>();files.rows(id).forEach(r->rows.put(r.extracted().id(),r.extracted()));
            for(var selection:files.selections(id))if(selection.selected()){
                Product product=validateSelection(selection);
                selected.add(new Contracts.Selection(product.article(),selection.unit(),selection.warehouse(),selection.quantity()));
                var location=rows.get(selection.rowId()).source();
                sources.add(new Contracts.SourceRef(id,version,current.filename(),location.page(),location.sheet(),location.row()));
            }
            return new Contracts.ReviewedItems(id,version,List.copyOf(selected),List.copyOf(sources));
        });
    }
    private Product validateSelection(Selection selection){
        Product product=catalog.product(selection.productId()).orElseThrow(ApiException::missing);
        BigDecimal quantity;
        try{if(selection.quantity()==null||!selection.quantity().matches("[0-9]{1,12}(?:\\.[0-9]{1,6})?"))throw new NumberFormatException();quantity=new BigDecimal(selection.quantity());}
        catch(Exception e){throw new ApiException(400,"invalid_quantity");}
        if(!product.unit().equals(selection.unit()))throw new ApiException(400,"invalid_unit");
        if(quantity.signum()<=0||quantity.compareTo(product.minimum())<0||quantity.remainder(product.step()).signum()!=0)throw new ApiException(400,"invalid_quantity_step");
        if(!product.warehouses().contains(selection.warehouse()))throw new ApiException(400,"invalid_warehouse");
        return product;
    }
    private Stored access(TrustedScope scope,String id,boolean lock){
        requireScope(scope);var file=files.find(id,scope,lock);chat.conversation(scope,UUID.fromString(file.conversationId()),false);return file;
    }
    private Snapshot snapshot(Stored file){return new Snapshot(file.id(),file.conversationId(),Long.toString(file.version()),file.status(),file.stage(),file.filename(),file.mime(),files.rows(file.id()),files.selections(file.id()),files.warnings(file),file.error());}
    private static void requireScope(TrustedScope scope){if(scope==null||scope.principalId()==null)throw new ApiException(401,"unauthorized");}
}
