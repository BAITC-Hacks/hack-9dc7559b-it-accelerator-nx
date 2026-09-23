package com.hackalem.ai.attachments;

import com.hackalem.domain.attachments.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import static com.hackalem.domain.attachments.AttachmentModels.*;

/** Conservative table parser: numeric price columns never become quantities. */
public class DocumentRows {
    private static final Pattern NUMBER=Pattern.compile("[0-9]+(?:[,.][0-9]{1,6})?");
    private final List<ExtractedRow> rows=new ArrayList<>();
    private int article=-1,quantity=-1,unit=-1; private int chars;
    private final Map<String,Integer> occurrences=new HashMap<>();
    public void visual(VisualEvidence evidence) {
        for(int i=0;i<rows.size();i++){var r=rows.get(i);rows.set(i,new ExtractedRow(r.id(),r.rawText(),r.article(),r.quantity(),r.unit(),r.source(),r.warnings(),evidence));}
    }
    public void resetHeader() { article=-1;quantity=-1;unit=-1; }
    public List<ExtractedRow> rows() {return List.copyOf(rows);}
    public void add(List<String> cells,Location source,boolean formula) {
        if(cells.stream().allMatch(String::isBlank)) return;
        String raw=String.join(" | ",cells).strip();
        chars+=raw.length();
        if(chars>AttachmentLimits.TEXT || cells.stream().anyMatch(s->s.length()>AttachmentLimits.CELL)) throw AttachmentException.invalid("TEXT_LIMIT");
        List<String> lower=cells.stream().map(s->s.strip().toLowerCase(Locale.ROOT).replaceAll("[.:]$", "")).toList();
        int a=index(lower,List.of("артикул","sku","article","код","код товара"));
        int q=index(lower,List.of("количество","кол-во","кол во","qty","quantity"));
        int u=index(lower,List.of("ед","ед. изм","единица","единица измерения","unit","units"));
        if(a>=0||q>=0) {article=a;quantity=q;unit=u;return;}
        if(lower.stream().anyMatch(s->s.matches("^(итого|всего|total|subtotal)(\\s.*)?$"))) return;
        List<String> warnings=new ArrayList<>();
        String sku=value(cells,article),qty=value(cells,quantity),units=value(cells,unit);
        if(article<0) {
            warnings.add("NO_RELIABLE_HEADER");
            // A labelled article in paragraphs is evidence; unlabelled numbers are not.
            var match=Pattern.compile("(?iu)(?:артикул|sku|article)\\s*[:=]\\s*([\\p{L}0-9_.-]+)").matcher(raw);
            if(match.find()) sku=match.group(1);
            if(sku==null&&cells.size()>=2&&cells.getFirst().strip().matches("(?:[0-9]{4,}|[A-Za-z][A-Za-z0-9_.-]*[0-9][A-Za-z0-9_.-]*)")){
                sku=cells.getFirst().strip();warnings.add("ARTICLE_COLUMN_ASSUMED");
            }
            var count=Pattern.compile("(?iu)(?:количество|кол-во|qty|quantity)\\s*[:=]\\s*([0-9]+(?:[,.][0-9]{1,6})?)").matcher(raw);
            if(count.find()) qty=count.group(1);
            var measure=Pattern.compile("(?iu)(?:единица|unit)\\s*[:=]\\s*([\\p{L}.]+)").matcher(raw);
            if(measure.find()) units=measure.group(1);
        }
        if(qty!=null) {
            String numeric=qty.replace("\u00a0", "").replace(" ", "");
            if(NUMBER.matcher(numeric).matches()&&new BigDecimal(numeric.replace(',','.')).signum()>0) qty=new BigDecimal(numeric.replace(',','.')).stripTrailingZeros().toPlainString();
            else {qty=null;warnings.add("INVALID_QUANTITY");}
        }
        if(qty==null) warnings.add("QUANTITY_REQUIRED");
        if(units==null) warnings.add("UNIT_REQUIRED");
        if(sku==null) warnings.add("ARTICLE_UNCERTAIN");
        if(formula) warnings.add("CACHED_FORMULA_REQUIRES_REVIEW");
        if(rows.size()>=AttachmentLimits.ROWS) throw AttachmentException.invalid("ROW_LIMIT");
        String identity=source.kind()+"|"+source.sheet()+"|"+source.row()+"|"+source.cell()+"|"+source.page()+"|"+source.paragraph()+"|"+raw;
        String key=com.hackalem.domain.Json.hash(identity).substring(0,24);int occurrence=occurrences.merge(key,1,Integer::sum);
        rows.add(new ExtractedRow("row-"+key+"-"+occurrence,raw,sku,qty,units,source,List.copyOf(warnings)));
    }
    public void text(String text,Location location) {
        Map<String,List<WordBox>> groups=new LinkedHashMap<>();
        if(location.words()!=null)for(WordBox word:location.words())groups.computeIfAbsent(word.lineId(),ignored->new ArrayList<>()).add(word);
        for(String line:text.split("\\R")) {
            if(!line.isBlank()) {
                String normalized=line.replaceAll("\\s","");String match=null;
                for(var group:groups.entrySet())if(group.getValue().stream().map(WordBox::text).collect(java.util.stream.Collectors.joining()).equals(normalized)){match=group.getKey();break;}
                // Each OCR word belongs to at most one row, bounding provenance size.
                List<WordBox> boxes=match==null?List.of():List.copyOf(groups.remove(match));
                Location source=new Location(location.kind(),location.sheet(),location.row(),location.cell(),location.page(),location.paragraph(),boxes);
                add(Arrays.stream(line.split("\\t|\\s{2,}|\\|",-1)).map(String::strip).toList(),source,false);
            }
        }
    }
    private static String value(List<String> row,int i) {return i<0||i>=row.size()||row.get(i).isBlank()?null:row.get(i).strip();}
    private static int index(List<String> cells,List<String> names) {for(int i=0;i<cells.size();i++) if(names.contains(cells.get(i))) return i;return -1;}
}
