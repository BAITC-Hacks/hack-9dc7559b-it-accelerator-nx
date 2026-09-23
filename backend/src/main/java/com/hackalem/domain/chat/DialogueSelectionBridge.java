package com.hackalem.domain.chat;

import com.hackalem.domain.port.*;
import com.hackalem.domain.catalog.CatalogAnalogsService;
import com.hackalem.integration.catalog.CatalogDataAdapter;
import com.hackalem.web.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.hackalem.domain.port.Contracts.*;

/** Explicit browser selections enter the same immutable result-set path as tool results. */
@Component
public class DialogueSelectionBridge {
    private final ObjectProvider<CatalogDataAdapter> catalog;
    private final ObjectProvider<CatalogAnalogsService> analogs;
    private final ObjectProvider<AttachmentPort> attachments;
    private final ObjectProvider<CatalogPort> products;
    private final ObjectProvider<StockPort> stock;
    public DialogueSelectionBridge(ObjectProvider<CatalogDataAdapter> catalog,ObjectProvider<CatalogAnalogsService> analogs,
            ObjectProvider<AttachmentPort> attachments,ObjectProvider<CatalogPort> products,ObjectProvider<StockPort> stock){
        this.catalog=catalog;this.analogs=analogs;this.attachments=attachments;this.products=products;this.stock=stock;
    }
    public ProductResultSet saved(String id,TrustedScope scope){
        var source=catalog.getIfAvailable();if(source==null)throw ApiException.missing();
        return source.saved(ChatService.uuid(id),scope).resultSet();
    }
    public ProductResultSet explicit(UpdateDialogue request,TrustedScope scope){
        List<Selection> lines;
        if(request.attachmentId()!=null){
            var source=attachments.getIfAvailable();if(source==null)throw ApiException.unavailable("attachment_source_unavailable");
            lines=source.getReviewedItems(request.attachmentId(),request.attachmentVersion(),scope).items();
        }else if(request.fulfillmentOptionId()!=null){
            var source=analogs.getIfAvailable();if(source==null)throw ApiException.unavailable("catalog_source_unavailable");
            lines=source.option(ChatService.uuid(request.fulfillmentOptionId()),scope).lines();
        }else return null;
        if(lines.isEmpty())throw ApiException.conflict("selection_required");
        var reader=products.getIfAvailable();var offers=stock.getIfAvailable();
        if(reader==null||offers==null)throw ApiException.unavailable("catalog_source_unavailable");
        return new ProductResultSet(UUID.randomUUID().toString(),"1",lines.stream().map(Selection::article).distinct().map(a->reader.getProduct(a,scope)).toList(),offers.getOffers(lines,scope));
    }
}
