package com.hackalem.ai.gateway;
import com.hackalem.domain.port.LlmGateway;
import com.hackalem.domain.Json;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
@Component @Profile({"contract","test"})
public class ContractLlm implements LlmGateway {
    private final Json json;public ContractLlm(Json json){this.json=json;}
    public Round stream(List<Turn> context,List<ToolSpec> tools,int maxTokens,Instant deadline,Consumer<String> sink){
        var last=context.getLast();
        if(last.role().equals("tool"))return new Round("Проверенные данные показаны в карточках. Добавление требует отдельного подтверждения.",List.of(),80,20,"contract-scripted","offline");
        String text=context.stream().filter(t->t.role().equals("user")).reduce((a,b)->b).orElseThrow().text();
        String tool=text.toLowerCase(Locale.ROOT).matches(".*(достав|оплат|услови).* ")?"search_purchase_terms":"search_catalog";
        if(text.toLowerCase(Locale.ROOT).contains("достав") || text.toLowerCase(Locale.ROOT).contains("услови"))tool="search_purchase_terms";
        return new Round("",List.of(new ToolCall("call-"+UUID.randomUUID(),tool,json.write(Map.of("query",text)))),100,20,"contract-scripted","offline");
    }
}
