package com.hackalem.integration.catalog;

import com.hackalem.domain.catalog.*;
import com.hackalem.domain.port.*;
import com.hackalem.domain.port.Contracts.*;
import com.hackalem.web.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import java.math.BigDecimal;
import java.util.*;

@Component
@Profile("!contract & !test")
public class CatalogDataAdapter implements CatalogPort,StockPort,AnalogsPort {
    private final CatalogRepository repository;private final CatalogSearchService search;private final CatalogOfferService offers;
    private final CatalogAnalogsService analogs;private final CatalogReadSnapshots snapshots;
    public CatalogDataAdapter(CatalogRepository repository,CatalogSearchService search,CatalogOfferService offers,CatalogAnalogsService analogs,CatalogReadSnapshots snapshots){
        this.repository=repository;this.search=search;this.offers=offers;this.analogs=analogs;this.snapshots=snapshots;
    }
    public record SavedSearch(ProductResultSet resultSet,SearchCriteria appliedConstraints,String mode,List<String> warnings){}
    @Override public ProductResultSet search(SearchQuery query,TrustedScope scope){return searchSnapshot(criteria(query),scope).resultSet();}
    public SavedSearch searchSnapshot(SearchCriteria criteria,TrustedScope scope){
        CatalogOfferService.requireScope(scope);var found=search.search(criteria);UUID id=UUID.randomUUID();
        var quoteList=found.products().stream().flatMap(p->offers.quotes(p).stream()).map(CatalogOfferService.Quote::offer).filter(Objects::nonNull).toList();
        var result=new ProductResultSet(id.toString(),String.valueOf(found.version().id()),found.products().stream().map(CatalogDataAdapter::details).toList(),quoteList);
        var saved=new SavedSearch(result,criteria,found.mode(),found.warnings());snapshots.save(id,"SEARCH",saved,scope);return saved;
    }
    public SavedSearch saved(UUID id,TrustedScope scope){return snapshots.get(id,"SEARCH",scope,SavedSearch.class);}
    public SavedSearch compare(UUID id,List<Integer> indices,TrustedScope scope){
        var saved=saved(id,scope);
        if(indices==null||indices.isEmpty()||indices.size()>10||new HashSet<>(indices).size()!=indices.size()
                ||indices.stream().anyMatch(i->i==null||i<0||i>=saved.resultSet().products().size()))throw new ApiException(400,"invalid_comparison_indices");
        var products=indices.stream().map(i->saved.resultSet().products().get(i)).toList();
        var quotes=saved.resultSet().offers().stream().filter(o->products.stream().anyMatch(p->p.article().equals(o.article()))).toList();
        return new SavedSearch(new ProductResultSet(saved.resultSet().id(),saved.resultSet().version(),products,quotes),saved.appliedConstraints(),saved.mode(),saved.warnings());
    }
    @Override public ProductDetails getProduct(String article,TrustedScope scope){CatalogOfferService.requireScope(scope);return details(repository.findActiveByArticle(article).orElseThrow(ApiException::missing));}
    @Override public List<OfferSnapshot> getOffers(List<Selection> items,TrustedScope scope){return offers.getOffers(items,scope);}
    @Override public List<AlternativePlan> find(String article,SearchQuery query,TrustedScope scope){
        var p=repository.findActiveByArticle(article).orElseThrow(ApiException::missing);
        var quantity=query.quantity()==null?p.minimumQuantity():CatalogOfferService.quantity(query.quantity().value());
        return analogs.find(article,quantity,query.quantity()==null?p.unit():query.quantity().unit(),criteria(query),scope).options().stream().map(CatalogAnalogsService.Option::shared).toList();
    }
    public static SearchCriteria criteria(SearchQuery query){
        BigDecimal max=null;
        if(query.maxPrice()!=null){try{max=new BigDecimal(query.maxPrice().amount());}catch(Exception e){throw new ApiException(400,"invalid_price_range");}}
        return new SearchCriteria(query.query(),20,query.category(),null,null,max,query.maxPrice()==null?null:query.maxPrice().currency(),query.hardConstraints(),false);
    }
    public static ProductDetails details(CatalogProduct p){return new ProductDetails(String.valueOf(p.id()),p.article(),p.name(),Map.copyOf(p.specs()),
            p.certificates().stream().map(c->new SourceRef(p.synthetic()&&Boolean.TRUE.equals(c.synthetic())?CatalogCertificates.url(p,c):c.id(),c.version(),"Сертификат "+c.id(),null,null,null)).toList());}
}
