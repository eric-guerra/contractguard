package com.ericguerra.contractguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.*;

/**
 * Compares two OpenAPI 3 documents and reports what would break existing clients.
 *
 * Direction matters: a schema used in a <b>request</b> breaks clients when it gets stricter
 * (new required field, narrowed enum), while a <b>response</b> schema breaks clients when it gets
 * looser in what it promises (field removed, field became optional, enum widened).
 */
public final class OpenApiDiff {
    private enum Direction { REQUEST, RESPONSE }

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options");

    private final JsonNode oldDoc;
    private final JsonNode newDoc;
    private final List<Change> changes = new ArrayList<>();

    private OpenApiDiff(JsonNode oldDoc, JsonNode newDoc) {
        this.oldDoc = oldDoc;
        this.newDoc = newDoc;
    }

    public static List<Change> compare(JsonNode oldDoc, JsonNode newDoc) {
        OpenApiDiff d = new OpenApiDiff(oldDoc, newDoc);
        d.paths();
        d.changes.sort(Comparator.comparing(Change::severity).thenComparing(Change::location));
        return d.changes;
    }

    // ------------------------------------------------------------------ paths & operations

    private void paths() {
        Map<String, JsonNode> oldPaths = normalizedPaths(oldDoc);
        Map<String, JsonNode> newPaths = normalizedPaths(newDoc);
        for (var e : oldPaths.entrySet()) {
            JsonNode np = newPaths.get(e.getKey());
            if (np == null) {
                changes.add(Change.breaking(e.getKey(), "path-removed", "path removed"));
                continue;
            }
            for (String m : METHODS) {
                JsonNode oo = e.getValue().path(m);
                JsonNode no = np.path(m);
                String loc = m.toUpperCase() + " " + e.getKey();
                if (oo.isMissingNode()) {
                    if (!no.isMissingNode()) changes.add(Change.info(loc, "operation-added", "new operation"));
                } else if (no.isMissingNode()) {
                    changes.add(Change.breaking(loc, "operation-removed", "operation removed"));
                } else {
                    operation(loc, oo, no);
                }
            }
        }
        for (String p : newPaths.keySet()) {
            if (!oldPaths.containsKey(p)) changes.add(Change.info(p, "path-added", "new path"));
        }
    }

    /** /orders/{id} and /orders/{orderId} are the same route; compare on a normalized template. */
    private static Map<String, JsonNode> normalizedPaths(JsonNode doc) {
        Map<String, JsonNode> out = new TreeMap<>();
        doc.path("paths").fields().forEachRemaining(e -> out.put(e.getKey().replaceAll("\\{[^}]+}", "{}"), e.getValue()));
        return out;
    }

    private void operation(String loc, JsonNode oldOp, JsonNode newOp) {
        if (newOp.path("deprecated").asBoolean() && !oldOp.path("deprecated").asBoolean())
            changes.add(Change.warning(loc, "operation-deprecated", "operation marked deprecated"));
        parameters(loc, oldOp, newOp);
        requestBody(loc, oldOp, newOp);
        responses(loc, oldOp, newOp);
        security(loc, oldOp, newOp);
    }

    private void parameters(String loc, JsonNode oldOp, JsonNode newOp) {
        Map<String, JsonNode> op = params(oldOp, oldDoc);
        Map<String, JsonNode> np = params(newOp, newDoc);
        for (var e : np.entrySet()) {
            JsonNode o = op.get(e.getKey());
            boolean required = e.getValue().path("required").asBoolean() || e.getKey().startsWith("path:");
            if (o == null) {
                if (required) changes.add(Change.breaking(loc, "param-required-added", "new required parameter " + e.getKey()));
                else changes.add(Change.info(loc, "param-added", "new optional parameter " + e.getKey()));
            } else {
                if (required && !o.path("required").asBoolean() && !e.getKey().startsWith("path:"))
                    changes.add(Change.breaking(loc, "param-became-required", e.getKey() + " is now required"));
                schema(loc + " param " + e.getKey(), resolve(o.path("schema"), oldDoc), resolve(e.getValue().path("schema"), newDoc), Direction.REQUEST, new HashSet<>());
            }
        }
        for (String k : op.keySet()) {
            if (!np.containsKey(k)) changes.add(Change.breaking(loc, "param-removed", "parameter " + k + " removed (clients sending it may now get 400)"));
        }
    }

