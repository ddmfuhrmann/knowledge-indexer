package io.github.ddmfuhrmann.kindexer.model;

/**
 * Best-effort migration facts (Flyway {@code V*.sql} or a Liquibase changelog). Only foreign keys
 * are surfaced, to complement the annotation-derived ER with DB-declared relationships. Absent
 * migrations yield an empty list — never an error.
 */
public final class Migrations {

    private Migrations() {}

    public record MigrationFk(
            String table,
            String column,
            String refTable,
            String refColumn,
            String file) {}
}
