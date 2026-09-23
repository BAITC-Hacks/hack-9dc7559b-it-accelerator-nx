package com.hackalem.attachments;
import com.hackalem.domain.attachments.*;
import static com.hackalem.domain.attachments.AttachmentModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class AttachmentMatchingTest {
    @Test void exactCodeWithConflictingRatingCannotBeMatched(){
        var product=new Product("10","000001","C16 breaker","pcs",BigDecimal.ONE,BigDecimal.ONE,Map.of("currentA","16","curve","C","poles","1"),List.of("main"));
        var catalog=new AttachmentCatalog(){public List<Product> search(String owner,String query){return List.of(product);}public Optional<Product> product(String id){return Optional.of(product);}};
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("catalog",catalog);var matcher=new AttachmentMatcher(beans.getBeanProvider(AttachmentCatalog.class));
        var row=new ExtractedRow("row-1","000001 | C32 1P | 3 pcs","000001","3","pcs",new Location("sheet","test",2,"A2",null,null),List.of());
        var result=matcher.match("owner",new Extraction(List.of(row),List.of())).getFirst();
        assertThat(result.status()).isEqualTo("needs_review");assertThat(result.candidates().getFirst().evidence()).isEqualTo("hard_attribute_conflict");
    }
}