    private static Map<String, JsonNode> params(JsonNode op, JsonNode doc) {
        Map<String, JsonNode> out = new TreeMap<>();
        int pathIndex = 0;
        for (JsonNode p : op.path("parameters")) {
            JsonNode r = resolve(p, doc);
            // Path params are positional on the wire, so a rename ({id} → {orderId}) is invisible to clients.
            String key = r.path("in").asText().equals("path") ? "path:#" + pathIndex++ : r.path("in").asText() + ":" + r.path("name").asText();
            out.put(key, r);
        }
        return out;
    }

    private void requestBody(String loc, JsonNode oldOp, JsonNode newOp) {
        JsonNode ob = oldOp.path("requestBody");
        JsonNode nb = newOp.path("requestBody");
        if (ob.isMissingNode() && !nb.isMissingNode() && nb.path("required").asBoolean()) {
            changes.add(Change.breaking(loc, "request-body-required", "request body is now required"));
            return;
        }
        if (ob.isMissingNode() || nb.isMissingNode()) return;
        JsonNode os = jsonSchema(ob, oldDoc);
        JsonNode ns = jsonSchema(nb, newDoc);
        if (!os.isMissingNode() && ns.isMissingNode())
            changes.add(Change.breaking(loc, "media-type-removed", "application/json request body no longer accepted"));
        else if (!os.isMissingNode())
            schema(loc + " request", os, ns, Direction.REQUEST, new HashSet<>());
    }

    private void responses(String loc, JsonNode oldOp, JsonNode newOp) {
        JsonNode or = oldOp.path("responses");
        JsonNode nr = newOp.path("responses");
        or.fieldNames().forEachRemaining(code -> {
            JsonNode o = or.path(code);
            JsonNode n = nr.path(code);
            if (n.isMissingNode()) {
                Change.Severity sev = code.startsWith("2") ? Change.Severity.BREAKING : Change.Severity.WARNING;
                changes.add(new Change(sev, loc, "response-removed", "response " + code + " removed"));
                return;
            }
            JsonNode os = jsonSchema(o, oldDoc);
            JsonNode ns = jsonSchema(n, newDoc);
            if (!os.isMissingNode() && !ns.isMissingNode())
                schema(loc + " " + code, os, ns, Direction.RESPONSE, new HashSet<>());
        });
        nr.fieldNames().forEachRemaining(code -> {
            if (or.path(code).isMissingNode() && code.startsWith("2"))
                changes.add(Change.warning(loc, "success-response-added", "new success status " + code + " (clients may only handle the old one)"));
        });
    }

    private void security(String loc, JsonNode oldOp, JsonNode newOp) {
        JsonNode os = oldOp.has("security") ? oldOp.path("security") : oldDoc.path("security");
        JsonNode ns = newOp.has("security") ? newOp.path("security") : newDoc.path("security");
        if (isEmptySecurity(os) && !isEmptySecurity(ns))
            changes.add(Change.breaking(loc, "auth-added", "operation now requires authentication"));
        Set<String> oldScopes = scopes(os);
        Set<String> newScopes = scopes(ns);
        newScopes.removeAll(oldScopes);
        if (!newScopes.isEmpty() && !isEmptySecurity(os))
            changes.add(Change.breaking(loc, "scope-added", "now requires scopes " + newScopes));
    }

    private static boolean isEmptySecurity(JsonNode s) {
        if (s.isMissingNode() || s.isEmpty()) return true;
        for (JsonNode req : s) if (req.isEmpty()) return true; // `- {}` means anonymous is allowed
        return false;
    }

