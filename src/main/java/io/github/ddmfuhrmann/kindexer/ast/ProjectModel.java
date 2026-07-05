package io.github.ddmfuhrmann.kindexer.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The parsed working tree of the target repo: every {@code .java} file under its source roots,
 * with a shared symbol solver so call sites can be resolved to their declaring type. This is the
 * one and only source of raw material for the deterministic extractors — nothing here is derived
 * from an LLM. Files are held in a stable, path-sorted order so downstream extraction is
 * reproducible.
 */
public final class ProjectModel {

    private final Path repoRoot;
    private final List<ParsedFile> files;

    private ProjectModel(Path repoRoot, List<ParsedFile> files) {
        this.repoRoot = repoRoot;
        this.files = files;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    /** Path-sorted, immutable view of successfully parsed files. */
    public List<ParsedFile> files() {
        return files;
    }

    /** A parsed compilation unit with its repo-relative path (used for evidence anchors). */
    public record ParsedFile(String relPath, CompilationUnit cu) {}

    public static ProjectModel parse(Path repoRoot) {
        return parse(repoRoot, Set.of());
    }

    /** {@code excludeDirs} are extra directory names to prune (e.g. a nested/vendored project). */
    public static ProjectModel parse(Path repoRoot, Set<String> excludeDirs) {
        Set<String> ignored = new java.util.HashSet<>(IGNORED_DIRS);
        ignored.addAll(excludeDirs);
        List<Path> sourceRoots = discoverSourceRoots(repoRoot, ignored);

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(config);

        List<ParsedFile> parsed = new ArrayList<>();
        for (Path root : sourceRoots) {
            collectJavaFiles(root, ignored).forEach(file -> {
                try {
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    result.getResult().ifPresent(cu ->
                            parsed.add(new ParsedFile(relativize(repoRoot, file), cu)));
                } catch (IOException e) {
                    // Best-effort: an unreadable/unparseable file is skipped, never fatal.
                }
            });
        }
        parsed.sort(Comparator.comparing(ParsedFile::relPath));
        return new ProjectModel(repoRoot, List.copyOf(parsed));
    }

    /** Build output / dependency dirs to skip. Dot-directories (.git, .idea, .gradle, .knowledge-index,
     *  git worktrees under .claude, …) are pruned separately by {@link #pruned} — no need to list them. */
    private static final Set<String> IGNORED_DIRS = Set.of(
            "build", "target", "out", "bin", "node_modules");

    /**
     * Whether to skip descending into {@code dir}. Prunes the build/dep dirs above, plus <b>any
     * dot-directory</b> (name starting with {@code .}) — VCS/IDE/tool metadata and nested git
     * worktrees live there and never hold real Spring source. The walk root itself is never pruned,
     * so a repo located under a dotted path (e.g. {@code ~/.local/repo}) still parses.
     */
    private static boolean pruned(Path dir, Path start, Set<String> ignored) {
        if (dir.equals(start)) return false;
        Path name = dir.getFileName();
        if (name == null) return false;
        String n = name.toString();
        return ignored.contains(n) || n.startsWith(".");
    }

    /**
     * Every {@code src/main/java} and {@code src/test/java} anywhere in the tree — so a multi-module
     * repo (module-a/src/main/java, module-b/…) is handled, not just a single-module layout. Build
     * output and VCS/IDE/tool dirs are pruned. Falls back to the repo root when no standard root is
     * found, so an unconventional layout still yields something rather than nothing.
     */
    private static List<Path> discoverSourceRoots(Path repoRoot, Set<String> ignored) {
        List<Path> roots = new ArrayList<>();
        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (pruned(dir, repoRoot, ignored)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String s = dir.toString().replace('\\', '/');
                    if (s.endsWith("/src/main/java") || s.endsWith("/src/test/java")) {
                        roots.add(dir);
                        return FileVisitResult.SKIP_SUBTREE; // no nested source roots inside a root
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // fall through to the fallback
        }
        roots.sort(Comparator.naturalOrder());
        if (roots.isEmpty() && Files.isDirectory(repoRoot)) {
            roots.add(repoRoot);
        }
        return roots;
    }

    private static List<Path> collectJavaFiles(Path root, Set<String> ignored) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return pruned(dir, root, ignored)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static String relativize(Path repoRoot, Path file) {
        return repoRoot.relativize(file).toString().replace('\\', '/');
    }
}
