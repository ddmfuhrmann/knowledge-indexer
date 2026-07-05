package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.model.Migrations.MigrationFk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Best-effort reader of SQL migrations. SQL is data, not code structure, so light pattern matching
 * is acceptable here (JavaParser is reserved for Java). Scans the conventional Flyway location for
 * {@code REFERENCES}/{@code FOREIGN KEY} declarations. If the directory is missing it simply
 * returns nothing — the whole extractor is optional per the spec.
 */
public final class MigrationReader {

    private static final Pattern INLINE_REF = Pattern.compile(
            "(?is)`?(\\w+)`?\\s+[\\w()]+[^,]*?references\\s+`?(\\w+)`?\\s*\\(\\s*`?(\\w+)`?\\s*\\)");
    private static final Pattern FK_CONSTRAINT = Pattern.compile(
            "(?is)foreign\\s+key\\s*\\(\\s*`?(\\w+)`?\\s*\\)\\s*references\\s+`?(\\w+)`?\\s*\\(\\s*`?(\\w+)`?\\s*\\)");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?`?(\\w+)`?");

    public List<MigrationFk> read(Path repoRoot) {
        Path dir = repoRoot.resolve("src/main/resources/db/migration");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<MigrationFk> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .sorted()
                    .forEach(p -> parseFile(repoRoot, p, out));
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing(MigrationFk::file)
                .thenComparing(MigrationFk::table)
                .thenComparing(MigrationFk::column));
        return out;
    }

    private void parseFile(Path repoRoot, Path file, List<MigrationFk> out) {
        String sql;
        try {
            sql = Files.readString(file);
        } catch (IOException e) {
            return;
        }
        String rel = repoRoot.relativize(file).toString().replace('\\', '/');
        String currentTable = "unknown";
        Matcher ct = CREATE_TABLE.matcher(sql);
        if (ct.find()) {
            currentTable = ct.group(1);
        }
        Matcher inline = INLINE_REF.matcher(sql);
        while (inline.find()) {
            out.add(new MigrationFk(currentTable, inline.group(1), inline.group(2), inline.group(3), rel));
        }
        Matcher fk = FK_CONSTRAINT.matcher(sql);
        while (fk.find()) {
            out.add(new MigrationFk(currentTable, fk.group(1), fk.group(2), fk.group(3), rel));
        }
    }
}
