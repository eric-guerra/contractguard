package com.ericguerra.contractguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * contractguard old.yaml new.yaml [--allow rule1,rule2] [--format text|markdown]
 * Exit 1 on breaking changes (CI gate), 0 otherwise.
 */
public final class Cli {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: contractguard <old-spec> <new-spec> [--allow rule,...] [--format text|markdown]");
            System.exit(2);
        }
        Set<String> allowed = Set.of();
        String format = "text";
        for (int i = 2; i < args.length - 1; i++) {
            if (args[i].equals("--allow")) allowed = Set.of(args[i + 1].split(","));
            if (args[i].equals("--format")) format = args[i + 1];
        }
        List<Change> changes = OpenApiDiff.compare(read(args[0]), read(args[1]));
        System.out.println(format.equals("markdown") ? markdown(changes, allowed) : text(changes));
        System.exit(gate(changes, allowed));
    }

    static JsonNode read(String path) throws IOException {
        return YAML.readTree(new File(path)); // YAML is a superset of JSON, so this reads both
    }

    static int gate(List<Change> changes, Set<String> allowed) {
        return changes.stream().anyMatch(c -> c.severity() == Change.Severity.BREAKING && !allowed.contains(c.rule())) ? 1 : 0;
    }

    static String text(List<Change> changes) {
        if (changes.isEmpty()) return "no changes";
        return changes.stream().map(Change::toString).collect(Collectors.joining("\n"));
    }

    /** For the PR comment. */
    static String markdown(List<Change> changes, Set<String> allowed) {
        long breaking = changes.stream().filter(c -> c.severity() == Change.Severity.BREAKING && !allowed.contains(c.rule())).count();
        StringBuilder sb = new StringBuilder();
        sb.append(breaking > 0 ? "### :x: " + breaking + " breaking API change(s)\n\n" : "### :white_check_mark: no breaking API changes\n\n");
        if (changes.isEmpty()) return sb.toString();
        sb.append("| | Rule | Where | Detail |\n|---|---|---|---|\n");
        for (Change c : changes) {
            String icon = switch (c.severity()) {
                case BREAKING -> allowed.contains(c.rule()) ? ":warning: (allowed)" : ":x:";
                case WARNING -> ":warning:";
                case INFO -> ":information_source:";
            };
            sb.append("| ").append(icon).append(" | `").append(c.rule()).append("` | `").append(c.location())
              .append("` | ").append(c.message().replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private Cli() {}
}
