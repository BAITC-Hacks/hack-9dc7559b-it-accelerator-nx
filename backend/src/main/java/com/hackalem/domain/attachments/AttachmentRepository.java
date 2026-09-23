package com.hackalem.domain.attachments;

import com.hackalem.domain.Json;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static com.hackalem.domain.attachments.AttachmentModels.*;

@Repository
public class AttachmentRepository {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final Json json;
    private static final RowMapper<Stored> STORED=(r,n)->new Stored(r.getString("id"),r.getString("owner_subject"),r.getString("conversation_id"),r.getLong("revision"),r.getInt("desired_version"),r.getString("status"),r.getString("stage"),r.getString("filename"),r.getString("mime_type"),r.getString("extension"),r.getBytes("original_bytes"),r.getString("warnings"),r.getString("error_code"),r.getBoolean("deleted"));
    public AttachmentRepository(JdbcTemplate db,TransactionTemplate tx,Json json){this.db=db;this.tx=tx;this.json=json;}
    public Accepted create(TrustedScope scope,UUID conversation,String filename,String mime,String extension,byte[] bytes){
        return tx.execute(s->{
            db.queryForList("SELECT id FROM attachment_queue_guard WHERE id=1 FOR UPDATE");
            // Defense in depth: lock and check the trusted owner at the metadata/job commit.
            if(db.queryForList("SELECT id FROM conversations WHERE id=? AND owner_id=? FOR UPDATE",conversation,scope.principalId()).isEmpty())throw ApiException.missing();
            String hash=hex(bytes);
            var prior=db.query("SELECT * FROM attachments WHERE owner_subject=? AND conversation_id=? AND content_hash=? AND deleted=false ORDER BY created_at LIMIT 1",STORED,scope.principalId(),conversation,hash);
            if(!prior.isEmpty()){
                var existing=prior.getFirst();String job=db.queryForObject("SELECT id FROM attachment_jobs WHERE attachment_id=? ORDER BY created_at DESC LIMIT 1",String.class,existing.id());
                return new Accepted(existing.id(),job,Long.toString(existing.version()),existing.status(),true);
            }
            if(db.queryForObject("SELECT count(*) FROM attachment_jobs WHERE status IN ('QUEUED','RUNNING')",Integer.class)>=AttachmentLimits.QUEUE || db.queryForObject("SELECT count(*) FROM attachments WHERE owner_subject=? AND status IN ('QUEUED','PROCESSING') AND deleted=false",Integer.class,scope.principalId())>=AttachmentLimits.OWNER_QUEUE) throw new ApiException(429,"attachment_queue_full");
            String id=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
            db.update("INSERT INTO attachments(id,owner_subject,conversation_id,status,stage,filename,mime_type,extension,content_hash,original_bytes) VALUES (?,?,?,'QUEUED','queued',?,?,?,?,?)",id,scope.principalId(),conversation,filename,mime,extension,hash,bytes);
            db.update("INSERT INTO attachment_jobs(id,attachment_id,desired_version) VALUES (?,?,1)",job,id);
            return new Accepted(id,job,"1","QUEUED",false);
        });
    }
    public Stored find(String id,TrustedScope scope,boolean lock){
        return db.query("SELECT * FROM attachments WHERE id=? AND owner_subject=? AND deleted=false"+(lock?" FOR UPDATE":""),STORED,id,scope.principalId()).stream().findFirst().orElseThrow(ApiException::missing);
    }
    public Stored ownedIncludingDeleted(String id,TrustedScope scope){return db.query("SELECT * FROM attachments WHERE id=? AND owner_subject=?",STORED,id,scope.principalId()).stream().findFirst().orElseThrow(ApiException::missing);}
    public Optional<Stored> internal(String id){return db.query("SELECT * FROM attachments WHERE id=? AND deleted=false",STORED,id).stream().findFirst();}
    public List<MatchedRow> rows(String id){return db.query("SELECT payload FROM attachment_rows WHERE attachment_id=? ORDER BY row_order",(r,n)->json.read(r.getString(1),MatchedRow.class),id);}
    public List<Selection> selections(String id){return db.query("SELECT r.payload FROM attachment_reviews r JOIN attachment_rows x ON x.attachment_id=r.attachment_id AND x.row_id=r.row_id WHERE r.attachment_id=? ORDER BY x.row_order",(r,n)->json.read(r.getString(1),Selection.class),id);}
    public List<String> warnings(Stored file){return Arrays.asList(json.read(file.warnings(),String[].class));}
    public void review(String id,TrustedScope scope,long expected,List<Selection> selections){
        tx.executeWithoutResult(s->{
            Stored current=find(id,scope,true);
            if(current.version()!=expected)throw ApiException.conflict("stale_attachment");
            if(!List.of("READY","NEEDS_REVIEW").contains(current.status()))throw ApiException.conflict("attachment_not_ready");
            Set<String> rows=new HashSet<>(rows(id).stream().map(r->r.extracted().id()).toList());
            if(selections.stream().anyMatch(selection->!rows.contains(selection.rowId())))throw ApiException.missing();
            for(var selection:selections) db.update("INSERT INTO attachment_reviews(attachment_id,row_id,payload) VALUES (?,?,?) ON CONFLICT(attachment_id,row_id) DO UPDATE SET payload=EXCLUDED.payload,reviewed_at=now()",id,selection.rowId(),json.write(selection));
            db.update("UPDATE attachments SET revision=revision+1 WHERE id=?",id);
        });
    }
    public void delete(String id,TrustedScope scope){
        tx.executeWithoutResult(s->{
            var found=db.query("SELECT * FROM attachments WHERE id=? AND owner_subject=? FOR UPDATE",STORED,id,scope.principalId());
            if(found.isEmpty())throw ApiException.missing();
            if(found.getFirst().deleted())return;
            // Tombstone is acquired before cleanup. Fenced publishers lock the same row.
            db.update("UPDATE attachments SET deleted=true,revision=revision+1,desired_version=desired_version+1,status='DELETED',original_bytes=NULL,warnings='[]' WHERE id=?",id);
            db.update("UPDATE attachment_jobs SET status='CANCELLED',epoch=epoch+1,lease_until=NULL WHERE attachment_id=?",id);
            db.update("DELETE FROM attachment_reviews WHERE attachment_id=?",id);db.update("DELETE FROM attachment_rows WHERE attachment_id=?",id);
        });
    }
    public Accepted reprocess(String id,TrustedScope scope,long expected){
        return tx.execute(s->{
            db.queryForList("SELECT id FROM attachment_queue_guard WHERE id=1 FOR UPDATE");
            Stored current=find(id,scope,true);
            if(current.version()!=expected)throw ApiException.conflict("stale_attachment");
            if(db.queryForObject("SELECT count(*) FROM attachment_jobs WHERE attachment_id<>? AND status IN ('QUEUED','RUNNING')",Integer.class,id)>=AttachmentLimits.QUEUE || db.queryForObject("SELECT count(*) FROM attachments WHERE id<>? AND owner_subject=? AND status IN ('QUEUED','PROCESSING') AND deleted=false",Integer.class,id,scope.principalId())>=AttachmentLimits.OWNER_QUEUE)throw new ApiException(429,"attachment_queue_full");
            int desired=Math.addExact(current.desiredVersion(),1);String job=UUID.randomUUID().toString();
            db.update("UPDATE attachment_jobs SET status='CANCELLED',epoch=epoch+1,lease_until=NULL WHERE attachment_id=? AND status IN ('QUEUED','RUNNING')",id);
            db.update("UPDATE attachments SET revision=revision+1,desired_version=?,status='QUEUED',stage='queued',error_code=NULL WHERE id=?",desired,id);
            db.update("INSERT INTO attachment_jobs(id,attachment_id,desired_version) VALUES (?,?,?)",job,id,desired);
            return new Accepted(id,job,Long.toString(current.version()+1),"QUEUED",false);
        });
    }
    public Optional<Job> claim(){
        return tx.execute(s->{
            // Lock attachment first everywhere (publish/delete/claim), preventing deadlocks.
            var candidates=db.queryForList("SELECT a.id FROM attachments a JOIN attachment_jobs j ON j.attachment_id=a.id WHERE a.deleted=false AND a.desired_version=j.desired_version AND (j.status='QUEUED' OR (j.status='RUNNING' AND j.lease_until<now())) ORDER BY j.created_at FOR UPDATE OF a SKIP LOCKED LIMIT 1");
            if(candidates.isEmpty())return Optional.empty();
            String id=candidates.getFirst().get("id").toString();
            var r=db.queryForList("SELECT * FROM attachment_jobs WHERE attachment_id=? AND status IN ('QUEUED','RUNNING') ORDER BY created_at DESC FOR UPDATE LIMIT 1",id).getFirst();
            String job=r.get("id").toString();int attempts=((Number)r.get("attempts")).intValue()+1;long epoch=((Number)r.get("epoch")).longValue()+1;
            if(attempts>AttachmentLimits.ATTEMPTS){db.update("UPDATE attachment_jobs SET status='FAILED',epoch=? WHERE id=?",epoch,job);db.update("UPDATE attachments SET status='FAILED',stage='failed',error_code='RETRY_EXHAUSTED' WHERE id=?",id);return Optional.empty();}
            db.update("UPDATE attachment_jobs SET status='RUNNING',epoch=?,attempts=?,lease_until=? WHERE id=?",epoch,attempts,Timestamp.from(Instant.now().plusSeconds(AttachmentLimits.LEASE_SECONDS)),job);
            db.update("UPDATE attachments SET status='PROCESSING',stage='extraction' WHERE id=?",id);
            return Optional.of(new Job(job,id,((Number)r.get("desired_version")).intValue(),epoch,attempts));
        });
    }
    public boolean publish(Job job,List<MatchedRow> rows,List<String> warnings,String error){
        return Boolean.TRUE.equals(tx.execute(s->{
            var current=db.query("SELECT * FROM attachments WHERE id=? FOR UPDATE",STORED,job.attachmentId());
            if(current.isEmpty()||current.getFirst().deleted()||current.getFirst().desiredVersion()!=job.version())return false;
            int updated=db.update("UPDATE attachment_jobs SET status=?,lease_until=NULL WHERE id=? AND epoch=? AND status='RUNNING' AND lease_until>now()",error==null?"COMPLETED":"FAILED",job.id(),job.epoch());
            if(updated==0)return false;
            // Retain reviewed original evidence. OCR changes must never bind an old selection
            // to a different row merely because its ordinal stayed the same.
            db.update("DELETE FROM attachment_rows r WHERE r.attachment_id=? AND NOT EXISTS (SELECT 1 FROM attachment_reviews v WHERE v.attachment_id=r.attachment_id AND v.row_id=r.row_id)",job.attachmentId());
            for(int i=0;i<rows.size();i++)db.update("INSERT INTO attachment_rows(attachment_id,row_id,extraction_version,row_order,payload) VALUES (?,?,?,?,?) ON CONFLICT(attachment_id,row_id) DO UPDATE SET extraction_version=EXCLUDED.extraction_version,row_order=EXCLUDED.row_order,payload=EXCLUDED.payload WHERE NOT EXISTS (SELECT 1 FROM attachment_reviews v WHERE v.attachment_id=attachment_rows.attachment_id AND v.row_id=attachment_rows.row_id)",job.attachmentId(),rows.get(i).extracted().id(),job.version(),i,json.write(rows.get(i)));
            List<String> publishedWarnings=new ArrayList<>(warnings);if(db.queryForObject("SELECT count(*) FROM attachment_reviews WHERE attachment_id=?",Integer.class,job.attachmentId())>0)publishedWarnings.add("REVIEWED_ROWS_PRESERVED");
            boolean needsReview=!publishedWarnings.isEmpty()||rows.isEmpty()||rows.stream().anyMatch(r->!r.status().equals("matched"));
            db.update("UPDATE attachments SET status=?,stage=?,warnings=?,error_code=?,revision=revision+1 WHERE id=?",error!=null?"FAILED":needsReview?"NEEDS_REVIEW":"READY",error!=null?"failed":"review",json.write(publishedWarnings),error,job.attachmentId());
            return true;
        }));
    }
    private static String hex(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
