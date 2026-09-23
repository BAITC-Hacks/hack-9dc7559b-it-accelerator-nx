package com.hackalem.domain.port;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared v1 wire types. Decimal values and database counters never use JS numbers. */
public final class Contracts {
    private Contracts() {}
    public record TrustedScope(UUID principalId, UUID cartId) {}
    public record Money(@Pattern(regexp="[0-9]+(\\.[0-9]+)?") String amount, String currency) {}
    public record Quantity(@Pattern(regexp="[0-9]+(\\.[0-9]+)?") String value, String unit, String step) {}
    public record SourceRef(String id, String version, String title, Integer page, String sheet, Integer row) {}
    public record ProductDetails(String id, String article, String name, Map<String,String> specs,
                                 List<SourceRef> certificates) {}
    public record OfferSnapshot(String article, Money price, Quantity available, String warehouse,
                                String stockBucket, String version, Instant observedAt, Instant expiresAt) {}
    public record SearchQuery(@NotBlank @Size(max=2000) String query, Map<String,String> hardConstraints,
                              String category, Money maxPrice, Quantity quantity) {}
    public record ProductResultSet(String id, String version, List<ProductDetails> products,
                                   List<OfferSnapshot> offers) {}
    public record AlternativePlan(String id, String kind, List<Selection> lines, List<String> differences) {}
    public record SourceChunk(SourceRef source, String text) {}
    public record Selection(@NotBlank String article, @NotBlank String unit,
                            @NotBlank String warehouse, @NotBlank @Pattern(regexp="[0-9]+(\\.[0-9]+)?") String addQuantity) {}
    public record ReviewedItems(String attachmentId, String version, List<Selection> items, List<SourceRef> sources) {}
    public record AttachmentResult(String id, String version, String status, List<Selection> reviewedItems,
                                    List<SourceRef> sources, List<String> reviewReasons) {}
    public record ProposalLine(String article, Quantity addQuantity, Money unitPrice, String warehouse,
                                String stockBucket, String offerVersion, List<String> provenance) {}
    public record ProposalSnapshot(String id, String conversationId, String cartId, String revision,
                                    String digest, String operationId, String expectedCartVersion,
                                    String status, Instant expiresAt, List<ProposalLine> lines) {}
    public record CartLine(String article, Quantity quantity, Money unitPrice, String warehouse, String stockBucket) {}
    public record CartSnapshot(String id, String version, List<CartLine> lines, String url) {}
    public record OperationOutcome(String id, String proposalId, String status, CartSnapshot cart, String code) {}
    public record SessionToken(String accessToken, Instant expiresAt, String principalId, String cartId) {}
    public record Conversation(String id, String version, Instant createdAt) {}
    public record Message(String id, String sequence, String role, String text, String runId, Instant createdAt) {}
    public record MessagePage(List<Message> items, String nextCursor) {}
    public record ConversationPage(List<Conversation> items, String nextCursor) {}
    public record TurnRequest(@NotBlank @Size(max=8000) String text, String resultSetId, String expectedStateVersion) {}
    public record AcceptedTurn(String messageId, String runId) {}
    public record RunSnapshot(String id, String conversationId, String status, String epoch, String sequence,
                              String text, String errorCode, Instant createdAt, Instant finishedAt) {}
    public record DialogueState(String version, String category, Money budget, Map<String,String> hardConstraints,
                                 Quantity quantity, String lastResultSetId, List<String> selectedArticles,
                                 String fulfillmentOptionId, String activeProposalId, String attachmentId,
                                 String attachmentVersion) {}
    public record UpdateDialogue(@NotBlank String expectedVersion, String category, Money budget,
                                  Map<String,String> hardConstraints, Quantity quantity, String resultSetId,
                                  List<Integer> selectedIndices, String fulfillmentOptionId,
                                  String attachmentId, String attachmentVersion,
                                  @Schema(description="Parameters to reset. Omitted fields otherwise retain their saved value. Allowed: category, budget, quantity, hardConstraints.")
                                  List<String> clearFields) {
        public UpdateDialogue(String expectedVersion, String category, Money budget,
                              Map<String,String> hardConstraints, Quantity quantity, String resultSetId,
                              List<Integer> selectedIndices, String fulfillmentOptionId,
                              String attachmentId, String attachmentVersion) {
            this(expectedVersion,category,budget,hardConstraints,quantity,resultSetId,selectedIndices,
                    fulfillmentOptionId,attachmentId,attachmentVersion,null);
        }
    }
    public record ProposalRequest(@NotNull UUID conversationId, @NotBlank String expectedStateVersion,
                                   @NotBlank String resultSetId, @NotEmpty @Size(max=50) List<@Valid Selection> lines) {}
    public record ConfirmRequest(@NotBlank String revision, @NotBlank String digest,
                                  @NotNull ConsentOrigin origin, @Size(max=80) String text) {}
    public enum ConsentOrigin { button, user_text }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, property="kind")
    @JsonSubTypes({@JsonSubTypes.Type(value=StatusPayload.class,name="status"),
        @JsonSubTypes.Type(value=DeltaPayload.class,name="delta"),
        @JsonSubTypes.Type(value=ProductsPayload.class,name="products"),
        @JsonSubTypes.Type(value=ProposalPayload.class,name="proposal"),
        @JsonSubTypes.Type(value=TerminalPayload.class,name="terminal"),
        @JsonSubTypes.Type(value=SourcesPayload.class,name="sources"),
        @JsonSubTypes.Type(value=AlternativesPayload.class,name="alternatives"),
        @JsonSubTypes.Type(value=ReviewPayload.class,name="review")})
    @Schema(discriminatorProperty="kind", oneOf={StatusPayload.class,DeltaPayload.class,ProductsPayload.class,
        ProposalPayload.class,TerminalPayload.class,SourcesPayload.class,AlternativesPayload.class,ReviewPayload.class})
    public sealed interface EventPayload permits StatusPayload,DeltaPayload,ProductsPayload,ProposalPayload,
        TerminalPayload,SourcesPayload,AlternativesPayload,ReviewPayload {}
    public record StatusPayload(String status, String tool) implements EventPayload {}
    public record DeltaPayload(String text) implements EventPayload {}
    public record ProductsPayload(ProductResultSet resultSet) implements EventPayload {}
    public record ProposalPayload(ProposalSnapshot proposal) implements EventPayload {}
    public record TerminalPayload(RunSnapshot snapshot, boolean replayUnavailable) implements EventPayload {}
    public record SourcesPayload(List<SourceRef> sources, List<SourceChunk> excerpts) implements EventPayload {}
    public record AlternativesPayload(List<AlternativePlan> alternatives) implements EventPayload {}
    public record ReviewPayload(ReviewedItems review) implements EventPayload {}
    public record ChatEvent(String eventId, String runId, String seq, String epoch, String type,
                             String schemaVersion, EventPayload payload) {}
}
