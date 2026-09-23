package com.hackalem.catalog;
import com.hackalem.domain.catalog.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CompatibilityRulesTest {
    private CatalogProduct product(String unit,Map<String,String> specs,boolean synthetic){return new CatalogProduct(1,"1","sku","SKU","same name","brand","breakers",unit,BigDecimal.ONE,BigDecimal.ONE,specs,List.of(),null,"v1",synthetic,1,null,ProductStock.from(List.of()),null);}
    private Map<String,String> specs(){return Map.of("poles","1","currentA","16","voltageV","230","curve","C","breakingCapacityKa","6");}
    @Test void missingHardSpecRequiresClarificationInsteadOfVerification(){var missing=new HashMap<>(specs());missing.remove("currentA");assertThat(CompatibilityRules.check(product("pcs",specs(),true),product("pcs",missing,true)).status()).isEqualTo("NEEDS_SPECIFICATION");}
    @Test void incompatibleCurrentAndUnitAreRejectedDespiteIdenticalName(){var wrong=new HashMap<>(specs());wrong.put("currentA","32");assertThat(CompatibilityRules.check(product("pcs",specs(),true),product("pcs",wrong,true)).status()).isEqualTo("INCOMPATIBLE");assertThat(CompatibilityRules.check(product("pcs",specs(),true),product("box",specs(),true)).status()).isEqualTo("INCOMPATIBLE");}
    @Test void syntheticRulesNeverVerifyRealEquipment(){assertThat(CompatibilityRules.check(product("pcs",specs(),true),product("pcs",specs(),false)).status()).isEqualTo("NEEDS_SPECIFICATION");}
}
