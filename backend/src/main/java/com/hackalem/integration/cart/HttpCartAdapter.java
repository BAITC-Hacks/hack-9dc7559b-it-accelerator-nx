package com.hackalem.integration.cart;

import static com.hackalem.domain.port.Contracts.*;

import com.hackalem.domain.port.CartPort;
import com.hackalem.web.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Opt-in boundary contract, not a claim that the partner implements this protocol. */
@Component
@ConditionalOnProperty(name = "app.cart-mode", havingValue = "partner")
public class HttpCartAdapter implements CartPort {
    private final RestClient client;
    private final Set<String> allowedOrigins;

    @Autowired
    public HttpCartAdapter(
            @Value("${app.partner-cart.verified:false}") boolean verified,
            @Value("${app.partner-cart.base-url:}") String baseUrl,
            @Value("${app.partner-cart.allowed-origins:}") String origins,
            @Value("${app.partner-cart.token:}") String token,
            @Value("${app.partner-cart.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.partner-cart.request-timeout-ms:5000}") int requestTimeoutMs) {
        this(new Settings(verified, baseUrl, origins, token), productionBuilder(connectTimeoutMs, requestTimeoutMs));
    }

    // Injectable transport permits contract tests without a real partner or real credentials.
    HttpCartAdapter(Settings settings, RestClient.Builder builder) {
        if (!settings.verified()) throw new IllegalStateException("Partner cart integration gate has not been verified");
        allowedOrigins = Arrays.stream(settings.allowedOrigins().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(HttpCartAdapter::allowlistedOrigin)
                .collect(Collectors.toUnmodifiableSet());
        URI base = checkedUri(settings.baseUrl());
        if (base.getRawQuery() != null || base.getRawFragment() != null || !base.normalize().equals(base)
                || !allowedOrigins.contains(origin(base))) {
            throw new IllegalArgumentException("Partner base URL must be HTTPS and explicitly allowlisted");
        }
        if (settings.token() == null || settings.token().isBlank() || settings.token().contains("\r") || settings.token().contains("\n")) {
            throw new IllegalArgumentException("Partner cart service credential is required");
        }
        client = builder.baseUrl(base.toASCIIString().replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + settings.token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
    }

    @Override
    public CartSnapshot get(TrustedScope scope) {
        try {
            CartSnapshot cart = client.get().uri("/carts/{cartId}", scope.cartId())
                    .header("X-Principal-Id", scope.principalId().toString())
                    .retrieve().onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw ApiException.unavailable("partner_cart_unavailable");
                    }).body(CartSnapshot.class);
            return checkedCart(scope, cart);
        } catch (RestClientException ex) {
            throw ApiException.unavailable("partner_cart_unavailable");
        }
    }

    @Override
    public OperationOutcome addConditionally(TrustedScope scope, ProposalSnapshot proposal) {
        if (!scope.cartId().toString().equals(proposal.cartId())) throw ApiException.missing();
        checkedId(proposal.operationId());
        checkedId(proposal.id());
        checkedVersion(proposal.expectedCartVersion());
        try {
            OperationOutcome outcome = client.post()
                    .uri("/carts/{cartId}/operations/{operationId}", scope.cartId(), proposal.operationId())
                    .header("X-Principal-Id", scope.principalId().toString())
                    .header("Idempotency-Key", proposal.operationId())
                    .header(HttpHeaders.IF_MATCH, "\"" + proposal.expectedCartVersion() + "\"")
                    .contentType(MediaType.APPLICATION_JSON).body(proposal)
                    .retrieve().onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw ApiException.unavailable("partner_cart_unavailable");
                    }).body(OperationOutcome.class);
            OperationOutcome checked = checkedOutcome(scope, proposal.operationId(), proposal.id(), outcome);
            if (checked.status().equals("outcome_unknown")) throw ApiException.unavailable("partner_outcome_unknown");
            return checked;
        } catch (RestClientException | ApiException ex) {
            // A timeout, malformed reply, redirect or error status does not establish whether the write committed.
            // Lookup is safe; an absent/failed lookup is never permission to replay this POST.
            try {
                var known = lookupOperation(scope, proposal.operationId());
                if (known.isPresent()) return checkedOutcome(scope, proposal.operationId(), proposal.id(), known.get());
            } catch (RestClientException | ApiException ignored) {
                // Keep the durable operation pending for later reconciliation.
            }
            return new OperationOutcome(proposal.operationId(), proposal.id(), "outcome_unknown", null, "partner_outcome_unknown");
        }
    }

    @Override
    public Optional<OperationOutcome> lookupOperation(TrustedScope scope, String operationId) {
        checkedId(operationId);
        return client.get().uri("/carts/{cartId}/operations/{operationId}", scope.cartId(), operationId)
                .header("X-Principal-Id", scope.principalId().toString())
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) return Optional.empty();
                    if (!response.getStatusCode().is2xxSuccessful()) throw ApiException.unavailable("partner_lookup_unavailable");
                    return Optional.of(checkedOutcome(scope, operationId, null, response.bodyTo(OperationOutcome.class)));
                });
    }

    private OperationOutcome checkedOutcome(TrustedScope scope, String operationId, String proposalId, OperationOutcome outcome) {
        if (outcome == null || !operationId.equals(outcome.id()) || outcome.proposalId() == null
                || (proposalId != null && !proposalId.equals(outcome.proposalId()))
                || !Set.of("succeeded", "failed", "outcome_unknown").contains(outcome.status() == null ? "" : outcome.status())) {
            throw ApiException.unavailable("invalid_partner_outcome");
        }
        checkedId(outcome.proposalId());
        if (outcome.status().equals("succeeded") || outcome.cart() != null) checkedCart(scope, outcome.cart());
        // A URL/snapshot accompanying an uncertain response must not be presented as a committed cart.
        if (outcome.status().equals("outcome_unknown")) {
            return new OperationOutcome(outcome.id(), outcome.proposalId(), outcome.status(), null, outcome.code());
        }
        return outcome;
    }

    private CartSnapshot checkedCart(TrustedScope scope, CartSnapshot cart) {
        if (cart == null || !scope.cartId().toString().equals(cart.id()) || cart.lines() == null) {
            throw ApiException.unavailable("invalid_partner_cart");
        }
        checkedVersion(cart.version());
        try {
            if (!allowedOrigins.contains(origin(checkedUri(cart.url())))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw ApiException.unavailable("invalid_partner_cart_url");
        }
        return cart;
    }

    private static void checkedId(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw ApiException.unavailable("invalid_partner_operation_id");
        }
    }

    private static void checkedVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9._-]{1,128}")) {
            throw ApiException.unavailable("invalid_partner_cart_version");
        }
    }

    private static URI checkedUri(String value) {
        if (value == null) throw new IllegalArgumentException("HTTPS URL required");
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("HTTPS URL without credentials or fragment required");
        }
        return uri;
    }

    private static String allowlistedOrigin(String value) {
        URI uri = checkedUri(value);
        if (uri.getRawQuery() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Allowlist entries must be HTTPS origins without paths");
        }
        return origin(uri);
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + ((port == -1 || port == 443) ? "" : ":" + port);
    }

    private static RestClient.Builder productionBuilder(int connectTimeoutMs, int requestTimeoutMs) {
        if (connectTimeoutMs < 1 || requestTimeoutMs < 1) throw new IllegalArgumentException("Positive partner timeouts required");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(requestTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }

    record Settings(boolean verified, String baseUrl, String allowedOrigins, String token) {}
}
