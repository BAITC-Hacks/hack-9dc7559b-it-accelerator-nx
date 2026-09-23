package com.hackalem.domain.attachments;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.hackalem.domain.attachments.AttachmentModels.*;
@Component
public class AttachmentMatcher {
    private final ObjectProvider<AttachmentCatalog> catalogs;
    public AttachmentMatcher(ObjectProvider<AttachmentCatalog> catalogs) {this.catalogs=catalogs;}
    public List<MatchedRow> match(String owner,Extraction extraction) {
        var catalog=catalogs.getIfAvailable();
        List<MatchedRow> result=new ArrayList<>();
        for(var row:extraction.rows()) {
            List<Product> products=catalog==null?List.of():catalog.search(owner,row.article()==null?row.rawText():row.article());
            List<Product> exact=products.stream().filter(p->p.article().equalsIgnoreCase(Objects.toString(row.article(),""))).toList();
            List<Product> chosen=exact.isEmpty()?products:exact;
            List<Candidate> candidates=chosen.stream().limit(5).map(p->new Candidate(p.id(),p.article(),p.name(),p.unit(),exact.isEmpty()?"catalog_search_candidate":"exact_article",p.minimum().toPlainString(),p.step().toPlainString(),p.warehouses())).toList();
            boolean conflict=exact.size()==1&&hardConflict(row,exact.getFirst());
            String status=candidates.isEmpty()?"unmatched":exact.size()==1?"matched":"ambiguous";
            if(row.quantity()==null) status="needs_quantity";
            else if(!row.warnings().isEmpty()||!extraction.warnings().isEmpty()) status="needs_review";
            if(exact.size()==1) {
                Product product=exact.getFirst();
                if(row.unit()!=null&&!row.unit().equals(product.unit()))status="needs_review";
                if(row.quantity()!=null){var quantity=new java.math.BigDecimal(row.quantity());if(quantity.compareTo(product.minimum())<0||quantity.remainder(product.step()).signum()!=0)status="needs_review";}
            }
            if(conflict){status="needs_review";candidates=candidates.stream().map(c->new Candidate(c.productId(),c.article(),c.name(),c.unit(),"hard_attribute_conflict",c.minimum(),c.step(),c.warehouses())).toList();}
            result.add(new MatchedRow(row,status,candidates));
        }
        return List.copyOf(result);
    }
    private static boolean hardConflict(ExtractedRow row,Product product){
        var marking=java.util.regex.Pattern.compile("(?iu)\\b([BCDС])([0-9]+(?:[.,][0-9]+)?)\\b").matcher(row.rawText());
        if(marking.find()){
            String curve=marking.group(1).toUpperCase(Locale.ROOT).replace('С','C');
            if(product.attributes().containsKey("curve")&&!curve.equalsIgnoreCase(product.attributes().get("curve")))return true;
            if(product.attributes().containsKey("currentA"))try{if(new java.math.BigDecimal(marking.group(2).replace(',','.')).compareTo(new java.math.BigDecimal(product.attributes().get("currentA")))!=0)return true;}catch(NumberFormatException ignored){return true;}
        }
        var poles=java.util.regex.Pattern.compile("(?iu)\\b([1-4])P\\b").matcher(row.rawText());
        return poles.find()&&product.attributes().containsKey("poles")&&!poles.group(1).equals(product.attributes().get("poles"));
    }

}
