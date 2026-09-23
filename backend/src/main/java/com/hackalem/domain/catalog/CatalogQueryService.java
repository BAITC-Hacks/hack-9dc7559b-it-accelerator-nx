package com.hackalem.domain.catalog;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class CatalogQueryService {
    private final CatalogRepository catalog;private final CatalogSearchService search;private final CatalogOfferService offers;
    public CatalogQueryService(CatalogRepository catalog,CatalogSearchService search,CatalogOfferService offers){this.catalog=catalog;this.search=search;this.offers=offers;}
    public CatalogProduct findByArticle(String article){return offers.hydrate(catalog.findActiveByArticle(article).orElseThrow(()->new ProductNotFoundException(article)));}
    public SearchResult search(String query,int limit,String category,String brand,BigDecimal minPrice,BigDecimal maxPrice){
        var result=search.search(new SearchCriteria(query,limit,category,brand,minPrice,maxPrice,null,Map.of(),false));
        return new SearchResult(result.products(),result.version(),result.mode(),result.warnings());
    }
    public CatalogVersion activeVersion(){return catalog.findActiveVersion().orElseThrow(()->new CatalogIndexNotReadyException("CATALOG_NOT_INITIALIZED","Каталог ещё не загружен"));}
    public record SearchResult(List<CatalogProduct> items,CatalogVersion version,String mode,List<String> warnings){}
}
