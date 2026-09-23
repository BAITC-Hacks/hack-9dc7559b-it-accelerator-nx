package com.hackalem.domain.catalog;

import com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.Json;
import com.hackalem.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** The synthetic source shares D1's authoritative stock buckets, never cart state. No quote cache. */
@Service
public class CatalogOfferService {
    private final CatalogRepository catalog;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public CatalogOfferService(CatalogRepository catalog,JdbcTemplate db,TransactionTemplate tx) {
        this.catalog=catalog;this.db=new JdbcTemplate(Objects.requireNonNull(db.getDataSource()));this.db.setQueryTimeout(2);this.tx=tx;
    }
    public record Quote(String article,String warehouse,String status,String freshness,String error,OfferSnapshot offer) {}
    public List<Quote> quotes(CatalogProduct p) {
        if(!p.synthetic())return p.stock().warehouses().stream().map(w->new Quote(p.article(),w.warehouseId(),"UNKNOWN","UNAVAILABLE","partner_source_not_configured",null)).toList();
        try {
            seed(p);
            var rows=db.query("SELECT * FROM sample_offers WHERE article=? AND unit=? AND catalog_version_id=? ORDER BY warehouse",(r,n)->{
                var now=Instant.now();
                return new OfferSnapshot(p.article(),new Money(Decimals.money(r.getBigDecimal("price")),r.getString("currency")),
                        new Quantity(Decimals.quantity(r.getBigDecimal("available")),p.unit(),Decimals.quantity(r.getBigDecimal("step"))),
                        r.getString("warehouse"),r.getString("bucket"),r.getString("version"),now,now.plusSeconds(60));
            },p.article(),p.unit(),p.catalogVersionId());
            return p.stock().warehouses().stream().map(w->{
                var found=rows.stream().filter(o->o.warehouse().equals(w.warehouseId())).findFirst();
                if(found.isPresent())return new Quote(p.article(),w.warehouseId(),new BigDecimal(found.get().available().value()).signum()>0?"IN_STOCK":"OUT_OF_STOCK","CURRENT",null,found.get());
                String error=!w.eligible()?"warehouse_ineligible":w.status()==StockStatus.UNKNOWN?"stock_unknown":w.status()==StockStatus.ON_ORDER?"on_order":"offer_unavailable";
                return new Quote(p.article(),w.warehouseId(),w.status()==StockStatus.ON_ORDER?"ON_ORDER":"UNKNOWN",error.equals("offer_unavailable")?"UNAVAILABLE":"UNKNOWN",error,null);
            }).toList();
        } catch(DataAccessException e) {
            return List.of(new Quote(p.article(),null,"UNKNOWN","UNAVAILABLE",e instanceof org.springframework.dao.QueryTimeoutException?"stock_source_timeout":"stock_source_unavailable",null));
        }
    }
    /** Both current facts and unknown failures are visible in cards; no stale quote fallback. */
    public CatalogProduct hydrate(CatalogProduct p) {
        var quotes=quotes(p);
        var valid=quotes.stream().filter(q->q.offer()!=null).map(Quote::offer).toList();
        var price=valid.isEmpty()?null:new ProductOffer(new BigDecimal(valid.getFirst().price().amount()),valid.getFirst().price().currency(),valid.getFirst().version(),valid.getFirst().observedAt());
        var warehouses=quotes.stream().filter(q->q.warehouse()!=null).map(q->new WarehouseAvailability(q.warehouse(),StockStatus.valueOf(q.status()),
                q.offer()==null?null:new BigDecimal(q.offer().available().value()),!"warehouse_ineligible".equals(q.error()),q.offer()==null?null:q.offer().observedAt())).toList();
        return new CatalogProduct(p.id(),p.supplierId(),p.article(),p.articleNormalized(),p.name(),p.brand(),p.category(),p.unit(),p.minimumQuantity(),p.stepQuantity(),
                p.specs(),p.certificates(),p.sourceUrl(),p.sourceVersion(),p.synthetic(),p.catalogVersionId(),price,ProductStock.from(warehouses),p.score());
    }
    public List<OfferSnapshot> getOffers(List<Selection> items,TrustedScope scope) {
        requireScope(scope);
        if(items==null||items.isEmpty()||items.size()>50)throw new ApiException(400,"invalid_offer_batch");
        List<OfferSnapshot> result=new ArrayList<>();long deadline=System.nanoTime()+2_000_000_000L;
        for(Selection item:items) {
            if(System.nanoTime()>deadline)throw ApiException.unavailable("stock_source_timeout");
            var p=catalog.findActiveByArticle(item.article()).orElseThrow(ApiException::missing);
            BigDecimal quantity=quantity(item.addQuantity());
            validateQuantity(p,quantity,item.unit());
            var current=quotes(p);
            current.stream().filter(q->q.warehouse()==null&&q.error()!=null).findFirst().ifPresent(q->{throw ApiException.unavailable(q.error());});
            var quote=current.stream().filter(q->Objects.equals(q.warehouse(),item.warehouse())).findFirst().orElseThrow(()->ApiException.unavailable("offer_unavailable"));
            if(quote.offer()==null)throw ApiException.unavailable(quote.error());
            result.add(quote.offer());
        }
        return List.copyOf(result);
    }
    public void updateSample(String article,String warehouse,BigDecimal price,BigDecimal quantity,String expectedVersion) {
        if(price==null||quantity==null||price.signum()<0||quantity.signum()<0||price.scale()>2||quantity.scale()>6)
            throw new ApiException(400,"invalid_sample_offer");
        var p=catalog.findActiveByArticle(article).orElseThrow(ApiException::missing);
        if(!p.synthetic())throw new ApiException(403,"synthetic_offer_required");
        seed(p);
        int changed=db.update("UPDATE sample_offers SET price=?,available=?,version=version+1,observed_at=now() WHERE article=? AND warehouse=? AND unit=? AND version::text=?",
                price,quantity,p.article(),warehouse,p.unit(),expectedVersion);
        if(changed!=1)throw ApiException.conflict("offer_version_changed");
    }
    private void seed(CatalogProduct p) {
        tx.executeWithoutResult(s->{
            // Cross-replica lock and source-version check ensure repeated reads never overwrite changed offers.
            db.queryForList("SELECT id FROM products WHERE id=? FOR UPDATE",p.id());
            var old=db.queryForList("SELECT * FROM catalog_offer_seed WHERE product_id=?",p.id());
            if(!old.isEmpty()&&((Number)old.getFirst().get("catalog_version_id")).longValue()==p.catalogVersionId()
                    &&Objects.equals(old.getFirst().get("source_version"),p.sourceVersion()))return;
            long active=catalog.findActiveVersion().orElseThrow(()->ApiException.unavailable("catalog_unavailable")).id();
            if(active!=p.catalogVersionId())throw ApiException.conflict("catalog_version_changed");
            // Remove only this catalog product's obsolete source buckets; cart rows are never touched.
            var eligible=p.stock().warehouses().stream().filter(w->w.eligible()&&w.availableQuantity()!=null
                    &&w.status()!=StockStatus.UNKNOWN&&w.status()!=StockStatus.ON_ORDER).map(WarehouseAvailability::warehouseId).toList();
            var prior=db.queryForList("SELECT warehouse,unit FROM sample_offers WHERE article=? AND catalog_version_id IS NOT NULL",p.article());
            for(var oldRow:prior)if(!p.unit().equals(oldRow.get("unit"))||!eligible.contains(oldRow.get("warehouse"))||p.offer()==null)
                db.update("DELETE FROM sample_offers WHERE article=? AND unit=? AND warehouse=?",p.article(),oldRow.get("unit"),oldRow.get("warehouse"));
            if(p.offer()!=null&&p.offer().known())for(var w:p.stock().warehouses()) {
                if(!w.eligible()||w.availableQuantity()==null||w.status()==StockStatus.ON_ORDER||w.status()==StockStatus.UNKNOWN)continue;
                String bucket=bucket(p.article(),p.unit(),w.warehouseId());
                db.update("""
                    INSERT INTO sample_offers(article,unit,warehouse,bucket,price,currency,available,step,version,catalog_version_id,source_version,minimum_quantity)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(article,unit,warehouse) DO UPDATE SET
                    price=EXCLUDED.price,currency=EXCLUDED.currency,available=EXCLUDED.available,step=EXCLUDED.step,
                    version=sample_offers.version+1,catalog_version_id=EXCLUDED.catalog_version_id,
                    source_version=EXCLUDED.source_version,minimum_quantity=EXCLUDED.minimum_quantity,observed_at=now()
                    """,p.article(),p.unit(),w.warehouseId(),bucket,p.offer().price(),p.offer().currency(),w.availableQuantity(),p.stepQuantity(),
                        Math.max(1,p.catalogVersionId()),p.catalogVersionId(),p.sourceVersion(),p.minimumQuantity());
            }
            db.update("INSERT INTO catalog_offer_seed VALUES (?,?,?) ON CONFLICT(product_id) DO UPDATE SET catalog_version_id=EXCLUDED.catalog_version_id,source_version=EXCLUDED.source_version",
                    p.id(),p.catalogVersionId(),p.sourceVersion());
        });
    }
    public static String bucket(String article,String unit,String warehouse) { return "catalog:"+Json.hash(article+"\u0000"+unit+"\u0000"+warehouse); }
    public static BigDecimal quantity(String value) {
        if(value==null||!value.matches("[0-9]{1,12}(\\.[0-9]{1,6})?"))throw new ApiException(400,"invalid_quantity");
        BigDecimal result=new BigDecimal(value);if(result.signum()<=0)throw new ApiException(400,"invalid_quantity");return result;
    }
    public static void validateQuantity(CatalogProduct p,BigDecimal quantity,String unit) {
        if(!p.unit().equals(unit)||quantity.compareTo(p.minimumQuantity())<0||quantity.remainder(p.stepQuantity()).signum()!=0)
            throw new ApiException(400,"invalid_quantity_unit_or_step");
    }
    public static void requireScope(TrustedScope scope) {
        if(scope==null||scope.principalId()==null||scope.cartId()==null)throw new ApiException(401,"trusted_scope_required");
    }
}
