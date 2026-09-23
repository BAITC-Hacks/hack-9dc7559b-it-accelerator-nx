package com.hackalem.config;

import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.port.*;
import com.hackalem.domain.cart.CartService;
import com.hackalem.web.ApiException;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;

/** Explicit offline fixtures only. Production must supply D2 implementations of these ports. */
@Configuration
@Profile({"contract","test"})
public class ContractData {
    @Bean ApplicationRunner sampleSeed(JdbcTemplate db){return args->{
        db.update("INSERT INTO sample_offers(article,unit,warehouse,bucket,price,currency,available,step) VALUES ('DEMO-001','piece','main','DEMO-001:main',1500,'KZT',12,1),('DEMO-002','piece','main','DEMO-002:main',1000,'KZT',30,1) ON CONFLICT DO NOTHING");
    };}
    @Bean StockPort contractStock(JdbcTemplate db){return (items,scope)->items.stream().map(item->db.query("SELECT * FROM sample_offers WHERE article=? AND unit=? AND warehouse=?",(r,n)->new OfferSnapshot(r.getString("article"),new Money(CartService.decimal(r.getBigDecimal("price")),r.getString("currency")),new Quantity(CartService.decimal(r.getBigDecimal("available")),r.getString("unit"),CartService.decimal(r.getBigDecimal("step"))),r.getString("warehouse"),r.getString("bucket"),r.getString("version"),Instant.now(),Instant.now().plusSeconds(300)),item.article(),item.unit(),item.warehouse()).stream().findFirst().orElseThrow(()->ApiException.conflict("offer_unavailable"))).toList();}
    @Bean CatalogPort contractCatalog(StockPort stock){return new CatalogPort(){
        final List<ProductDetails> products=List.of(new ProductDetails("9007199254740993","DEMO-001","Тестовый автомат 16A",Map.of("current","16A"),List.of()),new ProductDetails("9007199254740994","DEMO-002","Тестовый автомат 10A",Map.of("current","10A"),List.of()));
        public ProductResultSet search(SearchQuery query,TrustedScope scope){
            var found=products.stream().filter(p->!query.query().contains("DEMO-") || query.query().contains(p.article()))
                .filter(p->query.hardConstraints()==null || query.hardConstraints().entrySet().stream().allMatch(e->e.getValue().equals(p.specs().get(e.getKey())))).toList();
            var offers=stock.getOffers(found.stream().map(p->new Selection(p.article(),"piece","main","1")).toList(),scope);
            var filtered=found.stream().filter(p->query.maxPrice()==null || offers.stream().anyMatch(o->o.article().equals(p.article()) && o.price().currency().equals(query.maxPrice().currency()) && new java.math.BigDecimal(o.price().amount()).compareTo(new java.math.BigDecimal(query.maxPrice().amount()))<=0)).toList();
            return new ProductResultSet(UUID.randomUUID().toString(),"1",filtered,offers.stream().filter(o->filtered.stream().anyMatch(p->p.article().equals(o.article()))).toList());
        }
        public ProductDetails getProduct(String article,TrustedScope scope){return products.stream().filter(p->p.article().equals(article)).findFirst().orElseThrow(ApiException::missing);}
    };}
    @Bean KnowledgePort contractKnowledge(){return (query,scope,budget)->List.of(new SourceChunk(new SourceRef("sample-terms","1","Тестовые условия",1,null,null),"Тестовые условия: самовывоз со склада. Доставка согласуется отдельно."));}
    @Bean AnalogsPort contractAnalogs(){return (article,constraints,scope)->List.of();}
    @Bean AttachmentPort contractAttachments(){return (id,version,scope)->{throw ApiException.missing();};}
}
