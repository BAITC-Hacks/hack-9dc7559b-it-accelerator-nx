package com.hackalem.integration.cart;

import static com.hackalem.domain.port.Contracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackalem.web.ApiException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpCartAdapterTest {
    private static final String BASE = "https://partner.example/api/v1";
    private static final TrustedScope SCOPE = new TrustedScope(UUID.randomUUID(), UUID.randomUUID());
    private static final String OPERATION = UUID.randomUUID().toString();
    private static final String PROPOSAL = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockRestServiceServer server;
    private HttpCartAdapter adapter;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new HttpCartAdapter(settings(true, BASE, "https://partner.example,https://shop.example", "test-only"), builder);
    }

    @Test
    void mutationUsesBoundCartStableOperationAndConditionalVersion() throws Exception {
        OperationOutcome success = outcome("succeeded", cart("https://shop.example/cart/" + SCOPE.cartId()));
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-only"))
                .andExpect(header("X-Principal-Id", SCOPE.principalId().toString()))
                .andExpect(header("Idempotency-Key", OPERATION)).andExpect(header("If-Match", "\"7\""))
                .andExpect(jsonPath("$.cartId").value(SCOPE.cartId().toString()))
                .andExpect(jsonPath("$.operationId").value(OPERATION))
                .andExpect(jsonPath("$.lines[0].addQuantity.value").value("1.25"))
                .andRespond(withSuccess(json.writeValueAsString(success), MediaType.APPLICATION_JSON));
        assertThat(adapter.addConditionally(SCOPE, proposal())).isEqualTo(success);
        server.verify();
    }

    @Test
    void timeoutAfterCommitResolvesByLookupWithoutAnotherMutation() throws Exception {
        OperationOutcome success = outcome("succeeded", cart("https://shop.example/cart"));
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("response lost after commit")));
        expectLookup(success);
        assertThat(adapter.addConditionally(SCOPE, proposal())).isEqualTo(success);
        server.verify();
    }

    @Test
    void missingLookupKeepsOutcomeUnknownWithoutReplayingMutation() {
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST)).andRespond(withServerError());
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
        assertUnknown(adapter.addConditionally(SCOPE, proposal()));
        server.verify();
    }

    @Test
    void unavailableLookupKeepsOutcomeUnknown() {
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST)).andRespond(withBadRequest());
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.GET))
                .andRespond(withException(new SocketTimeoutException("lookup unavailable")));
        assertUnknown(adapter.addConditionally(SCOPE, proposal()));
        server.verify();
    }

    @Test
    void redirectResponseIsNotTrustedAsCommitAndReconciles() {
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT).location(java.net.URI.create("https://attacker.example/cart")));
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
        assertUnknown(adapter.addConditionally(SCOPE, proposal()));
        server.verify();
    }

    @Test
    void explicitUncertainResponseAlsoTriggersLookup() throws Exception {
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json.writeValueAsString(outcome("outcome_unknown", null)), MediaType.APPLICATION_JSON));
        OperationOutcome failed = outcome("failed", cart("https://shop.example/cart"));
        expectLookup(failed);
        assertThat(adapter.addConditionally(SCOPE, proposal())).isEqualTo(failed);
        server.verify();
    }

    @Test
    void conflictStatusNeedsAuthoritativeStoredOutcome() throws Exception {
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));
        OperationOutcome failed = new OperationOutcome(OPERATION, PROPOSAL, "failed", cart("https://shop.example/cart"), "cart_version_conflict");
        expectLookup(failed);
        assertThat(adapter.addConditionally(SCOPE, proposal())).isEqualTo(failed);
        server.verify();
    }

    @Test
    void malformedMutationReplyReconcilesAndNeverClaimsSuccess() {
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{invalid-json", MediaType.APPLICATION_JSON));
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
        assertUnknown(adapter.addConditionally(SCOPE, proposal()));
        server.verify();
    }

    @Test
    void foreignCartCannotChooseMutationDestination() {
        var otherScope = new TrustedScope(SCOPE.principalId(), UUID.randomUUID());
        assertThatThrownBy(() -> adapter.addConditionally(otherScope, proposal())).isInstanceOf(ApiException.class)
                .hasMessage("resource_not_found");
        server.verify();
    }

    @Test
    void foreignOutcomeIdentityIsRejected() throws Exception {
        OperationOutcome foreign = new OperationOutcome(UUID.randomUUID().toString(), PROPOSAL, "succeeded", cart("https://shop.example/cart"), null);
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json.writeValueAsString(foreign), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.lookupOperation(SCOPE, OPERATION)).hasMessage("invalid_partner_outcome");
        server.verify();
    }

    @Test
    void foreignCartSnapshotIsRejected() throws Exception {
        CartSnapshot foreign = new CartSnapshot(UUID.randomUUID().toString(), "8", List.of(), "https://shop.example/cart");
        server.expect(requestTo(BASE + "/carts/" + SCOPE.cartId())).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json.writeValueAsString(foreign), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.get(SCOPE)).hasMessage("invalid_partner_cart");
        server.verify();
    }

    @Test
    void nonAllowlistedCartUrlIsRejectedEvenOnSuccessfulReply() throws Exception {
        server.expect(requestTo(BASE + "/carts/" + SCOPE.cartId()))
                .andRespond(withSuccess(json.writeValueAsString(cart("https://shop.example.attacker.example/cart")), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.get(SCOPE)).hasMessage("invalid_partner_cart_url");
        server.verify();
    }

    @Test
    void configurationRequiresVerifiedGateHttpsAllowlistAndCredential() {
        assertThatThrownBy(() -> new HttpCartAdapter(settings(false, BASE, "https://partner.example", "test"), RestClient.builder()))
                .hasMessageContaining("gate has not been verified");
        for (String base : List.of("http://partner.example", "https://attacker.example", "https://secret@partner.example", BASE + "?token=x", BASE + "#x")) {
            assertThatThrownBy(() -> new HttpCartAdapter(settings(true, base, "https://partner.example", "test"), RestClient.builder()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new HttpCartAdapter(settings(true, BASE, "https://partner.example/path", "test"), RestClient.builder()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpCartAdapter(settings(true, BASE, "https://partner.example", ""), RestClient.builder()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partnerBeanIsDisabledByDefaultAndFailsClosedWhenUnverified() {
        new ApplicationContextRunner().withUserConfiguration(HttpCartAdapter.class)
                .run(context -> assertThat(context).doesNotHaveBean(HttpCartAdapter.class));
        new ApplicationContextRunner().withUserConfiguration(HttpCartAdapter.class)
                .withPropertyValues("app.cart-mode=partner")
                .run(context -> assertThat(context).hasFailed());
    }

    private void expectLookup(OperationOutcome outcome) throws Exception {
        server.expect(requestTo(operationUrl())).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Principal-Id", SCOPE.principalId().toString()))
                .andRespond(withSuccess(json.writeValueAsString(outcome), MediaType.APPLICATION_JSON));
    }
    private static void assertUnknown(OperationOutcome outcome) {
        assertThat(outcome.id()).isEqualTo(OPERATION);
        assertThat(outcome.proposalId()).isEqualTo(PROPOSAL);
        assertThat(outcome.status()).isEqualTo("outcome_unknown");
        assertThat(outcome.cart()).isNull();
    }
    private static String operationUrl() { return BASE + "/carts/" + SCOPE.cartId() + "/operations/" + OPERATION; }
    private static HttpCartAdapter.Settings settings(boolean verified, String base, String origins, String token) {
        return new HttpCartAdapter.Settings(verified, base, origins, token);
    }
    private static CartSnapshot cart(String url) { return new CartSnapshot(SCOPE.cartId().toString(), "8", List.of(), url); }
    private static OperationOutcome outcome(String status, CartSnapshot cart) { return new OperationOutcome(OPERATION, PROPOSAL, status, cart, null); }
    private static ProposalSnapshot proposal() {
        return new ProposalSnapshot(PROPOSAL, UUID.randomUUID().toString(), SCOPE.cartId().toString(), "1", "digest", OPERATION, "7", "pending",
                Instant.parse("2030-01-01T00:00:00Z"), List.of(new ProposalLine("SKU-1", new Quantity("1.25", "m", "0.25"),
                new Money("1500.00", "KZT"), "main", "SKU-1:m:main", "3", List.of("catalog-fixture"))));
    }
}
