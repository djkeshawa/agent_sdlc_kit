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

final class Scratch implements AutoCloseable {
    final Path dir;

    private Scratch(Path dir) {
        this.dir = dir;
    }

    static Scratch of(String tree, String label) {
        try {
            Scratch s = new Scratch(Files.createTempDirectory("sdlc-check-" + label + "-").toRealPath());
            try {
                s.checkout(tree, List.of());
            } catch (IOException | RuntimeException e) {
                s.close();
                throw e;
            }
            return s;
        } catch (IOException e) {
            throw new UsageException("cannot create a scratch folder: " + e.getMessage());
        }
    }

    void write(String tree, List<String> paths) throws IOException {
        if (paths.isEmpty()) return;
        for (String path : paths) makeRoom(path);
        checkout(tree, paths);
    }

    private void makeRoom(String path) throws IOException {
        Path target = dir.resolve(path).normalize();
        if (!target.startsWith(dir)) throw new UsageException("a path that leaves the checkout: " + path);
        // Never through a link: a link on the way may lead out of the checkout.
        Path at = dir;
        Path rest = dir.relativize(target);
        for (int i = 0; i < rest.getNameCount() - 1; i++) {
            at = at.resolve(rest.getName(i));
            if (Files.isSymbolicLink(at) || Files.isRegularFile(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(at);
            if (!Files.exists(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        }
        if (Files.isSymbolicLink(target)) Files.delete(target);
        else if (Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> s = Files.walk(target)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private void checkout(String tree, List<String> paths) throws IOException {
        Path index = Files.createTempFile("sdlc-index", "");
        Files.delete(index);
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
