package com.hackalem.domain.chat;

import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.Json;
import com.hackalem.web.ApiException;
import com.hackalem.ai.agent.Limits;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ChatService {
    private final JdbcTemplate db; private final TransactionTemplate tx; private final Json json;
    private final Limits limits; private final MeterRegistry metrics; private final int queueSize,deadline;
    public ChatService(JdbcTemplate db,TransactionTemplate tx,Json json,Limits limits,MeterRegistry metrics,
        @Value("${app.limits.queue-size}") int queueSize,@Value("${app.worker.deadline-seconds}") int deadline) {
        this.db=db;this.tx=tx;this.json=json;this.limits=limits;this.metrics=metrics;this.queueSize=queueSize;this.deadline=deadline;
    }
    private static final RowMapper<Conversation> CONVERSATION=(r,n)->new Conversation(r.getString("id"),r.getString("version"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<Message> MESSAGE=(r,n)->new Message(r.getString("id"),r.getString("seq"),r.getString("role"),r.getString("text"),r.getString("run_id"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<RunSnapshot> RUN=(r,n)->new RunSnapshot(r.getString("id"),r.getString("conversation_id"),r.getString("state"),r.getString("epoch"),r.getString("event_seq"),r.getString("text"),r.getString("error_code"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toInstant());
    public Conversation create(TrustedScope scope) {
        limits.rate("conversation",scope.principalId().toString(),30);
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO conversations(id,owner_id,state) VALUES (?,?,?::jsonb)",id,scope.principalId(),json.write(emptyState()));
        return conversation(scope,id,false);
    }
    public Conversation conversation(TrustedScope scope,UUID id,boolean lock) {
        return db.query("SELECT * FROM conversations WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),CONVERSATION,id,scope.principalId())
            .stream().findFirst().orElseThrow(ApiException::missing);
    }
    public ConversationPage conversations(TrustedScope scope,String cursor,int limit) {
        int page=pageSize(limit); UUID after=cursor==null?null:uuid(cursor);
        List<Conversation> rows=after==null?db.query("SELECT * FROM conversations WHERE owner_id=? ORDER BY created_at,id LIMIT ?",CONVERSATION,scope.principalId(),page+1):
            db.query("SELECT * FROM conversations WHERE owner_id=? AND (created_at,id) > (SELECT created_at,id FROM conversations WHERE id=? AND owner_id=?) ORDER BY created_at,id LIMIT ?",CONVERSATION,scope.principalId(),after,scope.principalId(),page+1);
        return new ConversationPage(rows.stream().limit(page).toList(),rows.size()>page?rows.get(page-1).id():null);
    }
    public MessagePage messages(TrustedScope scope,UUID id,String cursor,int limit) {
        conversation(scope,id,false);int page=pageSize(limit);long after=cursor==null?0:counter(cursor);
        var rows=db.query("SELECT * FROM messages WHERE conversation_id=? AND seq>? ORDER BY seq LIMIT ?",MESSAGE,id,after,page+1);
        return new MessagePage(rows.stream().limit(page).toList(),rows.size()>page?rows.get(page-1).sequence():null);
    }
    public AcceptedTurn submit(TrustedScope scope,UUID conversation,String key,TurnRequest request) {
        validKey(key);String hash=Json.hash(json.write(request));metrics.counter("chat.turns.offered").increment();
        return tx.execute(s->{
            conversation(scope,conversation,true);
            var prior=db.queryForList("SELECT message_id,id,payload_hash FROM chat_runs WHERE conversation_id=? AND idempotency_key=?",conversation,key);
            if(!prior.isEmpty()) {
                if(!hash.equals(prior.getFirst().get("payload_hash"))) throw ApiException.conflict("idempotency_conflict");
                return new AcceptedTurn(prior.getFirst().get("message_id").toString(),prior.getFirst().get("id").toString());
            }
            if(request.expectedStateVersion()!=null && !state(scope,conversation).version().equals(request.expectedStateVersion())) throw ApiException.conflict("stale_dialogue");
            if(request.resultSetId()!=null) resultSet(scope,conversation,uuid(request.resultSetId()));
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM chat_runs WHERE conversation_id=? AND state IN ('queued','generating'))",Boolean.class,conversation))) throw ApiException.conflict("active_run");
            // One shared admission lock bounds the durable queue across replicas.
            db.execute("SELECT pg_advisory_xact_lock(491701)");
            if(db.queryForObject("SELECT count(*) FROM chat_runs WHERE state IN ('queued','generating')",Long.class)>=queueSize) throw new ApiException(503,"queue_full");
            limits.rate("turn",scope.principalId().toString(),30);
            applyExplicitContext(scope,conversation,request);
            UUID message=UUID.randomUUID(),run=UUID.randomUUID();
            db.update("INSERT INTO chat_runs(id,conversation_id,owner_id,message_id,idempotency_key,payload_hash,state,deadline) VALUES (?,?,?,?,?,?,'queued',?)",run,conversation,scope.principalId(),message,key,hash,Timestamp.from(Instant.now().plusSeconds(deadline)));
            long seq=nextSequence(conversation);
            db.update("INSERT INTO messages(id,conversation_id,seq,role,text,run_id) VALUES (?,?,?,'user',?,?)",message,conversation,seq,request.text(),run);
            metrics.counter("chat.turns.admitted").increment();return new AcceptedTurn(message.toString(),run.toString());
        });
    }
    public RunSnapshot run(TrustedScope scope,UUID id) {
        return db.query("SELECT * FROM chat_runs WHERE id=? AND owner_id=?",RUN,id,scope.principalId()).stream().findFirst().orElseThrow(ApiException::missing);
    }
    public RunSnapshot internalRun(UUID id) {return db.queryForObject("SELECT * FROM chat_runs WHERE id=?",RUN,id);}
    public record Claim(UUID id,UUID conversation,TrustedScope scope,long epoch,Instant deadline) {}
    public Optional<Claim> claim() {
        return tx.execute(s->{
            var rows=db.queryForList("SELECT r.*,v.cart_id FROM chat_runs r JOIN visitor_sessions v ON v.id=r.owner_id WHERE r.state='queued' ORDER BY r.created_at FOR UPDATE OF r SKIP LOCKED LIMIT 1");
            if(rows.isEmpty()) return Optional.empty();
            var r=rows.getFirst();UUID id=(UUID)r.get("id");long epoch=((Number)r.get("epoch")).longValue()+1;
            db.update("UPDATE chat_runs SET state='generating',epoch=?,lease_until=deadline WHERE id=?",epoch,id);
            event(id,epoch,"run.started",new StatusPayload("generating",null));
            metrics.timer("chat.queue.wait").record(java.time.Duration.between(((Timestamp)r.get("created_at")).toInstant(),Instant.now()));
            return Optional.of(new Claim(id,(UUID)r.get("conversation_id"),new TrustedScope((UUID)r.get("owner_id"),(UUID)r.get("cart_id")),epoch,((Timestamp)r.get("deadline")).toInstant()));
        });
    }
    /** All writes are fenced by epoch, terminal state and total deadline. */
    public void assertActive(Claim claim) {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM chat_runs WHERE id=? AND epoch=? AND state='generating' AND deadline>now())",Boolean.class,claim.id(),claim.epoch()))) throw ApiException.conflict("run_not_active");
    }
    private void lockRun(Claim claim) {
        conversation(claim.scope(),claim.conversation(),true);
        db.queryForList("SELECT id FROM chat_runs WHERE id=? FOR UPDATE",claim.id()); assertActive(claim);
    }
    public void delta(Claim claim,String text) {
        if(text==null || text.isEmpty())return;
        tx.executeWithoutResult(s->{lockRun(claim);var before=internalRun(claim.id());if(before.text().isEmpty())metrics.timer("chat.ttft").record(java.time.Duration.between(before.createdAt(),Instant.now()));db.update("UPDATE chat_runs SET text=text||? WHERE id=?",text,claim.id());event(claim.id(),claim.epoch(),"message.delta",new DeltaPayload(text));});
    }
    public void emit(Claim claim,String type,EventPayload payload) {
        tx.executeWithoutResult(s->{lockRun(claim);event(claim.id(),claim.epoch(),type,payload);});
    }
    private void event(UUID id,long epoch,String type,EventPayload payload) {
        long seq=db.queryForObject("UPDATE chat_runs SET event_seq=event_seq+1 WHERE id=? RETURNING event_seq",Long.class,id);
        if(payload instanceof TerminalPayload terminal) payload=new TerminalPayload(internalRun(id),terminal.replayUnavailable());
        var event=new ChatEvent(epoch+":"+seq,id.toString(),String.valueOf(seq),String.valueOf(epoch),type,"1",payload);
        db.update("INSERT INTO run_events(run_id,seq,epoch,body) VALUES (?,?,?,?::jsonb)",id,seq,epoch,json.write(event));
    }
    public void finish(Claim claim,String status,String code) {
        tx.executeWithoutResult(s->{
            conversation(claim.scope(),claim.conversation(),true);
            var current=db.queryForObject("SELECT * FROM chat_runs WHERE id=? FOR UPDATE",RUN,claim.id());
            if(current==null || !current.epoch().equals(String.valueOf(claim.epoch())) || terminal(current.status()))return;
            if(status.equals("completed") && !Instant.now().isBefore(claim.deadline())) {statusTerminal(claim,current,"failed","deadline_exceeded");return;}
            statusTerminal(claim,current,status,code);
        });
    }
    private void statusTerminal(Claim claim,RunSnapshot current,String status,String code) {
        if(!current.text().isBlank()) db.update("INSERT INTO messages(id,conversation_id,seq,role,text,run_id) VALUES (?,?,?,'assistant',?,?) ON CONFLICT(run_id,role) DO NOTHING",UUID.randomUUID(),claim.conversation(),nextSequence(claim.conversation()),current.text(),claim.id());
        db.update("UPDATE chat_runs SET state=?,error_code=?,finished_at=now(),lease_until=NULL WHERE id=?",status,code,claim.id());
        event(claim.id(),claim.epoch(),"run."+status,new TerminalPayload(internalRun(claim.id()),false));
        metrics.counter("chat.turns.terminal","status",status).increment();
        metrics.timer("chat.completion","status",status).record(java.time.Duration.between(current.createdAt(),Instant.now()));
    }
    public RunSnapshot cancel(TrustedScope scope,UUID id) {
        return tx.execute(s->{
            RunSnapshot before=run(scope,id);
            conversation(scope,uuid(before.conversationId()),true);
            RunSnapshot current=db.queryForObject("SELECT * FROM chat_runs WHERE id=? FOR UPDATE",RUN,id);
            if(current!=null && !terminal(current.status()))statusTerminal(new Claim(id,uuid(current.conversationId()),scope,Long.parseLong(current.epoch()),Instant.MAX),current,"cancelled","user_cancelled");
            return run(scope,id);
        });
    }
    public List<Claim> expiredClaims() {
        return db.query("SELECT r.*,v.cart_id FROM chat_runs r JOIN visitor_sessions v ON v.id=r.owner_id WHERE r.state IN ('queued','generating') AND r.deadline<=now() LIMIT 100",(r,n)->new Claim(r.getObject("id",UUID.class),r.getObject("conversation_id",UUID.class),new TrustedScope(r.getObject("owner_id",UUID.class),r.getObject("cart_id",UUID.class)),r.getLong("epoch"),r.getTimestamp("deadline").toInstant()));
    }
    public List<Message> context(Claim claim) {
        var rows=db.query("SELECT * FROM (SELECT * FROM messages WHERE conversation_id=? ORDER BY seq DESC LIMIT 12) recent ORDER BY seq",MESSAGE,claim.conversation());
        int total=0;LinkedList<Message> bounded=new LinkedList<>();
        for(int i=rows.size()-1;i>=0;i--){Message m=rows.get(i);if(total+m.text().length()>16000)break;bounded.addFirst(m);total+=m.text().length();}
        return bounded;
    }
    public ProductResultSet saveResults(Claim claim,ProductResultSet result) {
        return tx.execute(s->{lockRun(claim); UUID id=UUID.randomUUID();var saved=new ProductResultSet(id.toString(),"1",List.copyOf(result.products()),List.copyOf(result.offers()));
            db.update("INSERT INTO result_sets(id,conversation_id,owner_id,body) VALUES (?,?,?,?::jsonb)",id,claim.conversation(),claim.scope().principalId(),json.write(saved));
            var old=state(claim.scope(),claim.conversation());supersede(claim.conversation());
            writeState(claim.conversation(),new DialogueState(old.version(),old.category(),old.budget(),old.hardConstraints(),old.quantity(),id.toString(),List.of(),null,null,old.attachmentId(),old.attachmentVersion()));
            event(claim.id(),claim.epoch(),"products.result",new ProductsPayload(saved));return saved;
        });
    }
    public ProductResultSet resultSet(TrustedScope scope,UUID conversation,UUID id) {
        return db.query("SELECT body::text FROM result_sets WHERE id=? AND conversation_id=? AND owner_id=?",(r,n)->json.read(r.getString(1),ProductResultSet.class),id,conversation,scope.principalId()).stream().findFirst().orElseThrow(ApiException::missing);
    }
    public DialogueState state(TrustedScope scope,UUID id) {
        conversation(scope,id,false);return db.queryForObject("SELECT state::text FROM conversations WHERE id=?",(r,n)->json.read(r.getString(1),DialogueState.class),id);
    }
    public DialogueState updateState(TrustedScope scope,UUID id,UpdateDialogue req) {
        return tx.execute(s->{conversation(scope,id,true);var old=state(scope,id);
            if(!old.version().equals(req.expectedVersion()))throw ApiException.conflict("stale_dialogue");
            String resultId=req.resultSetId()==null?old.lastResultSetId():req.resultSetId();List<String> selected=old.selectedArticles();
            if(req.selectedIndices()!=null) {
                if(resultId==null)throw ApiException.conflict("result_set_required");
                var results=resultSet(scope,id,uuid(resultId));
                if(req.selectedIndices().stream().anyMatch(i->i==null || i<0 || i>=results.products().size()))throw new ApiException(400,"invalid_selection");
                selected=req.selectedIndices().stream().map(i->results.products().get(i).article()).distinct().toList();
            }
            supersede(id);
            return writeState(id,new DialogueState(old.version(),req.category()==null?old.category():req.category(),req.budget()==null?old.budget():req.budget(),req.hardConstraints()==null?old.hardConstraints():Map.copyOf(req.hardConstraints()),req.quantity()==null?old.quantity():req.quantity(),resultId,selected,req.fulfillmentOptionId(),null,req.attachmentId(),req.attachmentVersion()));
        });
    }
    public void supersede(UUID id) {db.update("UPDATE cart_proposals SET state='superseded' WHERE conversation_id=? AND state='pending'",id);}
    public DialogueState writeState(UUID id,DialogueState value) {
        long version=db.queryForObject("UPDATE conversations SET version=version+1 WHERE id=? RETURNING version",Long.class,id);
        var saved=new DialogueState(String.valueOf(version),value.category(),value.budget(),value.hardConstraints(),value.quantity(),value.lastResultSetId(),value.selectedArticles(),value.fulfillmentOptionId(),value.activeProposalId(),value.attachmentId(),value.attachmentVersion());
        db.update("UPDATE conversations SET state=?::jsonb WHERE id=?",json.write(saved),id);return saved;
    }
    public Optional<String> previousTool(Claim claim,String callId,String argsHash) {
        return db.query("SELECT args_hash,result::text FROM tool_invocations WHERE run_id=? AND call_id=?",(r,n)->{
            if(!argsHash.equals(r.getString(1)))throw ApiException.conflict("tool_call_conflict");return r.getString(2);},claim.id(),callId).stream().findFirst();
    }
    public void saveTool(Claim claim,String callId,String name,String argsHash,String result) {
        tx.executeWithoutResult(s->{lockRun(claim);db.update("INSERT INTO tool_invocations(run_id,call_id,name,args_hash,result) VALUES (?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",claim.id(),callId,name,argsHash,result);});
    }
    private long nextSequence(UUID id) {return db.queryForObject("UPDATE conversations SET next_seq=next_seq+1 WHERE id=? RETURNING next_seq",Long.class,id);}
    private void applyExplicitContext(TrustedScope scope,UUID conversation,TurnRequest request) {
        var old=state(scope,conversation);String resultId=request.resultSetId()==null?old.lastResultSetId():request.resultSetId();
        Quantity quantity=old.quantity();List<String> selected=old.selectedArticles();Money budget=old.budget();boolean changed=request.resultSetId()!=null;
        String text=request.text().strip().toLowerCase(java.util.Locale.ROOT);
        var amount=java.util.regex.Pattern.compile("^(?:нужно|нужны|надо)\\s+([0-9]+(?:[.,][0-9]+)?)(?:\\s+(?:штук|штуки|шт\\.?))?$").matcher(text);
        if(amount.matches()) {
            String value=amount.group(1).replace(',','.');com.hackalem.domain.cart.CartService.positive(value);
            if(quantity!=null)quantity=new Quantity(value,quantity.unit(),quantity.step());
            else if(resultId!=null){var result=resultSet(scope,conversation,uuid(resultId));if(!result.offers().isEmpty()){var offer=result.offers().getFirst();quantity=new Quantity(value,offer.available().unit(),offer.available().step());}}
            changed=true;
        }
        if(resultId!=null && text.matches(".*(?:сравни|выбери).*первые два.*")) {
            var result=resultSet(scope,conversation,uuid(resultId));
            selected=result.products().stream().limit(2).map(ProductDetails::article).toList();changed=true;
        }
        if(resultId!=null && text.equals("дешевле")) {
            var result=resultSet(scope,conversation,uuid(resultId));
            var cheapest=result.offers().stream().min(java.util.Comparator.comparing(o->new java.math.BigDecimal(o.price().amount())));
            if(cheapest.isPresent()){var price=cheapest.get().price();var max=new java.math.BigDecimal(price.amount()).subtract(new java.math.BigDecimal("0.01")).max(java.math.BigDecimal.ZERO);budget=new Money(max.toPlainString(),price.currency());changed=true;}
        }
        if(changed){supersede(conversation);writeState(conversation,new DialogueState(old.version(),old.category(),budget,old.hardConstraints(),quantity,resultId,selected,old.fulfillmentOptionId(),null,old.attachmentId(),old.attachmentVersion()));}
    }
    public static boolean terminal(String state) {return List.of("completed","failed","cancelled").contains(state);}
    public static void validKey(String key) {if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}"))throw new ApiException(400,"invalid_idempotency_key");}
    public static UUID uuid(String id) {try{return UUID.fromString(id);}catch(Exception e){throw new ApiException(400,"invalid_id");}}
    public static long counter(String value) {try{long n=Long.parseLong(value);if(n<0)throw new NumberFormatException();return n;}catch(Exception e){throw new ApiException(400,"invalid_cursor");}}
    private static int pageSize(int size){if(size<1 || size>100)throw new ApiException(400,"invalid_page_size");return size;}
    private static DialogueState emptyState(){return new DialogueState("0",null,null,Map.of(),null,null,List.of(),null,null,null,null);}
}
