package com.hackalem.integration.cart;
import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.Json;
import com.hackalem.domain.cart.CartService;
import com.hackalem.domain.port.CartPort;
import com.hackalem.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;

/** Stateful sample cart. Validation and mutation share a single DB transaction. */
@Component
@ConditionalOnProperty(name="app.cart-mode",havingValue="sample",matchIfMissing=true)
public class SampleCartAdapter implements CartPort {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final Json json;private final String url;
    public SampleCartAdapter(JdbcTemplate db,TransactionTemplate tx,Json json,@Value("${app.cart-url}") String url){this.db=db;this.tx=tx;this.json=json;this.url=url;}
    @Override public CartSnapshot get(TrustedScope scope){
        return tx.execute(s->readSnapshot(scope));
    }
    private CartSnapshot readSnapshot(TrustedScope scope){
        var versions=db.queryForList("SELECT version FROM carts WHERE id=? AND owner_id=? FOR SHARE",scope.cartId(),scope.principalId());
        if(versions.isEmpty())throw ApiException.missing();
        var lines=db.query("SELECT * FROM cart_lines WHERE cart_id=? ORDER BY bucket",(r,n)->new CartLine(r.getString("article"),new Quantity(CartService.decimal(r.getBigDecimal("quantity")),r.getString("unit"),CartService.decimal(r.getBigDecimal("step"))),new Money(CartService.decimal(r.getBigDecimal("price")),r.getString("currency")),r.getString("warehouse"),r.getString("bucket")),scope.cartId());
        return new CartSnapshot(scope.cartId().toString(),versions.getFirst().get("version").toString(),lines,url);
    }
    @Override public Optional<OperationOutcome> lookupOperation(TrustedScope scope,String operation){return db.query("SELECT body::text FROM sample_cart_outcomes WHERE operation_id=? AND owner_id=?",(r,n)->json.read(r.getString(1),OperationOutcome.class),UUID.fromString(operation),scope.principalId()).stream().findFirst();}
    @Override public Optional<OperationOutcome> reconcile(TrustedScope scope,ProposalSnapshot proposal){
        return Optional.of(lookupOperation(scope,proposal.operationId()).orElseGet(()->addConditionally(scope,proposal)));
    }
    @Override public OperationOutcome addConditionally(TrustedScope scope,ProposalSnapshot proposal){return tx.execute(s->{
        if(!scope.cartId().toString().equals(proposal.cartId()))throw ApiException.missing();
        db.queryForList("SELECT id FROM carts WHERE id=? AND owner_id=? FOR UPDATE",scope.cartId(),scope.principalId());
        var old=lookupOperation(scope,proposal.operationId());if(old.isPresent())return old.get();
        var before=get(scope);String error=null;
        if(!before.version().equals(proposal.expectedCartVersion()))error="cart_version_conflict";
        Map<String,BigDecimal> additions=new TreeMap<>();Map<String,ProposalLine> lines=new HashMap<>();
        for(var line:proposal.lines()){
            var duplicate=lines.putIfAbsent(line.stockBucket(),line);
            if(duplicate!=null && (!duplicate.article().equals(line.article()) || !duplicate.addQuantity().unit().equals(line.addQuantity().unit()) || !duplicate.unitPrice().equals(line.unitPrice())))error="inconsistent_stock_bucket";
            additions.merge(line.stockBucket(),CartService.positive(line.addQuantity().value()),BigDecimal::add);
        }
        for(var entry:additions.entrySet()){
            var line=lines.get(entry.getKey());var offers=db.queryForList("SELECT * FROM sample_offers WHERE bucket=? FOR UPDATE",entry.getKey());
            if(offers.isEmpty()){error="offer_unavailable";continue;}
            var offer=offers.getFirst();BigDecimal add=entry.getValue(),step=(BigDecimal)offer.get("step");
            if(!line.article().equals(offer.get("article")) || !line.addQuantity().unit().equals(offer.get("unit")) || !line.warehouse().equals(offer.get("warehouse")) || add.remainder(step).signum()!=0)error="invalid_quantity_unit";
            if(!line.offerVersion().equals(offer.get("version").toString()) || new BigDecimal(line.unitPrice().amount()).compareTo((BigDecimal)offer.get("price"))!=0 || !line.unitPrice().currency().equals(offer.get("currency")))error="offer_changed";
            BigDecimal existing=before.lines().stream().filter(l->l.stockBucket().equals(entry.getKey())).map(l->new BigDecimal(l.quantity().value())).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(existing.add(add).compareTo((BigDecimal)offer.get("available"))>0)error="insufficient_stock";
        }
        if(error!=null)return store(scope,new OperationOutcome(proposal.operationId(),proposal.id(),"failed",before,error));
        for(var entry:additions.entrySet()){
            var line=lines.get(entry.getKey());
            db.update("INSERT INTO cart_lines(cart_id,article,unit,warehouse,bucket,quantity,price,currency,step) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(cart_id,bucket) DO UPDATE SET quantity=cart_lines.quantity+EXCLUDED.quantity,price=EXCLUDED.price",scope.cartId(),line.article(),line.addQuantity().unit(),line.warehouse(),line.stockBucket(),entry.getValue(),new BigDecimal(line.unitPrice().amount()),line.unitPrice().currency(),new BigDecimal(line.addQuantity().step()));
        }
        db.update("UPDATE carts SET version=version+1 WHERE id=?",scope.cartId());
        return store(scope,new OperationOutcome(proposal.operationId(),proposal.id(),"succeeded",get(scope),null));
    });}
    private OperationOutcome store(TrustedScope scope,OperationOutcome result){db.update("INSERT INTO sample_cart_outcomes VALUES (?,?,?::jsonb)",UUID.fromString(result.id()),scope.principalId(),json.write(result));return result;}
}