    private static Set<String> scopes(JsonNode s) {
        Set<String> out = new TreeSet<>();
        for (JsonNode req : s) req.fields().forEachRemaining(e -> e.getValue().forEach(v -> out.add(e.getKey() + ":" + v.asText())));
        return out;
    }

    // ------------------------------------------------------------------ schemas

    private void schema(String loc, JsonNode o, JsonNode n, Direction dir, Set<String> seen) {
        if (o.isMissingNode() || n.isMissingNode()) return;
        String key = loc + "|" + o.hashCode() + "|" + n.hashCode();
        if (!seen.add(key) || seen.size() > 5000) return; // recursive schemas

        String ot = type(o), nt = type(n);
        if (!ot.isEmpty() && !nt.isEmpty() && !ot.equals(nt) && !(ot.equals("integer") && nt.equals("number") && dir == Direction.REQUEST)) {
            changes.add(Change.breaking(loc, "type-changed", "type " + ot + " → " + nt));
            return;
        }
        if (n.path("nullable").asBoolean() && !o.path("nullable").asBoolean() && dir == Direction.RESPONSE)
            changes.add(Change.breaking(loc, "became-nullable", "response field may now be null"));

        enums(loc, o, n, dir);
        constraints(loc, o, n, dir);

        if ("object".equals(nt) || n.has("properties")) properties(loc, o, n, dir, seen);
        if ("array".equals(nt)) schema(loc + "[]", resolve(o.path("items"), oldDoc), resolve(n.path("items"), newDoc), dir, seen);
    }

    private void properties(String loc, JsonNode o, JsonNode n, Direction dir, Set<String> seen) {
        Set<String> oldReq = names(o.path("required"));
        Set<String> newReq = names(n.path("required"));
        JsonNode op = o.path("properties");
        JsonNode np = n.path("properties");

        op.fieldNames().forEachRemaining(name -> {
            String at = loc + "." + name;
            if (np.path(name).isMissingNode()) {
                if (dir == Direction.RESPONSE) changes.add(Change.breaking(at, "response-field-removed", "field removed from response"));
                else if (!n.path("additionalProperties").asBoolean(true))
                    changes.add(Change.breaking(at, "request-field-rejected", "field removed and additionalProperties=false"));
                else changes.add(Change.warning(at, "request-field-ignored", "field removed from request; it will be silently ignored"));
                return;
            }
            if (dir == Direction.RESPONSE && oldReq.contains(name) && !newReq.contains(name))
                changes.add(Change.breaking(at, "response-field-optional", "response field no longer guaranteed"));
            schema(at, resolve(op.path(name), oldDoc), resolve(np.path(name), newDoc), dir, seen);
        });

        for (String name : newReq) {
            if (dir == Direction.REQUEST && !oldReq.contains(name)) {
                boolean hasDefault = resolve(np.path(name), newDoc).has("default");
                if (!hasDefault) changes.add(Change.breaking(loc + "." + name, "request-field-required", "new required request field"));
            }
        }
        np.fieldNames().forEachRemaining(name -> {
            if (op.path(name).isMissingNode() && !(dir == Direction.REQUEST && newReq.contains(name)))
                changes.add(Change.info(loc + "." + name, "field-added", "new " + (dir == Direction.REQUEST ? "optional request" : "response") + " field"));
        });
    }

