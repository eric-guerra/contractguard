package com.ericguerra.contractguard.pact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * The checkout service's view of the Orders API: it only reads id, status and total, and tolerates
 * unknown status values (maps them to UNKNOWN) — the Pact below records exactly that, nothing more.
 */
public class OrdersClient {
    public enum Status { PLACED, PAID, SHIPPED, DELIVERED, CANCELLED, UNKNOWN }

    public record OrderView(String id, Status status, long totalMinor, String currency) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String token;

    public OrdersClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public Optional<OrderView> get(String id) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + id))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return Optional.empty();
        if (res.statusCode() != 200) throw new IOException("orders api " + res.statusCode());
        JsonNode n = json.readTree(res.body());
        Status s;
        try {
            s = Status.valueOf(n.path("status").asText());
        } catch (IllegalArgumentException e) {
            s = Status.UNKNOWN;
        }
        return Optional.of(new OrderView(n.path("id").asText(), s, n.path("total").path("amount").asLong(), n.path("total").path("currency").asText()));
    }
}
