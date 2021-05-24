package com.ericguerra.contractguard;

/** One difference between two API versions, classified by whether existing clients survive it. */
public record Change(Severity severity, String location, String rule, String message) {
    public enum Severity { BREAKING, WARNING, INFO }

    public static Change breaking(String location, String rule, String message) {
        return new Change(Severity.BREAKING, location, rule, message);
    }

    public static Change warning(String location, String rule, String message) {
        return new Change(Severity.WARNING, location, rule, message);
    }

    public static Change info(String location, String rule, String message) {
        return new Change(Severity.INFO, location, rule, message);
    }

    @Override
    public String toString() {
        return String.format("%-8s %-28s %s  (%s)", severity, rule, message, location);
    }
}
