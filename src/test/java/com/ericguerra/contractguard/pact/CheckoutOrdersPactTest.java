package com.ericguerra.contractguard.pact;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-driven contract: checkout publishes what it actually relies on. The provider verifies
 * this pact in its own build (and `pact-broker can-i-deploy` gates releases). OpenAPI diffing
 * catches spec-level breaks; pacts catch the ones the spec can't express, like "checkout needs
 * total.currency even though the spec only says total is an object".
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "orders-api", pactVersion = PactSpecVersion.V3) // 4.3 defaults to V4; these use the V3 DSL
class CheckoutOrdersPactTest {

    @Pact(consumer = "checkout")
    public RequestResponsePact existingOrder(PactDslWithProvider builder) {
        return builder
                .given("order O-1001 exists and is paid")
                .uponReceiving("a request for an existing order")
                    .path("/orders/O-1001")
                    .method("GET")
                    .matchHeader("Authorization", "Bearer .+", "Bearer test-token")
                .willRespondWith()
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(new PactDslJsonBody()
                            .stringValue("id", "O-1001")
                            .stringMatcher("status", "[A-Z_]+", "PAID") // any upper-case status; client tolerates unknowns
                            .object("total")
                                .integerType("amount", 4599)
                                .stringMatcher("currency", "[A-Z]{3}", "USD")
                            .closeObject())
                .toPact();
    }

    @Pact(consumer = "checkout")
    public RequestResponsePact missingOrder(PactDslWithProvider builder) {
        return builder
                .given("order O-404 does not exist")
                .uponReceiving("a request for a missing order")
                    .path("/orders/O-404")
                    .method("GET")
                .willRespondWith()
                    .status(404)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "existingOrder")
    void readsExistingOrder(MockServer server) throws Exception {
        Optional<OrdersClient.OrderView> o = new OrdersClient(server.getUrl(), "test-token").get("O-1001");
        assertThat(o).contains(new OrdersClient.OrderView("O-1001", OrdersClient.Status.PAID, 4599, "USD"));
    }

    @Test
    @PactTestFor(pactMethod = "missingOrder")
    void handlesMissingOrder(MockServer server) throws Exception {
        assertThat(new OrdersClient(server.getUrl(), "test-token").get("O-404")).isEmpty();
    }
}
