package com.hackalem.domain.attachments;
import com.hackalem.domain.catalog.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.hackalem.domain.attachments.AttachmentModels.*;
@Component
public class CatalogAttachmentAdapter implements AttachmentCatalog {
    private final CatalogQueryService catalog;private final JdbcTemplate db;
    public CatalogAttachmentAdapter(CatalogQueryService catalog,JdbcTemplate db){this.catalog=catalog;this.db=db;}
    public List<Product> search(String owner,String query){
        try{return List.of(convert(catalog.findByArticle(query)));}
        catch(ProductNotFoundException ignored){}
        try{return catalog.search(query.substring(0,Math.min(2000,query.length())),5,null,null,null,null).items().stream().map(this::convert).toList();}
        catch(CatalogIndexNotReadyException unavailable){return List.of();}
    }
    public Optional<Product> product(String id){
        long numeric;try{numeric=Long.parseLong(id);}catch(NumberFormatException e){return Optional.empty();}
        // Resolve only in the current version; IDs from a retired catalog cannot be reviewed.
        var articles=db.query("SELECT p.article FROM products p JOIN product_versions pv ON pv.product_id=p.id JOIN catalog_versions v ON v.id=pv.catalog_version_id WHERE p.id=? AND p.status='ACTIVE' AND v.status='ACTIVE'",(rs,n)->rs.getString(1),numeric);
        return articles.stream().findFirst().map(catalog::findByArticle).map(this::convert);
    }
    private Product convert(CatalogProduct p){return new Product(Long.toString(p.id()),p.article(),p.name(),p.unit(),p.minimumQuantity(),p.stepQuantity(),p.specs(),p.stock().warehouses().stream().filter(WarehouseAvailability::eligible).map(WarehouseAvailability::warehouseId).toList());}
}
