package com.hackalem.web.catalog;

import com.hackalem.domain.catalog.*;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.integration.catalog.CatalogDataAdapter;
import com.hackalem.web.ApiException;
import com.hackalem.security.TrustedScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@Profile("!contract & !test")
public class CatalogReadController {
    private final CatalogDataAdapter adapter;private final CatalogRepository repository;private final CatalogOfferService offers;
    private final CatalogAnalogsService analogs;private final TrustedScopeResolver admin;private final CatalogCertificates certificates;
    public CatalogReadController(CatalogDataAdapter adapter,CatalogRepository repository,CatalogOfferService offers,CatalogAnalogsService analogs,TrustedScopeResolver admin,CatalogCertificates certificates){this.adapter=adapter;this.repository=repository;this.offers=offers;this.analogs=analogs;this.admin=admin;this.certificates=certificates;}
    @PostMapping("/api/catalog/search")
    public CatalogDataAdapter.SavedSearch search(@RequestBody SearchCriteria criteria,@AuthenticationPrincipal TrustedScope scope){return adapter.searchSnapshot(criteria,scope);}
    @GetMapping("/api/catalog/search")
    public CatalogDataAdapter.SavedSearch search(@RequestParam String q,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String category,@RequestParam(required=false) String brand,
            @RequestParam(required=false) BigDecimal minPrice,@RequestParam(required=false) BigDecimal maxPrice,
            @RequestParam(defaultValue="false") boolean exactArticle,@AuthenticationPrincipal TrustedScope scope){
        return adapter.searchSnapshot(new SearchCriteria(q,limit,category,brand,minPrice,maxPrice,null,Map.of(),exactArticle),scope);
    }
    @GetMapping("/api/catalog/results/{id}")
    public CatalogDataAdapter.SavedSearch result(@PathVariable UUID id,@AuthenticationPrincipal TrustedScope scope){return adapter.saved(id,scope);}
    public record CompareRequest(List<Integer> indices){}
    @PostMapping("/api/catalog/results/{id}/compare")
    public CatalogDataAdapter.SavedSearch compare(@PathVariable UUID id,@RequestBody CompareRequest request,@AuthenticationPrincipal TrustedScope scope){return adapter.compare(id,request.indices(),scope);}
    @GetMapping(value="/api/products/{article}/certificates/{certificateId}",produces="application/pdf")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> certificate(@PathVariable String article,@PathVariable String certificateId,@AuthenticationPrincipal TrustedScope scope){
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","inline; filename=synthetic-certificate.pdf")
                .header("X-Content-Type-Options","nosniff").body(certificates.read(article,certificateId,scope));
    }
    @GetMapping("/api/products/{article}/offers")
    public List<CatalogOfferService.Quote> quotes(@PathVariable String article){return offers.quotes(repository.findActiveByArticle(article).orElseThrow(ApiException::missing));}
    @GetMapping("/api/products/{article}/analogs")
    public CatalogAnalogsService.Result analogs(@PathVariable String article,@RequestParam String quantity,@RequestParam(defaultValue="pcs") String unit,@AuthenticationPrincipal TrustedScope scope){
        return analogs.find(article,CatalogOfferService.quantity(quantity),unit,new SearchCriteria("analogs",20,null,null,null,null,null,Map.of(),false),scope);
    }
    @GetMapping("/api/catalog/options/{id}")
    public CatalogAnalogsService.Option option(@PathVariable UUID id,@AuthenticationPrincipal TrustedScope scope){return analogs.option(id,scope);}
    public record UpdateOffer(String price,String available,String expectedVersion){}
    @PatchMapping("/api/admin/catalog/offers/{article}/{warehouse}")
    public List<CatalogOfferService.Quote> update(@PathVariable String article,@PathVariable String warehouse,@RequestBody UpdateOffer update,HttpServletRequest request){
        admin.requireAdmin(request);
        try { offers.updateSample(article,warehouse,new BigDecimal(update.price()),new BigDecimal(update.available()),update.expectedVersion()); }
        catch(NumberFormatException|NullPointerException e){throw new ApiException(400,"invalid_sample_offer");}
        return quotes(article);
    }
}
