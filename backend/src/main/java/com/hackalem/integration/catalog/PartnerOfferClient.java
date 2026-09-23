package com.hackalem.integration.catalog;

import com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.catalog.CatalogOfferService;
import com.hackalem.web.ApiException;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Unregistered scaffold: partner contract must be verified before a bean is wired by configuration. */
public final class PartnerOfferClient {
    private final RestClient client;
    public PartnerOfferClient(URI origin,String token,boolean contractVerified){
        if(!contractVerified||origin==null||!"https".equals(origin.getScheme())||origin.getUserInfo()!=null||origin.getHost()==null)
            throw new IllegalArgumentException("Verified HTTPS partner origin required");
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(3));
        client=RestClient.builder().baseUrl(origin.toString()).requestFactory(factory).defaultHeader("Authorization","Bearer "+token).build();
    }
    public record Request(List<Selection> items,String priceContext){}
    public record Response(List<OfferSnapshot> offers){}
    public List<OfferSnapshot> getOffers(List<Selection> items,TrustedScope scope){
        CatalogOfferService.requireScope(scope);
        if(items==null||items.isEmpty()||items.size()>50)throw new ApiException(400,"invalid_offer_batch");
        try {
            var response=client.post().uri("/offers/quote").body(new Request(items,scope.principalId().toString())).retrieve().body(Response.class);
            if(response==null||response.offers()==null||response.offers().size()!=items.size())throw new IllegalStateException("Incomplete quote");
            for(int i=0;i<items.size();i++){
                var o=response.offers().get(i);var input=items.get(i);
                if(o==null||o.available()==null||o.price()==null||o.version()==null||o.stockBucket()==null||o.observedAt()==null||o.expiresAt()==null
                    ||o.expiresAt().isBefore(Instant.now())||!input.article().equals(o.article())||!input.unit().equals(o.available().unit())||!input.warehouse().equals(o.warehouse())
                    ||new java.math.BigDecimal(o.available().value()).signum()<0||new java.math.BigDecimal(o.price().amount()).signum()<0)throw new IllegalStateException("Invalid authoritative quote");
            }
            return List.copyOf(response.offers());
        }catch(RuntimeException e){throw ApiException.unavailable("partner_stock_source_unavailable");}
    }
}
