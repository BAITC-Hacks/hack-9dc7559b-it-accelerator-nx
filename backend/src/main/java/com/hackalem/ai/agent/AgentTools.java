package com.hackalem.ai.agent;
import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.port.*;
import com.hackalem.domain.port.LlmGateway.*;
import com.hackalem.domain.Json;
import com.hackalem.domain.chat.ChatService;
import com.hackalem.domain.cart.CartService;
import com.hackalem.web.ApiException;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Allowlisted read/propose tools. No mutation, confirm, credentials, SQL or owner arguments. */
@Component
public class AgentTools {
    public static final Set<String> NAMES=Set.of("search_catalog","check_stock","find_analogs","search_purchase_terms","read_reviewed_attachment","propose_cart_addition");
    private final ObjectProvider<CatalogPort> catalog;private final ObjectProvider<StockPort> stock;
    private final ObjectProvider<AnalogsPort> analogs;private final ObjectProvider<KnowledgePort> knowledge;
    private final ObjectProvider<AttachmentPort> attachments;private final ChatService chat;private final CartService cart;private final Json json;private final ObjectMapper mapper;
    public AgentTools(ObjectProvider<CatalogPort> catalog,ObjectProvider<StockPort> stock,ObjectProvider<AnalogsPort> analogs,ObjectProvider<KnowledgePort> knowledge,ObjectProvider<AttachmentPort> attachments,ChatService chat,CartService cart,Json json,ObjectMapper mapper){this.catalog=catalog;this.stock=stock;this.analogs=analogs;this.knowledge=knowledge;this.attachments=attachments;this.chat=chat;this.cart=cart;this.json=json;this.mapper=mapper;}
    public List<ToolSpec> specs(){return NAMES.stream().sorted().map(name->new ToolSpec(name,switch(name){
        case "search_catalog"->"Search authoritative catalog, preserving all saved hard constraints. query required.";
        case "check_stock"->"Fresh quantities and prices for an article. article required.";
        case "find_analogs"->"Find compatible alternatives for article; never silently replace selection.";
        case "search_purchase_terms"->"Retrieve purchase conditions with verified source references. query required.";
        case "read_reviewed_attachment"->"Read owner-scoped reviewed attachment rows, attachmentId and version required.";
        default->"Prepare only; never adds to cart. Use resultSetId and zero-based indices from shown results; quantity is additive decimal.";
    },schema(name))).toList();}
    private String schema(String name){
        Map<String,Object> props=new LinkedHashMap<>();List<String> required;
        switch(name){
            case "search_catalog","search_purchase_terms"->{props.put("query",Map.of("type","string","maxLength",2000));required=List.of("query");}
            case "check_stock","find_analogs"->{props.put("article",Map.of("type","string","maxLength",200));required=List.of("article");}
            case "read_reviewed_attachment"->{props.put("attachmentId",Map.of("type","string"));props.put("version",Map.of("type","string"));required=List.of("attachmentId","version");}
            default->{props.put("resultSetId",Map.of("type","string"));props.put("indices",Map.of("type","array","items",Map.of("type","integer","minimum",0),"minItems",1,"maxItems",50));props.put("quantity",Map.of("type","string","pattern","^[0-9]+(\\.[0-9]+)?$"));required=List.of("resultSetId","indices","quantity");}
        }
        return json.write(Map.of("type","object","properties",props,"required",required,"additionalProperties",false));
    }
    public String execute(ChatService.Claim claim,ToolCall call){
        if(!NAMES.contains(call.name()))throw new ApiException(400,"tool_not_allowed");
        if(call.id()==null || call.id().length()>160 || call.arguments()==null || call.arguments().length()>8000)throw new ApiException(400,"invalid_tool_arguments");
        chat.assertActive(claim);String hash=Json.hash(call.name()+"|"+call.arguments());var old=chat.previousTool(claim,call.id(),hash);
        // Recheck current ACL, review revision and stock even when the model repeats a call ID.
        if(old.isPresent() && !Set.of("search_purchase_terms","read_reviewed_attachment","check_stock").contains(call.name()))return old.get();
        JsonNode args;try{args=mapper.readTree(call.arguments());var schema=mapper.readTree(schema(call.name()));
            if(!args.isObject())throw new IllegalArgumentException();
            for(var names=args.fieldNames();names.hasNext();)if(!schema.get("properties").has(names.next()))throw new IllegalArgumentException();
            for(var field:schema.get("required"))if(!args.hasNonNull(field.asText()))throw new IllegalArgumentException();
        }catch(Exception e){throw new ApiException(400,"invalid_tool_arguments");}
        chat.emit(claim,"tool.status",new StatusPayload("running",call.name()));Object result;
        var state=chat.state(claim.scope(),claim.conversation());
        switch(call.name()){
            case "search_catalog"->{var query=new SearchQuery(string(args,"query"),state.hardConstraints(),state.category(),state.budget(),state.quantity());
                result=chat.saveResults(claim,required(catalog,"catalog").search(query,claim.scope()));}
            case "check_stock"->{String article=string(args,"article");required(catalog,"catalog").getProduct(article,claim.scope());
                // Unit and warehouse are taken from a shown result, never invented by the model.
                var shown=shownOffer(claim,state,article);result=required(stock,"stock").getOffers(List.of(new Selection(article,shown.available().unit(),shown.warehouse(),"1")),claim.scope());}
            case "find_analogs"->{var plans=required(analogs,"analogs").find(string(args,"article"),new SearchQuery("analogs",state.hardConstraints(),state.category(),state.budget(),state.quantity()),claim.scope());
                chat.emit(claim,"alternatives.result",new AlternativesPayload(plans));result=plans;}
            case "search_purchase_terms"->{var chunks=required(knowledge,"knowledge").retrieve(string(args,"query"),claim.scope(),6000);
                chat.emit(claim,"sources.result",new SourcesPayload(chunks.stream().map(SourceChunk::source).toList(),chunks));
                chat.delta(claim,chunks.isEmpty()?"В доступных источниках нет подтверждённых условий. ":"По найденным источникам: "+chunks.stream().map(c->c.text()+" ["+c.source().title()+"]").collect(java.util.stream.Collectors.joining("\n"))+"\n");result=chunks;}
            case "read_reviewed_attachment"->{var reviewed=required(attachments,"attachments").getReviewedItems(string(args,"attachmentId"),string(args,"version"),claim.scope());
                chat.emit(claim,"attachment.review",new ReviewPayload(reviewed));result=reviewed;}
            case "propose_cart_addition"->{String resultId=string(args,"resultSetId");var shown=chat.resultSet(claim.scope(),claim.conversation(),ChatService.uuid(resultId));
                JsonNode indices=args.get("indices");if(!indices.isArray() || indices.isEmpty() || indices.size()>50)throw new ApiException(400,"invalid_selection");
                String quantity=string(args,"quantity");CartService.positive(quantity);List<Selection> selections=new ArrayList<>();
                for(JsonNode index:indices){if(!index.isIntegralNumber() || index.asInt()<0 || index.asInt()>=shown.products().size())throw new ApiException(400,"invalid_selection");
                    String article=shown.products().get(index.asInt()).article();var offer=shown.offers().stream().filter(o->o.article().equals(article)).findFirst().orElseThrow(()->ApiException.conflict("offer_unavailable"));
                    selections.add(new Selection(article,offer.available().unit(),offer.warehouse(),quantity));}
                var proposal=cart.propose(claim.scope(),new ProposalRequest(claim.conversation(),state.version(),resultId,selections),"tool-"+Json.hash(claim.id()+":"+call.id()),claim);
                chat.emit(claim,"cart.proposal",new ProposalPayload(proposal));result=proposal;}
            default->throw new ApiException(400,"tool_not_allowed");
        }
        String wire=json.write(result);if(wire.length()>24000)throw new ApiException(413,"tool_result_too_large");chat.saveTool(claim,call.id(),call.name(),hash,wire);return wire;
    }
    private OfferSnapshot shownOffer(ChatService.Claim claim,DialogueState state,String article){if(state.lastResultSetId()==null)throw ApiException.conflict("result_set_required");return chat.resultSet(claim.scope(),claim.conversation(),ChatService.uuid(state.lastResultSetId())).offers().stream().filter(o->o.article().equals(article)).findFirst().orElseThrow(()->ApiException.conflict("offer_unavailable"));}
    private static String string(JsonNode args,String key){var value=args.get(key);if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>2000)throw new ApiException(400,"invalid_tool_arguments");return value.asText();}
    private static <T>T required(ObjectProvider<T> provider,String source){T value=provider.getIfAvailable();if(value==null)throw ApiException.unavailable(source+"_source_unavailable");return value;}
}
