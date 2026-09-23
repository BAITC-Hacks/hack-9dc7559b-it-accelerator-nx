package com.hackalem.domain.catalog;

import java.util.*;

/** Reviewed DATA-01 synthetic rules only. Real equipment requires partner-reviewed rules. */
public final class CompatibilityRules {
    public static final String VERSION="synthetic-compatibility-v1";
    private static final Map<String,List<String>> REQUIRED=Map.of(
            "breakers",List.of("poles","currentA","voltageV","curve","breakingCapacityKa"),
            "cables",List.of("cores","crossSectionMm2","material","voltageV","insulation"),
            "lamps",List.of("voltageV","base","powerW","colorTemperatureK"));
    private CompatibilityRules(){}
    public record Verdict(String status,Map<String,String> matchedRequirements,Map<String,String> differences,List<String> missingRequirements){}
    public static Verdict check(CatalogProduct original,CatalogProduct candidate){
        if(!original.synthetic()||!candidate.synthetic())return new Verdict("NEEDS_SPECIFICATION",Map.of(),Map.of(),List.of("partner-reviewed compatibility rules"));
        var keys=REQUIRED.get(original.category());
        if(keys==null)return new Verdict("NEEDS_SPECIFICATION",Map.of(),Map.of(),List.of("category compatibility rules"));
        if(!original.category().equals(candidate.category())||!original.unit().equals(candidate.unit()))return new Verdict("INCOMPATIBLE",Map.of(),Map.of("category/unit","different"),List.of());
        List<String> missing=new ArrayList<>();Map<String,String> matched=new LinkedHashMap<>(),diff=new LinkedHashMap<>();
        for(String key:keys){String left=original.specs().get(key),right=candidate.specs().get(key);
            if(left==null||left.isBlank()||right==null||right.isBlank())missing.add(key);
            else if(SearchCriteria.same(left,right))matched.put(key,left);
            else diff.put(key,left+" → "+right);
        }
        if(!missing.isEmpty())return new Verdict("NEEDS_SPECIFICATION",Map.copyOf(matched),Map.copyOf(diff),List.copyOf(missing));
        if(!diff.isEmpty())return new Verdict("INCOMPATIBLE",Map.copyOf(matched),Map.copyOf(diff),List.of());
        if(!Objects.equals(original.brand(),candidate.brand()))diff.put("brand",original.brand()+" → "+candidate.brand());
        return new Verdict("VERIFIED",Map.copyOf(matched),Map.copyOf(diff),List.of());
    }
}
