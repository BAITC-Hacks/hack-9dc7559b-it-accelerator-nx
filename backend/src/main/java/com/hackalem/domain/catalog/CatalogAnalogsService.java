package com.hackalem.domain.catalog;

import com.hackalem.domain.port.Contracts.*;
import com.hackalem.web.ApiException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service
public class CatalogAnalogsService {
    private final CatalogRepository catalog;private final CatalogOfferService offers;private final CatalogReadSnapshots snapshots;
    public CatalogAnalogsService(CatalogRepository catalog,CatalogOfferService offers,CatalogReadSnapshots snapshots){this.catalog=catalog;this.offers=offers;this.snapshots=snapshots;}
    public record Analog(String article,String name,CompatibilityRules.Verdict compatibility,List<CatalogOfferService.Quote> offers){}
    public record Option(String id,String kind,List<Selection> lines,List<String> differences,List<OfferSnapshot> offers,String version){
        public Option {lines=List.copyOf(lines);differences=List.copyOf(differences);offers=List.copyOf(offers);}
        public AlternativePlan shared(){return new AlternativePlan(id,kind,lines,differences);}
    }
    public record Result(String originalArticle,String status,String ruleVersion,List<Analog> analogs,List<Option> options,List<String> reasons){}
    public Result find(String article,BigDecimal requested,String unit,SearchCriteria constraints,TrustedScope scope){
        CatalogOfferService.requireScope(scope);
        var original=catalog.findActiveByArticle(article).orElseThrow(ApiException::missing);
        CatalogOfferService.validateQuantity(original,requested,unit);
        var self=CompatibilityRules.check(original,original);
        if(!self.status().equals("VERIFIED"))return new Result(article,"NEEDS_SPECIFICATION",CompatibilityRules.VERSION,List.of(),List.of(),self.missingRequirements());
        var sourceOffers=offers.quotes(original).stream().map(CatalogOfferService.Quote::offer).filter(Objects::nonNull)
                .filter(o->new BigDecimal(o.available().value()).signum()>0).sorted(Comparator.comparing((OfferSnapshot o)->new BigDecimal(o.available().value())).reversed()).toList();
        var candidates=catalog.candidates(original.catalogVersionId(),original.category(),null,2000).stream().filter(p->p.id()!=original.id())
                .filter(p->CompatibilityRules.check(original,p).status().equals("VERIFIED"))
                .map(offers::hydrate).filter(constraints::matches)
                .sorted(Comparator.comparing((CatalogProduct p)->p.offer()==null?new BigDecimal("1E30"):p.offer().price()).thenComparingLong(CatalogProduct::id)).toList();
        List<Analog> analogs=new ArrayList<>();List<Option> options=new ArrayList<>();
        for(var candidate:candidates){
            var quotes=offers.quotes(candidate);
            var usable=quotes.stream().map(CatalogOfferService.Quote::offer).filter(Objects::nonNull).filter(o->new BigDecimal(o.available().value()).signum()>0).toList();
            if(usable.isEmpty())continue;
            var verdict=CompatibilityRules.check(original,candidate);
            analogs.add(new Analog(candidate.article(),candidate.name(),verdict,quotes));
            List<String> reasons=new ArrayList<>();reasons.add("Проверены синтетические правила "+CompatibilityRules.VERSION+": "+verdict.matchedRequirements());
            verdict.differences().forEach((k,v)->reasons.add(k+": "+v));
            for(var alternate:usable){
                if(fits(candidate,requested,alternate))add(options,new Option(UUID.randomUUID().toString(),"FULL_ALTERNATIVE",
                        List.of(line(alternate,requested)),reasons,List.of(alternate),String.valueOf(original.catalogVersionId())),scope);
                for(var own:sourceOffers){
                    BigDecimal first=new BigDecimal(own.available().value()).min(requested),deficit=requested.subtract(first);
                    if(deficit.signum()<=0||!fits(original,first,own)||!fits(candidate,deficit,alternate))continue;
                    add(options,new Option(UUID.randomUUID().toString(),"PARTIAL_REPLACEMENT",List.of(line(own,first),line(alternate,deficit)),reasons,List.of(own,alternate),String.valueOf(original.catalogVersionId())),scope);
                    break;
                }
                if(options.size()>=10)break;
            }
            if(options.size()>=10||analogs.size()>=20)break;
        }
        return new Result(original.article(),options.isEmpty()?"NO_FULFILLMENT_OPTION":"VERIFIED",CompatibilityRules.VERSION,List.copyOf(analogs),List.copyOf(options),
                options.isEmpty()?List.of("Совместимый товар с подтверждённым количеством и шагом партии не найден"):List.of("Выбор варианта не добавляет товары в корзину; требуется отдельное подтверждение"));
    }
    private void add(List<Option> options,Option option,TrustedScope scope){if(options.size()<10){snapshots.save(UUID.fromString(option.id()),"OPTION",option,scope);options.add(option);}}
    public Option option(UUID id,TrustedScope scope){return snapshots.get(id,"OPTION",scope,Option.class);}
    private static Selection line(OfferSnapshot offer,BigDecimal quantity){return new Selection(offer.article(),offer.available().unit(),offer.warehouse(),Decimals.quantity(quantity));}
    private static boolean fits(CatalogProduct p,BigDecimal quantity,OfferSnapshot offer){
        return quantity.signum()>0&&quantity.compareTo(p.minimumQuantity())>=0&&quantity.remainder(p.stepQuantity()).signum()==0
                &&new BigDecimal(offer.available().value()).compareTo(quantity)>=0;
    }
}
