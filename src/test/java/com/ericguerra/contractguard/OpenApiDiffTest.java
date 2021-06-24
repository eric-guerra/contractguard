package com.ericguerra.contractguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDiffTest {
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static JsonNode y(String s) throws Exception { return YAML.readTree(s); }

    static List<String> rules(List<Change> cs, Change.Severity sev) {
        return cs.stream().filter(c -> c.severity() == sev).map(Change::rule).sorted().toList();
    }

    static final String BASE = """
        openapi: 3.0.3
        info: { title: orders, version: '1' }
        paths:
          /orders/{id}:
            get:
              parameters:
                - { name: id, in: path, required: true, schema: { type: string } }
                - { name: expand, in: query, schema: { type: string } }
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema: { $ref: '#/components/schemas/Order' }
                '404': { description: missing }
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema: { $ref: '#/components/schemas/NewOrder' }
              responses:
                '201': { description: created }
        components:
          schemas:
            Order:
              type: object
              required: [id, status, total]
              properties:
                id: { type: string }
                status: { type: string, enum: [PLACED, SHIPPED] }
                total: { type: integer }
                note: { type: string }
            NewOrder:
              type: object
              required: [customerId]
              properties:
                customerId: { type: string, maxLength: 40 }
                channel: { type: string, enum: [WEB, APP, STORE] }
                giftMessage: { type: string }
        """;

    List<Change> diff(String from, String to) throws Exception {
        return OpenApiDiff.compare(y(BASE.replace(from, from)), y(BASE.replace(from, to)));
    }

    @Test
    void identicalSpecsHaveNoChanges() throws Exception {
        assertThat(OpenApiDiff.compare(y(BASE), y(BASE))).isEmpty();
    }

    @Test
    void renamingAPathParameterIsNotARouteChange() throws Exception {
        String renamed = BASE.replace("/orders/{id}", "/orders/{orderId}").replace("name: id, in: path", "name: orderId, in: path");
        List<Change> cs = OpenApiDiff.compare(y(BASE), y(renamed));
        assertThat(cs).isEmpty();
        assertThat(cs).noneMatch(c -> c.rule().equals("path-removed"));
    }

    @Test
    void removingResponseFieldOrGuaranteeBreaks() throws Exception {
        assertThat(rules(diff("        note: { type: string }\n", ""), Change.Severity.BREAKING)).containsExactly("response-field-removed");
        assertThat(rules(diff("required: [id, status, total]", "required: [id, status]"), Change.Severity.BREAKING)).containsExactly("response-field-optional");
    }

    @Test
    void enumDirectionMatters() throws Exception {
        // Response enum widened: old strict clients can't parse the new value.
        assertThat(rules(diff("enum: [PLACED, SHIPPED]", "enum: [PLACED, SHIPPED, RETURNED]"), Change.Severity.BREAKING)).containsExactly("enum-value-added");
        // Request enum widened: fine. Narrowed: breaks clients that send the removed value.
        assertThat(rules(diff("enum: [WEB, APP, STORE]", "enum: [WEB, APP, STORE, KIOSK]"), Change.Severity.BREAKING)).isEmpty();
        assertThat(rules(diff("enum: [WEB, APP, STORE]", "enum: [WEB, APP]"), Change.Severity.BREAKING)).containsExactly("enum-value-removed");
    }

    @Test
    void newRequiredRequestFieldBreaksUnlessDefaulted() throws Exception {
        assertThat(rules(diff("required: [customerId]", "required: [customerId, channel]"), Change.Severity.BREAKING)).containsExactly("request-field-required");
        String defaulted = BASE.replace("required: [customerId]", "required: [customerId, channel]")
                .replace("channel: { type: string, enum: [WEB, APP, STORE] }", "channel: { type: string, enum: [WEB, APP, STORE], default: WEB }");
        assertThat(rules(OpenApiDiff.compare(y(BASE), y(defaulted)), Change.Severity.BREAKING)).isEmpty();
    }

    @Test
    void tighterRequestConstraintsBreakLooserDoNot() throws Exception {
        assertThat(rules(diff("maxLength: 40", "maxLength: 20"), Change.Severity.BREAKING)).containsExactly("constraint-tightened");
        assertThat(rules(diff("maxLength: 40", "maxLength: 64"), Change.Severity.BREAKING)).isEmpty();
    }

    @Test
    void typeChangesAreBreakingExceptSafeWidening() throws Exception {
        assertThat(rules(diff("total: { type: integer }", "total: { type: string }"), Change.Severity.BREAKING)).containsExactly("type-changed");
        assertThat(rules(diff("total: { type: integer }", "total: { type: number }"), Change.Severity.BREAKING))
                .as("response int→number breaks clients parsing ints").containsExactly("type-changed");
    }

    @Test
    void operationAndResponseRemovals() throws Exception {
        String noGet = BASE.replace("  /orders/{id}:\n    get:", "  /orders/{id}:\n    delete:");
        assertThat(noGet).isNotEqualTo(BASE);
        assertThat(rules(OpenApiDiff.compare(y(BASE), y(noGet)), Change.Severity.BREAKING)).contains("operation-removed");
        assertThat(rules(diff("'404': { description: missing }", "'410': { description: gone }"), Change.Severity.WARNING)).containsExactly("response-removed");
    }

    @Test
    void addingAuthOrScopesBreaks() throws Exception {
        String secured = BASE.replace("paths:", """
                security:
                  - oauth: [orders:read]
                paths:""".stripIndent());
        assertThat(rules(OpenApiDiff.compare(y(BASE), y(secured)), Change.Severity.BREAKING)).containsOnly("auth-added");
        String moreScopes = secured.replace("[orders:read]", "[orders:read, orders:admin]");
        assertThat(rules(OpenApiDiff.compare(y(secured), y(moreScopes)), Change.Severity.BREAKING)).containsOnly("scope-added");
    }

    @Test
    void additiveChangesAreInfoOnly() throws Exception {
        String added = BASE.replace("        note: { type: string }\n", "        note: { type: string }\n        eta: { type: string }\n")
                .replace("components:", "  /health:\n    get:\n      responses:\n        '200': { description: ok }\ncomponents:");
        assertThat(added).contains("eta:");
        List<Change> cs = OpenApiDiff.compare(y(BASE), y(added));
        assertThat(rules(cs, Change.Severity.BREAKING)).isEmpty();
        assertThat(rules(cs, Change.Severity.INFO)).contains("field-added", "path-added");
    }

    @Test
    void bundledSpecsShowTheReleaseThatWasBlocked() throws Exception {
        List<Change> cs = OpenApiDiff.compare(Cli.read("specs/orders-v1.yaml"), Cli.read("specs/orders-v2.yaml"));
        assertThat(Cli.gate(cs, Set.of())).isEqualTo(1);
        assertThat(rules(cs, Change.Severity.BREAKING)).contains("response-field-removed", "enum-value-added");
        assertThat(Cli.markdown(cs, Set.of())).startsWith("### :x:");
    }
}
