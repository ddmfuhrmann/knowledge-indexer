package io.github.ddmfuhrmann.kindexer.util;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared JSON facilities. Two writers, both with <b>alphabetically sorted keys</b> so that
 * serialization is stable across runs and JVMs:
 * <ul>
 *   <li>{@link #canonical()} — compact, no whitespace: the exact bytes fed to {@code ContentHash}.
 *   <li>{@link #pretty()} — 2-space indent with a stable line separator: what lands on disk
 *       (manifest, cache, enrichment I/O) so diffs are readable and deterministic.
 * </ul>
 * Determinism note: nothing here reads the wall clock; ordering is key-sorted, never
 * insertion- or hash-order dependent.
 */
public final class Json {

    private static final ObjectMapper MAPPER = buildMapper();

    private Json() {}

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        m.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        m.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return m;
    }

    /** Reuse the single configured mapper (parse, tree building). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Compact, key-sorted writer — canonical form for hashing. */
    public static ObjectWriter canonical() {
        return MAPPER.writer();
    }

    /** Indented, key-sorted, LF-only writer — for files on disk. */
    public static ObjectWriter pretty() {
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(indenter);
        pp.indentArraysWith(indenter);
        return MAPPER.writer(pp);
    }
}
