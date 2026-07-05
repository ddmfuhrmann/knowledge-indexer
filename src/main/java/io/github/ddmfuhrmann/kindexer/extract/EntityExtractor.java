package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.Entities.EntityModel;
import io.github.ddmfuhrmann.kindexer.model.Entities.FieldModel;
import io.github.ddmfuhrmann.kindexer.model.Entities.RelationModel;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Extractor 1 — JPA entities to an ER model. Pure AST: reads {@code @Entity}/{@code @Embeddable}/
 * {@code @MappedSuperclass}, {@code @Table}, {@code @Id}, {@code @Column}, {@code @Convert} and the
 * four relationship annotations. Scalar fields and relationships are separated; the dual-column FK
 * pattern (an {@code Integer xId} scalar plus a read-only {@code @ManyToOne} on the same column) is
 * preserved as both a field and a relation, exactly as written.
 */
public final class EntityExtractor {

    private static final List<String> RELATION_ANNOTATIONS =
            List.of("OneToOne", "OneToMany", "ManyToOne", "ManyToMany");

    public List<EntityModel> extract(ProjectModel project) {
        List<EntityModel> out = new ArrayList<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            for (ClassOrInterfaceDeclaration type :
                    pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String kind = entityKind(type);
                if (kind == null) {
                    continue;
                }
                out.add(toModel(type, kind, pf.relPath()));
            }
        }
        out.sort(Comparator.comparing(EntityModel::name));
        return out;
    }

    private static String entityKind(ClassOrInterfaceDeclaration type) {
        if (Ast.has(type, "Entity")) return "ENTITY";
        if (Ast.has(type, "Embeddable")) return "EMBEDDABLE";
        if (Ast.has(type, "MappedSuperclass")) return "MAPPED_SUPERCLASS";
        return null;
    }

    private EntityModel toModel(ClassOrInterfaceDeclaration type, String kind, String file) {
        String name = type.getNameAsString();
        String table = Ast.annotation(type, "Table")
                .map(Ast::stringValue)
                .filter(s -> s != null && !s.isBlank())
                .orElse(name);

        List<FieldModel> fields = new ArrayList<>();
        List<RelationModel> relations = new ArrayList<>();

        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic()) {
                continue;
            }
            String relKind = relationKind(field);
            for (VariableDeclarator var : field.getVariables()) {
                if (relKind != null) {
                    relations.add(toRelation(field, var, relKind));
                } else {
                    fields.add(toField(field, var));
                }
            }
        }

        fields.sort(Comparator.comparing(FieldModel::name));
        relations.sort(Comparator.comparing(RelationModel::field));
        return new EntityModel(name, kind, table, file, Ast.line(type), fields, relations);
    }

    private static String relationKind(FieldDeclaration field) {
        for (String rel : RELATION_ANNOTATIONS) {
            if (Ast.has(field, rel)) {
                return rel;
            }
        }
        return null;
    }

    private FieldModel toField(FieldDeclaration field, VariableDeclarator var) {
        String column = Ast.annotation(field, "Column").map(Ast::stringValue).orElse(null);
        AnnotationExpr columnAnn = Ast.annotation(field, "Column").orElse(null);
        Boolean nullable = columnAnn == null ? null : Ast.booleanMember(columnAnn, "nullable");
        Boolean unique = columnAnn == null ? null : Ast.booleanMember(columnAnn, "unique");
        Integer length = columnAnn == null ? null : Ast.intMember(columnAnn, "length");
        String converter = Ast.annotation(field, "Convert")
                .map(a -> Ast.member(a, "converter"))
                .orElse(null);
        return new FieldModel(
                var.getNameAsString(),
                var.getType().asString(),
                Ast.has(field, "Id"),
                Ast.has(field, "GeneratedValue"),
                column,
                nullable,
                unique,
                length,
                converter);
    }

    private RelationModel toRelation(FieldDeclaration field, VariableDeclarator var, String kind) {
        AnnotationExpr ann = field.getAnnotationByName(kind).orElseThrow();
        String mappedBy = Ast.member(ann, "mappedBy");
        String joinColumn = Ast.annotation(field, "JoinColumn").map(Ast::stringValue).orElse(null);
        return new RelationModel(
                var.getNameAsString(),
                kind,
                targetType(var.getType()),
                mappedBy,
                joinColumn,
                mappedBy == null || mappedBy.isBlank());
    }

    /** Element type for a collection ({@code List<Address> → Address}), else the type itself. */
    private static String targetType(Type type) {
        if (type instanceof ClassOrInterfaceType cit) {
            Optional<com.github.javaparser.ast.NodeList<Type>> args = cit.getTypeArguments();
            if (args.isPresent() && !args.get().isEmpty()) {
                return targetType(args.get().get(args.get().size() - 1));
            }
            return cit.getNameAsString();
        }
        return type.asString();
    }
}
