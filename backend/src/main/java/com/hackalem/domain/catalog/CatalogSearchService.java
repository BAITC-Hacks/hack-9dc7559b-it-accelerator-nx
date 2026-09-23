package com.hackalem.domain.catalog;

import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CatalogSearchService {
    private final CatalogRepository repository;
    private final CatalogEmbeddingProvider embeddings;
    private final CatalogOfferService offers;
    private final com.hackalem.ai.catalog.BoundedCatalogQueryEmbedding queryEmbedding;
    public CatalogSearchService(CatalogRepository repository,CatalogEmbeddingProvider embeddings,CatalogOfferService offers,com.hackalem.ai.catalog.BoundedCatalogQueryEmbedding queryEmbedding) {
        this.repository=repository;this.embeddings=embeddings;this.offers=offers;this.queryEmbedding=queryEmbedding;
    }
    public record Outcome(List<CatalogProduct> products,CatalogVersion version,String mode,List<String> warnings) {
        public Outcome { products=List.copyOf(products);warnings=List.copyOf(warnings); }
    }
    public Outcome search(SearchCriteria criteria) {
        var version=repository.findActiveVersion().orElseThrow(()->new CatalogIndexNotReadyException("CATALOG_NOT_INITIALIZED","Каталог ещё не загружен"));
        var exact=repository.findByArticle(version.id(),criteria.query());
        if(exact.isPresent()) {
            var p=offers.hydrate(exact.get());
            return new Outcome(criteria.matches(p)?List.of(p):List.of(),version,"EXACT",List.of());
        }
        // An explicit SKU or an ASCII code containing a digit must never silently become another item.
        if(criteria.exactArticle() || criteria.query().matches("(?=.*[0-9._/\\-])[A-Za-z0-9._/\\-]+"))
            return new Outcome(List.of(),version,"NOT_FOUND",List.of("Точный артикул не найден; замена не выбрана"));
        var candidates=repository.candidates(version.id(),criteria.category(),criteria.brand(),2000);
        List<String> warnings=new ArrayList<>();
        if(candidates.size()==2000)warnings.add("Лексический поиск ограничен 2000 кандидатами; уточните категорию");
        var ranked=candidates.stream().filter(p->criteria.specs().entrySet().stream().allMatch(e->SearchCriteria.same(e.getValue(),p.specs().get(e.getKey()))))
                .map(p->Map.entry(p,lexical(criteria.query(),p)))
                .filter(e->e.getValue()>0).sorted(Comparator.<Map.Entry<CatalogProduct,Double>>comparingDouble(Map.Entry::getValue).reversed()
                        .thenComparingLong(e->e.getKey().id())).limit(100).map(Map.Entry::getKey)
                .map(offers::hydrate).filter(criteria::matches).limit(criteria.limit()).toList();
        if(!ranked.isEmpty())return new Outcome(ranked,version,"LEXICAL",warnings);
        if(version.embeddingStatus()!=EmbeddingStatus.READY || !embeddings.vectorSpace().equals(version.vectorSpace())) {
            warnings.add("Семантический индекс недоступен; точный и лексический поиск работают");
            return new Outcome(List.of(),version,"LEXICAL",warnings);
        }
        try {
            float[] vector=queryEmbedding.embed(criteria.query());
            var semantic=repository.semanticSearch(version.id(),vector,Math.min(100,criteria.limit()*4),criteria.category(),criteria.brand(),criteria.specs(),false)
                    .stream().map(offers::hydrate).filter(criteria::matches).limit(criteria.limit()).toList();
            if(embeddings.vectorSpace().startsWith("fake:"))warnings.add("Детерминированные тестовые embeddings: качество семантики не подтверждено");
            return new Outcome(semantic,version,"SEMANTIC",warnings);
        } catch(RuntimeException e) {
            warnings.add("Провайдер семантического поиска недоступен; попробуйте артикул или уточните название");
            return new Outcome(List.of(),version,"UNAVAILABLE",warnings);
        }
    }
    static double lexical(String query,CatalogProduct p) {
        String text=normalize(p.name()+" "+Objects.toString(p.brand(),"")+" "+p.category()+" "+String.join(" ",p.specs().values()));
        String[] words=text.split("[^\\p{L}\\p{N}.]+");
        List<String> tokens=Arrays.stream(normalize(query).split("[^\\p{L}\\p{N}.]+"))
                .filter(t->!t.isBlank()&&!Set.of("нужен","нужна","нужно","найди","покажи","для","и","на").contains(t)).toList();
        if(tokens.isEmpty())return 0;
        double total=0;
        for(String token:tokens) {
            if(token.matches("[0-9]+[aа]"))token=token.substring(0,token.length()-1);
            double best=0;
            for(String word:words) {
                if(word.equals(token))best=Math.max(best,1);
                else if(token.length()>=3&&word.startsWith(token))best=Math.max(best,.9);
                else if(token.length()>=4&&distance(token,word)<=1)best=Math.max(best,.7);
            }
            if(best==0)return 0;
            total+=best;
        }
        return total/tokens.size();
    }
    private static String normalize(String s) { return s.toLowerCase(Locale.ROOT).replace('ё','е'); }
    private static int distance(String a,String b) {
        if(Math.abs(a.length()-b.length())>1)return 2;
        int[] prev=new int[b.length()+1];for(int j=0;j<prev.length;j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){int[] next=new int[b.length()+1];next[0]=i;
            for(int j=1;j<=b.length();j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=next;}
        return prev[b.length()];
    }
}
