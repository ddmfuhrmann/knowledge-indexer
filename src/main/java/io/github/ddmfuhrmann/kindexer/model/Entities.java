package io.github.ddmfuhrmann.kindexer.model;

import java.util.List;

/**
 * Deterministic ER material (Extractor 1). One {@link EntityModel} per persistent type, with its
 * table, columns and relationships read straight from the annotations — no interpretation.
 */
public final class Entities {

    private Entities() {}

    public record EntityModel(
            String name,
            String kind,      // ENTITY | EMBEDDABLE | MAPPED_SUPERCLASS
            String table,     // @Table(name) or default = simple name
            String file,
            int line,
            List<FieldModel> fields,
            List<RelationModel> relations) {}

    public record FieldModel(
            String name,
            String type,
            boolean id,
            boolean generated,
            String column,          // @Column(name) or null when defaulted
            Boolean nullable,
            Boolean unique,
            Integer length,
            String enumConverter) {} // simple name of @Convert converter, else null

    public record RelationModel(
            String field,
            String kind,     // OneToOne | OneToMany | ManyToOne | ManyToMany
            String target,   // target simple type name
            String mappedBy, // set on the inverse side
            String joinColumn,
            boolean owning) {}
}
