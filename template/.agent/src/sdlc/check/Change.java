package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sdlc.core.Ctx;
import sdlc.core.Git;
import sdlc.core.Trusted;
import sdlc.core.UsageException;
import sdlc.util.Proc;

public final class Change {
    private static String emptyTree() {
        Proc p = Proc.runWithInput(Ctx.root, "", 30, "git", "hash-object", "-t", "tree", "--stdin");
        if (!p.ok() || p.out().isBlank()) throw new UsageException("git hash-object failed: " + p.err().strip());
        return p.out().strip();
    }

    public record File(String status, String path) {}

    public final String base;
    public final String baseName;
    public final String head;
    public final List<File> files;

    private Change(String base, String baseName, String head, List<File> files) {
        this.base = base;
        this.baseName = baseName;
        this.head = head;
        this.files = List.copyOf(files);
    }

    public static Change of(String baseOption) {
        if (!Proc.run(Ctx.root, "git", "rev-parse", "--git-dir").ok()) throw new UsageException("sdlc check needs a git repository: " + Ctx.root + " is not one");
        String[] base = base(baseOption);
        String head = snapshot();
        return new Change(base[0], base[1], head, diff(base[0], head));
    }

    public static Change ofCommit(String commit) {
        String sha = rev(commit);
        if (sha.isEmpty()) throw new UsageException(commit + " is not a commit of this repository");
        Proc parent = Proc.run(Ctx.root, "git", "rev-parse", "-q", "--verify", sha + "^1^{commit}");
        String base = parent.ok() ? parent.out().strip() : emptyTree();
        String head = Proc.run(Ctx.root, "git", "rev-parse", sha + "^{tree}").out().strip();
        return new Change(base, "its parent", head, diff(base, head));
    }

    private static List<File> diff(String base, String head) {
        List<File> files = new ArrayList<>();
        Proc diff = Proc.run(Ctx.root, "git", "diff", "--name-status", "--no-renames", "-z", base, head);
        if (!diff.ok()) throw new UsageException("git diff failed: " + diff.err().strip());
        String[] parts = diff.out().split("\0");
        for (int i = 0; i + 1 < parts.length; i += 2) files.add(new File(parts[i].substring(0, 1), parts[i + 1]));
        return files;
    }

    private static String[] base(String option) {
        boolean hasHead = Git.refExists("HEAD");
        if (option != null && !option.isBlank()) {
            if (option.startsWith("-") || !Git.refExists(option + "^{commit}")) throw new UsageException("--base " + option + " is not a commit of this repository");
            return new String[] {hasHead ? mergeBase(option) : rev(option), "--base " + option};
        }
        if (!hasHead) return new String[] {emptyTree(), "an empty repository (no commit yet)"};
        if (Trusted.inCi()) {
            String ref = Trusted.baseRef();
            if (!Git.refExists(ref + "^{commit}")) throw new UsageException("the base branch " + Trusted.shortName(ref) + " is not in this checkout: fetch it first (git fetch origin)");
            if (!Proc.run(Ctx.root, "git", "merge-base", ref, "HEAD").ok()) throw new UsageException("this checkout has no commit in common with " + Trusted.shortName(ref) + " (a shallow clone?): fetch more history");
            return new String[] {mergeBase(ref), Trusted.shortName(ref)};
        }
        List<String> candidates = new ArrayList<>();
        if (Files.isRegularFile(Ctx.root.resolve(".agent/sdlc.yml"))) candidates.add("origin/" + Ctx.cfg.baseBranch());
        Proc remoteHead = Proc.run(Ctx.root, "git", "symbolic-ref", "-q", "--short", "refs/remotes/origin/HEAD");
        if (remoteHead.ok() && !remoteHead.out().isBlank()) candidates.add(remoteHead.out().strip());
        candidates.addAll(List.of("origin/main", "origin/master", "origin/develop", "main", "master", "develop"));
        for (String c : candidates) if (Git.refExists(c + "^{commit}")) return new String[] {mergeBase(c), c};
        return new String[] {rev("HEAD"), "HEAD (no base branch found: only uncommitted changes are checked)"};
    }

    private static String mergeBase(String ref) {
        Proc p = Proc.run(Ctx.root, "git", "merge-base", ref, "HEAD");
        return p.ok() && !p.out().isBlank() ? p.out().strip() : rev(ref);
    }

    private static String rev(String ref) {
        return Proc.run(Ctx.root, "git", "rev-parse", ref + "^{commit}").out().strip();
    }

    private static Path objects;

