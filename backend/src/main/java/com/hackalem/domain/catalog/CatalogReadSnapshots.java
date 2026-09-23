package com.hackalem.domain.catalog;

import com.hackalem.domain.Json;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class CatalogReadSnapshots {
    private final JdbcTemplate db;private final Json json;
    public CatalogReadSnapshots(JdbcTemplate db,Json json){this.db=db;this.json=json;}
    public void save(UUID id,String kind,Object payload,TrustedScope scope){
        CatalogOfferService.requireScope(scope);
        db.update("INSERT INTO catalog_read_snapshots(id,owner_id,kind,payload) VALUES (?,?,?,?::jsonb)",id,scope.principalId(),kind,json.write(payload));
    }
    public <T>T get(UUID id,String kind,TrustedScope scope,Class<T> type){
        CatalogOfferService.requireScope(scope);
        return db.query("SELECT payload::text FROM catalog_read_snapshots WHERE id=? AND owner_id=? AND kind=? AND expires_at>now()",
                (r,n)->json.read(r.getString(1),type),id,scope.principalId(),kind).stream().findFirst().orElseThrow(ApiException::missing);
    }
}