    private void enums(String loc, JsonNode o, JsonNode n, Direction dir) {
        if (!o.has("enum") && !n.has("enum")) return;
        Set<String> ov = names(o.path("enum"));
        Set<String> nv = names(n.path("enum"));
        if (!o.has("enum")) {
            if (dir == Direction.REQUEST) changes.add(Change.breaking(loc, "enum-added", "value now restricted to " + nv));
            return;
        }
        Set<String> removed = new TreeSet<>(ov);
        removed.removeAll(nv);
        Set<String> added = new TreeSet<>(nv);
        added.removeAll(ov);
        if (!n.has("enum")) added = Set.of("<any>");
        if (dir == Direction.REQUEST && !removed.isEmpty())
            changes.add(Change.breaking(loc, "enum-value-removed", "request no longer accepts " + removed));
        if (dir == Direction.RESPONSE && !added.isEmpty())
            changes.add(Change.breaking(loc, "enum-value-added", "response may now return " + added + "; strict clients will fail to parse"));
        if (dir == Direction.REQUEST && !added.isEmpty() && n.has("enum"))
            changes.add(Change.info(loc, "enum-value-added", "request also accepts " + added));
    }

    private void constraints(String loc, JsonNode o, JsonNode n, Direction dir) {
        if (dir != Direction.REQUEST) return;
        tighter(loc, "maxLength", o, n, true);
        tighter(loc, "maximum", o, n, true);
        tighter(loc, "maxItems", o, n, true);
        tighter(loc, "minLength", o, n, false);
        tighter(loc, "minimum", o, n, false);
        if (!o.has("pattern") && n.has("pattern") || o.has("pattern") && n.has("pattern") && !o.path("pattern").equals(n.path("pattern")))
            changes.add(Change.warning(loc, "pattern-changed", "pattern is now " + n.path("pattern").asText()));
    }

    /** Request constraint got stricter: lower max or higher min. */
    private void tighter(String loc, String kw, JsonNode o, JsonNode n, boolean isMax) {
        if (!n.has(kw)) return;
        double nv = n.path(kw).asDouble();
        if (!o.has(kw)) {
            changes.add(Change.breaking(loc, "constraint-added", kw + " = " + n.path(kw).asText() + " added"));
            return;
        }
        double ov = o.path(kw).asDouble();
        if (isMax ? nv < ov : nv > ov)
            changes.add(Change.breaking(loc, "constraint-tightened", kw + " " + o.path(kw).asText() + " → " + n.path(kw).asText()));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode jsonSchema(JsonNode bodyOrResponse, JsonNode doc) {
        JsonNode resolved = resolve(bodyOrResponse, doc);
        JsonNode content = resolved.path("content");
        for (String mt : List.of("application/json", "application/problem+json", "*/*")) {
            if (content.has(mt)) return resolve(content.path(mt).path("schema"), doc);
        }
        return MissingNode.getInstance();
    }

    /** Follow local $ref (#/components/...), and flatten allOf into one object schema. */
    static JsonNode resolve(JsonNode n, JsonNode doc) {
        int hops = 0;
        while (n != null && n.has("$ref") && doc != null && hops++ < 32) {
            String ref = n.path("$ref").asText();
            if (!ref.startsWith("#/")) return n;
            JsonNode cur = doc;
            for (String part : ref.substring(2).split("/")) cur = cur.path(part.replace("~1", "/").replace("~0", "~"));
            n = cur;
        }
        if (n != null && n.has("allOf") && doc != null) {
            com.fasterxml.jackson.databind.node.ObjectNode merged = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            merged.put("type", "object");
            com.fasterxml.jackson.databind.node.ObjectNode props = merged.putObject("properties");
            com.fasterxml.jackson.databind.node.ArrayNode req = merged.putArray("required");
            for (JsonNode part : n.path("allOf")) {
                JsonNode p = resolve(part, doc);
                p.path("properties").fields().forEachRemaining(e -> props.set(e.getKey(), e.getValue()));
                p.path("required").forEach(req::add);
            }
            return merged;
        }
        return n == null ? MissingNode.getInstance() : n;
    }

    private static String type(JsonNode s) {
        if (s.has("type")) return s.path("type").asText();
        if (s.has("properties")) return "object";
        if (s.has("items")) return "array";
        return "";
    }

    private static Set<String> names(JsonNode arr) {
        Set<String> out = new TreeSet<>();
        arr.forEach(v -> out.add(v.asText()));
        return out;
    }
}