    private static synchronized Path objects() throws IOException {
        if (objects == null) {
            Path dir = Files.createTempDirectory("sdlc-objects");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                    for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        p.toFile().setWritable(true);
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                }
            }));
            String inherited = System.getenv("GIT_ALTERNATE_OBJECT_DIRECTORIES");
            Proc.ENV.merge("GIT_ALTERNATE_OBJECT_DIRECTORIES", (inherited == null || inherited.isBlank() ? "" : inherited + java.io.File.pathSeparator) + dir,
                    (a, b) -> a + java.io.File.pathSeparator + dir);
            objects = dir;
        }
        return objects;
    }

    private static String snapshot() {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("sdlc-index", "");
            Proc where = Proc.run(Ctx.root, "git", "rev-parse", "--git-path", "index");
            Path index = where.ok() ? Ctx.root.resolve(where.out().strip()) : null;
            // the copy keeps the index's timestamp: git trusts a file's size and time unless the index is not newer than it
            if (index != null && Files.isRegularFile(index)) Files.copy(index, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            else Files.delete(tmp);
            Proc od = Proc.run(Ctx.root, "git", "rev-parse", "--git-path", "objects");
            if (!od.ok()) throw new UsageException("cannot find the repository's objects: " + od.err().strip());
            String repoObjects = Ctx.root.resolve(od.out().strip()).toString();
            String mine = objects().toString();
            String inherited = System.getenv("GIT_ALTERNATE_OBJECT_DIRECTORIES");
            Map<String, String> env = Map.of("GIT_INDEX_FILE", tmp.toString(), "GIT_OBJECT_DIRECTORY", mine,
                    "GIT_ALTERNATE_OBJECT_DIRECTORIES", repoObjects + (inherited == null || inherited.isBlank() ? "" : java.io.File.pathSeparator + inherited));
            lookAtEveryFile(env);
            Proc add = Proc.runWithEnv(Ctx.root, env, null, 300, "git", "add", "-A", "--", ".");
            if (!add.ok()) throw new UsageException("cannot record the working tree (git add): " + add.err().strip());
            Proc tree = Proc.runWithEnv(Ctx.root, env, null, 120, "git", "write-tree");
            if (!tree.ok()) throw new UsageException("cannot record the working tree (git write-tree): " + tree.err().strip());
            return tree.out().strip();
        } catch (IOException e) {
            throw new UsageException("cannot record the working tree: " + e.getMessage());
        } finally {
            try {
                if (tmp != null) {
                    Files.deleteIfExists(tmp);
                    Files.deleteIfExists(Path.of(tmp + ".lock"));
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static void lookAtEveryFile(Map<String, String> env) {
        Proc flagged = Proc.runWithEnv(Ctx.root, env, null, 120, "git", "ls-files", "-v", "-z");
        if (!flagged.ok()) throw new UsageException("cannot record the working tree (git ls-files): " + flagged.err().strip());
        StringBuilder clear = new StringBuilder(), missing = new StringBuilder();
        for (String entry : flagged.out().split("\0")) {
            if (entry.length() < 3) continue;
            char tag = entry.charAt(0);
            String path = entry.substring(2);
            boolean skip = tag == 'S' || tag == 's';
            boolean assumed = Character.isLowerCase(tag);
            if (!skip && !assumed) continue;
            if (skip && !Files.exists(Ctx.root.resolve(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                missing.append(path).append('\0');
                continue;
            }
            clear.append(path).append('\0');
        }
        if (missing.length() > 0) {
            boolean sparse = Proc.run(Ctx.root, "git", "config", "--bool", "core.sparseCheckout").out().strip().equals("true");
            if (!sparse) {
                clear.append(missing);
            } else {
                Proc inside = Proc.runWithInput(Ctx.root, missing.toString(), 120, "git", "sparse-checkout", "check-rules", "-z");
                if (!inside.ok()) {
                    throw new UsageException("this is a sparse checkout with files that are not on disk, and this git cannot say which of them the sparse rules leave out "
                            + "(git sparse-checkout check-rules needs git 2.41): " + inside.err().strip());
                }
                for (String path : inside.out().split("\0")) if (!path.isEmpty()) clear.append(path).append('\0');
            }
        }
        if (clear.length() == 0) return;
        // one flag per call: given both, git clears only the first
        for (String flag : List.of("--no-assume-unchanged", "--no-skip-worktree")) {
            Proc update = Proc.runWithEnv(Ctx.root, env, clear.toString(), 120, "git", "update-index", flag, "-z", "--stdin");
            if (!update.ok()) throw new UsageException("cannot record the working tree (git update-index): " + update.err().strip());
        }
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public List<String> paths(String statuses) {
        return files.stream().filter(f -> statuses.contains(f.status())).map(File::path).toList();
    }

    public static java.util.function.Consumer<String> wholeRead = path -> {};

    public String before(String path) {
        wholeRead.accept(path);
        return Trusted.read(base, path);
    }

    public String after(String path) {
        wholeRead.accept(path);
        return Trusted.read(head, path);
    }

    public Map<String, List<String>> addedLines() {
        return Git.addedLines(Git.patch(base, head));
    }

    public Map<String, List<String>> removedLines() {
        return Git.removedLines(Git.patch(base, head));
    }
}
