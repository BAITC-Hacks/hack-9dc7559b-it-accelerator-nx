package com.hackalem.domain.catalog;

import com.hackalem.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Deliberately typed allowlist, never SQL or arbitrary metadata predicates. */
public record SearchCriteria(String query, int limit, String category, String brand,
                             @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) @io.swagger.v3.oas.annotations.media.Schema(type="string") BigDecimal minPrice,
                             @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) @io.swagger.v3.oas.annotations.media.Schema(type="string") BigDecimal maxPrice, String currency,
                             Map<String,String> specs, boolean exactArticle) {
    public static final Set<String> SPEC_KEYS=Set.of("poles","currentA","voltageV","curve","breakingCapacityKa",
            "cores","crossSectionMm2","material","insulation","base","powerW","colorTemperatureK");
    public SearchCriteria {
        if(query==null || query.isBlank() || query.length()>500 || query.strip().split("\\s+").length>64
                || limit<1 || limit>50 || (category!=null&&category.length()>255) || (brand!=null&&brand.length()>255))
            throw new ApiException(400,"invalid_catalog_search");
        if(minPrice!=null&&minPrice.signum()<0 || maxPrice!=null&&maxPrice.signum()<0
                || minPrice!=null&&maxPrice!=null&&minPrice.compareTo(maxPrice)>0)
            throw new ApiException(400,"invalid_price_range");
        if(currency!=null&&!currency.matches("[A-Z]{3}"))throw new ApiException(400,"invalid_currency");
        specs=specs==null?Map.of():specs;
        if(specs.size()>16 || specs.entrySet().stream().anyMatch(e->!SPEC_KEYS.contains(e.getKey()) || e.getValue()==null
                || e.getValue().isBlank() || e.getValue().length()>100))throw new ApiException(400,"invalid_catalog_constraints");
        specs=Map.copyOf(specs);
        query=query.strip();
    }
    public boolean matches(CatalogProduct p) {
        if(category!=null&&!category.isBlank()&&!category.equals(p.category()))return false;
        if(brand!=null&&!brand.isBlank()&&!brand.equals(p.brand()))return false;
        if(!specs.entrySet().stream().allMatch(e->same(e.getValue(),p.specs().get(e.getKey()))))return false;
        if(minPrice!=null || maxPrice!=null || currency!=null) {
            if(p.offer()==null||!p.offer().known())return false;
            if(currency!=null&&!currency.equals(p.offer().currency()))return false;
            if(minPrice!=null&&p.offer().price().compareTo(minPrice)<0 || maxPrice!=null&&p.offer().price().compareTo(maxPrice)>0)return false;
        }
        return true;
    }
    public static boolean same(String a,String b) {
        if(a==null||b==null)return false;
        try { return new BigDecimal(a).compareTo(new BigDecimal(b))==0; }
        catch(NumberFormatException e) { return a.strip().equalsIgnoreCase(b.strip()); }
    }
}
