package com.hackalem.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * /v3/api-docs — источник правды для фронта (`npm run gen`).
 * Правишь контракт → перегенерируй клиент.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hackalemOpenApi() {
        return new OpenAPI()
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth"))
                .info(new Info()
                        .title("HackAlem AI API")
                        .version("v1")
                        .description("ИИ-ассистент для чата на сайте ekt.kz"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    public org.springdoc.core.customizers.OpenApiCustomizer errors() {
        return api -> {
            // Jackson interfaces produce parent-union -> subtype -> parent allOf cycles.
            // Flatten the generated subtype properties; retain the real wire discriminator.
            var variants=java.util.Map.of("StatusPayload","status","DeltaPayload","delta","ProductsPayload","products",
                "ProposalPayload","proposal","TerminalPayload","terminal","SourcesPayload","sources","AlternativesPayload","alternatives","ReviewPayload","review");
            var payload = api.getComponents().getSchemas().get("EventPayload");
            if (payload != null) {
                var discriminator = new io.swagger.v3.oas.models.media.Discriminator().propertyName("kind");
                variants.forEach((name, kind) -> discriminator.mapping(kind, "#/components/schemas/" + name));
                payload.setDiscriminator(discriminator);
            }
            variants.forEach((name,kind)->{
                var schema=api.getComponents().getSchemas().get(name);
                if(schema==null)return;
                if(schema.getAllOf()!=null){
                    for(Object item:schema.getAllOf()){
                        var part=(io.swagger.v3.oas.models.media.Schema<?>)item;
                        if(part.getProperties()!=null)part.getProperties().forEach((key,value)->schema.addProperty(key,(io.swagger.v3.oas.models.media.Schema<?>)value));
                    }
                    schema.setAllOf(null);
                }
                schema.setType("object");schema.addProperty("kind",new io.swagger.v3.oas.models.media.StringSchema()._enum(java.util.List.of(kind)));
                schema.addRequiredItem("kind");
            });
            var nullable=java.util.Map.ofEntries(
                java.util.Map.entry("RunSnapshot",java.util.List.of("errorCode","finishedAt")),
                java.util.Map.entry("SourceRef",java.util.List.of("page","sheet","row")),
                java.util.Map.entry("OperationOutcome",java.util.List.of("cart","code")),
                java.util.Map.entry("MessagePage",java.util.List.of("nextCursor")),
                java.util.Map.entry("ConversationPage",java.util.List.of("nextCursor")),
                java.util.Map.entry("StatusPayload",java.util.List.of("tool")),
                java.util.Map.entry("DialogueState",java.util.List.of("category","budget","quantity","lastResultSetId","fulfillmentOptionId","activeProposalId","attachmentId","attachmentVersion")));
            nullable.forEach((name,fields)->{
                var schema=api.getComponents().getSchemas().get(name);if(schema==null || schema.getProperties()==null)return;
                fields.forEach(field->{var original=(io.swagger.v3.oas.models.media.Schema<?>)schema.getProperties().get(field);
                    if(original!=null)schema.addProperty(field,new io.swagger.v3.oas.models.media.ComposedSchema().addAnyOfItem(original).addAnyOfItem(new io.swagger.v3.oas.models.media.Schema<>().type("null")));});
            });
            var problem=new io.swagger.v3.oas.models.media.Schema<>().type("object")
                .addProperty("status",new io.swagger.v3.oas.models.media.Schema<>().type("integer"))
                .addProperty("detail",new io.swagger.v3.oas.models.media.Schema<>().type("string"))
                .addProperty("code",new io.swagger.v3.oas.models.media.Schema<>().type("string"))
                .addProperty("correlationId",new io.swagger.v3.oas.models.media.Schema<>().type("string"))
                .addProperty("retryable",new io.swagger.v3.oas.models.media.Schema<>().type("boolean"));
            api.getComponents().addSchemas("ApiProblem",problem);
            api.getPaths().forEach((path,item)->item.readOperations().forEach(operation->{
                if(path.equals("/auth/visitor-session") || path.equals("/api/ping")) operation.setSecurity(java.util.List.of());
                for(String status:java.util.List.of("400","401","403","404","409","429","503"))
                    operation.getResponses().addApiResponse(status,new io.swagger.v3.oas.models.responses.ApiResponse().description("Domain error with stable code")
                        .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/problem+json",new io.swagger.v3.oas.models.media.MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/ApiProblem")))));
            }));
        };
    }
}
