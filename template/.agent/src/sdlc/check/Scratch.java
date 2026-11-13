package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.UsageException;
import sdlc.util.Proc;

/**
 * Throw-away checkouts outside the repository, written with git from a tree and never from the
 * working tree's files. The repository's working tree and index are not touched.
 */
final class Scratch implements AutoCloseable {
    final Path dir;

    private Scratch(Path dir) {
        this.dir = dir;
    }

    /** A new folder under the system's temporary folder holding every file of the tree. */
    static Scratch of(String tree, String label) {
        try {
            Scratch s = new Scratch(Files.createTempDirectory("sdlc-check-" + label + "-").toRealPath());
            try {
                s.write(tree, List.of());
            } catch (IOException | RuntimeException e) {
                s.close();
                throw e;
            }
            return s;
        } catch (IOException e) {
            throw new UsageException("cannot create a scratch folder: " + e.getMessage());
        }
    }

    /** Writes the given paths of a tree over what is here (all paths when the list is empty). */
    void write(String tree, List<String> paths) throws IOException {
        Path index = Files.createTempFile("sdlc-index", "");
        Files.delete(index); // git creates it
        try {
            Map<String, String> env = Map.of("GIT_INDEX_FILE", index.toString());
            Proc read = Proc.runWithEnv(Ctx.root, env, null, 300, "git", "read-tree", tree);
            if (!read.ok()) throw new UsageException("git read-tree " + tree + ": " + read.err().strip());
            List<String> cmd = new ArrayList<>(List.of("git", "checkout-index", "-f", "--prefix=" + dir.toString().replace('\\', '/') + "/"));
            if (paths.isEmpty()) {
                cmd.add("-a");
            } else {
                cmd.add("--");
                cmd.addAll(paths);
            }
            Proc out = Proc.runWithEnv(Ctx.root, env, null, 600, cmd.toArray(new String[0]));
            if (!out.ok()) throw new UsageException("git checkout-index: " + out.err().strip());
        } finally {
            Files.deleteIfExists(index);
            Files.deleteIfExists(Path.of(index + ".lock"));
        }
    }

    @Override
    public void close() {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isSymbolicLink(p)) p.toFile().setWritable(true);
                Files.deleteIfExists(p); // a link is removed, never followed
            }
        } catch (IOException e) {
            System.err.println("[sdlc] could not remove " + dir + ": " + e.getMessage());
        }
    }
}
