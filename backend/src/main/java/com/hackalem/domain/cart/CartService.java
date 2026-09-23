package com.hackalem.domain.cart;

import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.Json;
import com.hackalem.domain.chat.ChatService;
import com.hackalem.domain.port.*;
import com.hackalem.ai.agent.Limits;
import com.hackalem.web.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** The only domain entry to cart mutation. Never passed to LLM tools. */
@Service
public class CartService {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final Json json;
    private final ChatService chat;private final ObjectProvider<StockPort> stocks;private final CartPort cart;private final Limits limits;
    public CartService(JdbcTemplate db,TransactionTemplate tx,Json json,ChatService chat,
        ObjectProvider<StockPort> stocks,CartPort cart,Limits limits){this.db=db;this.tx=tx;this.json=json;this.chat=chat;this.stocks=stocks;this.cart=cart;this.limits=limits;}
    private RowMapper<ProposalSnapshot> mapper(){return (r,n)->new ProposalSnapshot(r.getString("id"),r.getString("conversation_id"),r.getString("cart_id"),r.getString("revision"),r.getString("digest"),r.getString("operation_id"),r.getString("expected_cart_version"),r.getString("state"),r.getTimestamp("expires_at").toInstant(),List.of(json.read(r.getString("lines"),ProposalLine[].class)));}
    public ProposalSnapshot get(TrustedScope scope,UUID id){return db.query("SELECT * FROM cart_proposals WHERE id=? AND owner_id=?",mapper(),id,scope.principalId()).stream().findFirst().orElseThrow(ApiException::missing);}
    public CartSnapshot cart(TrustedScope scope){return cart.get(scope);}
    public ProposalSnapshot propose(TrustedScope scope,ProposalRequest req,String key){return propose(scope,req,key,null);}
    public ProposalSnapshot propose(TrustedScope scope,ProposalRequest req,String key,ChatService.Claim claim){
        ChatService.validKey(key);
        chat.conversation(scope,req.conversationId(),false);
        String requestHash=Json.hash(json.write(req));
        String dedupKey=key+":"+requestHash;
        var existing=db.query("SELECT * FROM cart_proposals WHERE conversation_id=? AND left(dedup_key,?)=?",mapper(),req.conversationId(),key.length()+1,key+":");
        if(!existing.isEmpty()) {
            String stored=db.queryForObject("SELECT dedup_key FROM cart_proposals WHERE id=?",String.class,UUID.fromString(existing.getFirst().id()));
            if(!dedupKey.equals(stored))throw ApiException.conflict("idempotency_conflict");return existing.getFirst();
        }
        var state=chat.state(scope,req.conversationId());
        if(!state.version().equals(req.expectedStateVersion()))throw ApiException.conflict("stale_dialogue");
        var result=chat.resultSet(scope,req.conversationId(),ChatService.uuid(req.resultSetId()));
        Set<String> allowed=new HashSet<>(result.products().stream().map(ProductDetails::article).toList());
        if(req.lines().stream().anyMatch(line->!allowed.contains(line.article())))throw new ApiException(400,"selection_not_in_result_set");
        StockPort stock=stocks.getIfAvailable();if(stock==null)throw ApiException.unavailable("stock_source_unavailable");
        limits.rate("proposal",scope.principalId().toString(),30);
        List<OfferSnapshot> offers=stock.getOffers(req.lines(),scope); // Provider I/O outside transaction.
        CartSnapshot before=cart.get(scope);
        LinkedHashMap<String,ProposalLine> aggregated=new LinkedHashMap<>();
        for(Selection selection:req.lines()){
            var offer=offers.stream().filter(o->o.article().equals(selection.article()) && o.available().unit().equals(selection.unit()) && o.warehouse().equals(selection.warehouse())).findFirst().orElseThrow(()->ApiException.conflict("offer_unavailable"));
            if(!offer.expiresAt().isAfter(Instant.now()))throw ApiException.conflict("offer_expired");
            BigDecimal add=positive(selection.addQuantity());BigDecimal step=positive(offer.available().step());
            if(add.remainder(step).signum()!=0)throw new ApiException(400,"invalid_quantity_step");
            ProposalLine old=aggregated.get(offer.stockBucket());
            if(old!=null){
                if(!old.article().equals(offer.article()) || !old.addQuantity().unit().equals(offer.available().unit()) || !old.unitPrice().equals(offer.price()))throw ApiException.conflict("inconsistent_stock_bucket");
                add=add.add(new BigDecimal(old.addQuantity().value()));
            }
            BigDecimal existingQuantity=before.lines().stream().filter(l->l.stockBucket().equals(offer.stockBucket())).map(l->new BigDecimal(l.quantity().value())).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(existingQuantity.add(add).compareTo(new BigDecimal(offer.available().value()))>0)throw ApiException.conflict("insufficient_stock");
            aggregated.put(offer.stockBucket(),new ProposalLine(offer.article(),new Quantity(decimal(add),offer.available().unit(),offer.available().step()),offer.price(),offer.warehouse(),offer.stockBucket(),offer.version(),List.of(req.resultSetId())));
        }
        var lines=aggregated.values().stream().sorted(Comparator.comparing(ProposalLine::stockBucket)).toList();
        return tx.execute(s->{
            chat.conversation(scope,req.conversationId(),true);if(claim!=null)chat.assertActive(claim);
            var replay=db.query("SELECT * FROM cart_proposals WHERE conversation_id=? AND dedup_key=?",mapper(),req.conversationId(),dedupKey);
            if(!replay.isEmpty())return replay.getFirst();
            var current=chat.state(scope,req.conversationId());if(!current.version().equals(req.expectedStateVersion()))throw ApiException.conflict("stale_dialogue");
            UUID id=UUID.randomUUID(),operation=UUID.randomUUID();long revision=Long.parseLong(current.version())+1;
            Instant expiry=offers.stream().map(OfferSnapshot::expiresAt).min(Comparator.naturalOrder()).orElse(Instant.now()).isBefore(Instant.now().plusSeconds(300))?offers.stream().map(OfferSnapshot::expiresAt).min(Comparator.naturalOrder()).orElseThrow():Instant.now().plusSeconds(300);
            String digest=Json.hash(id+"|"+scope.cartId()+"|"+revision+"|"+before.version()+"|"+expiry+"|"+json.write(lines));
            chat.supersede(req.conversationId());
            db.update("INSERT INTO cart_proposals(id,conversation_id,owner_id,cart_id,revision,digest,operation_id,expected_cart_version,expires_at,lines,dedup_key) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)",id,req.conversationId(),scope.principalId(),scope.cartId(),revision,digest,operation,Long.parseLong(before.version()),Timestamp.from(expiry),json.write(lines),dedupKey);
            chat.writeState(req.conversationId(),new DialogueState(current.version(),current.category(),current.budget(),current.hardConstraints(),current.quantity(),current.lastResultSetId(),current.selectedArticles(),current.fulfillmentOptionId(),id.toString(),current.attachmentId(),current.attachmentVersion()));
            return get(scope,id);
        });
    }
    private record Admission(OperationOutcome outcome,ProposalSnapshot proposal,boolean execute){}
    public OperationOutcome confirm(TrustedScope scope,UUID id,String key,ConfirmRequest req){
        ChatService.validKey(key);ProposalSnapshot initial=get(scope,id);
        String hash=Json.hash(id+"|"+req.revision()+"|"+req.digest()+"|"+req.origin()+"|"+req.text());
        Admission admission=tx.execute(s->{
            // Serialize by conversation, then proposal, then operation: same order as dialogue/propose.
            chat.conversation(scope,UUID.fromString(initial.conversationId()),true);
            db.queryForList("SELECT id FROM cart_proposals WHERE id=? FOR UPDATE",id);
            var prior=db.queryForList("SELECT request_hash,operation_id FROM confirmation_keys WHERE owner_id=? AND idempotency_key=?",scope.principalId(),key);
            if(!prior.isEmpty()){
                if(!hash.equals(prior.getFirst().get("request_hash")))throw ApiException.conflict("idempotency_conflict");
                return new Admission(storedOperation(scope,(UUID)prior.getFirst().get("operation_id")),initial,false);
            }
            var proposal=get(scope,id);
            if(!proposal.revision().equals(req.revision()) || !proposal.digest().equals(req.digest()))throw ApiException.conflict("stale_proposal");
            var old=db.queryForList("SELECT id,request_hash FROM cart_operations WHERE proposal_id=?",id);
            if(!old.isEmpty()){
                if(!hash.equals(old.getFirst().get("request_hash")))throw ApiException.conflict("confirmation_conflict");
                db.update("INSERT INTO confirmation_keys VALUES (?,?,?,?)",scope.principalId(),key,hash,UUID.fromString(proposal.operationId()));
                return new Admission(storedOperation(scope,UUID.fromString(proposal.operationId())),proposal,false);
            }
            if(!proposal.status().equals("pending"))throw ApiException.conflict("proposal_"+proposal.status());
            if(!proposal.expiresAt().isAfter(Instant.now()))throw ApiException.conflict("proposal_expired");
            if(req.origin()==null || (req.origin()==ConsentOrigin.user_text && !explicitConsent(req.text())) || (req.origin()==ConsentOrigin.button && req.text()!=null))throw new ApiException(400,"explicit_consent_required");
            limits.rate("confirm",scope.principalId().toString(),20);
            var pending=new OperationOutcome(proposal.operationId(),id.toString(),"outcome_unknown",null,"awaiting_adapter");
            // Durable intent before any external I/O. Recovery always looks up this operation ID.
            db.update("INSERT INTO cart_operations(id,proposal_id,owner_id,state,consent_origin,request_hash,result) VALUES (?,?,?,'outcome_unknown',?,?,?::jsonb)",UUID.fromString(proposal.operationId()),id,scope.principalId(),req.origin().name(),hash,json.write(pending));
            db.update("INSERT INTO confirmation_keys VALUES (?,?,?,?)",scope.principalId(),key,hash,UUID.fromString(proposal.operationId()));
            return new Admission(pending,proposal,true);
        });
        if(!admission.execute())return admission.outcome();
        try{return record(scope,admission.proposal(),cart.addConditionally(scope,admission.proposal()));}
        catch(Exception e){return admission.outcome();} // Unknown may have committed remotely. Never blind retry.
    }
    public OperationOutcome operation(TrustedScope scope,UUID id){
        OperationOutcome stored=storedOperation(scope,id);
        if(!stored.status().equals("outcome_unknown"))return stored;
        try {var proposal=get(scope,UUID.fromString(stored.proposalId()));return cart.reconcile(scope,proposal).map(outcome->record(scope,proposal,outcome)).orElse(stored);}
        catch(Exception e){return stored;}
    }
    private OperationOutcome storedOperation(TrustedScope scope,UUID id){return db.query("SELECT result::text FROM cart_operations WHERE id=? AND owner_id=?",(r,n)->json.read(r.getString(1),OperationOutcome.class),id,scope.principalId()).stream().findFirst().orElseThrow(ApiException::missing);}
    private OperationOutcome record(TrustedScope scope,ProposalSnapshot proposal,OperationOutcome result){
        if(!result.id().equals(proposal.operationId()) || !result.proposalId().equals(proposal.id()) || !List.of("succeeded","failed","outcome_unknown").contains(result.status()))throw ApiException.unavailable("invalid_cart_outcome");
        return tx.execute(s->{
            chat.conversation(scope,UUID.fromString(proposal.conversationId()),true);
            db.queryForList("SELECT id FROM cart_proposals WHERE id=? FOR UPDATE",UUID.fromString(proposal.id()));
            db.queryForList("SELECT id FROM cart_operations WHERE id=? AND owner_id=? FOR UPDATE",UUID.fromString(result.id()),scope.principalId());
            var previous=storedOperation(scope,UUID.fromString(result.id()));if(!previous.status().equals("outcome_unknown"))return previous;
            db.update("UPDATE cart_operations SET state=?,result=?::jsonb,updated_at=now() WHERE id=?",result.status(),json.write(result),UUID.fromString(result.id()));
            if(result.status().equals("succeeded"))db.update("UPDATE cart_proposals SET state='confirmed' WHERE id=?",UUID.fromString(proposal.id()));
            if(result.status().equals("failed"))db.update("UPDATE cart_proposals SET state='superseded' WHERE id=?",UUID.fromString(proposal.id()));
            return result;
        });
    }
    public ProposalSnapshot reject(TrustedScope scope,UUID id){
        var p=get(scope,id);return tx.execute(s->{chat.conversation(scope,UUID.fromString(p.conversationId()),true);
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM cart_operations WHERE proposal_id=?)",Boolean.class,id)))throw ApiException.conflict("operation_already_started");
            db.update("UPDATE cart_proposals SET state='rejected' WHERE id=? AND state='pending'",id);return get(scope,id);});
    }
    public static boolean explicitConsent(String text){return text!=null && Set.of("да","добавь","подтверждаю","подтверждаю добавление").contains(text.strip().toLowerCase(Locale.ROOT));}
    public static BigDecimal positive(String value){try{if(value==null || !value.matches("[0-9]+(\\.[0-9]+)?"))throw new NumberFormatException();BigDecimal q=new BigDecimal(value);if(q.signum()<=0 || q.scale()>6 || q.precision()>18)throw new NumberFormatException();return q;}catch(Exception e){throw new ApiException(400,"invalid_quantity");}}
    public static String decimal(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
}
