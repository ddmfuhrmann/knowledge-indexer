package io.github.ddmfuhrmann.kindexer.git;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Git facts about the target repo, read once. The commit timestamp — not the wall clock — is
 * used as {@code generatedAt}, so two runs against the same commit produce byte-identical
 * manifests (the determinism guarantee). If the path is not a git repo, fields degrade to fixed
 * sentinels rather than anything time-varying.
 */
public record GitInfo(String commit, String generatedAt, String commitMessage) {

    public static GitInfo read(Path repo) {
        String commit = run(repo, "rev-parse", "HEAD");
        String when = run(repo, "show", "-s", "--format=%cI", "HEAD");
        String message = runMultiline(repo, "show", "-s", "--format=%B", "HEAD");
        return new GitInfo(
                commit == null ? "uncommitted" : commit.trim(),
                when == null ? "1970-01-01T00:00:00Z" : when.trim(),
                message == null ? "" : message.strip());
    }

    private static String run(Path repo, String... args) {
        String out = runMultiline(repo, args);
        if (out == null) {
            return null;
        }
        int nl = out.indexOf('\n');
        return nl >= 0 ? out.substring(0, nl) : out;
    }

    /** Full multi-line stdout (e.g. a commit message body), or null on failure. */
    private static String runMultiline(Path repo, String... args) {
        try {
            String[] cmd = new String[args.length + 3];
            cmd[0] = "git";
            cmd[1] = "-C";
            cmd[2] = repo.toString();
            System.arraycopy(args, 0, cmd, 3, args.length);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n"));
            }
            return p.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
}
