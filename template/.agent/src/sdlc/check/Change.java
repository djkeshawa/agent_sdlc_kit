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

/**
 * The change under check: from the commit where this branch left its base, to the working tree
 * as it is now, committed or not, untracked files included, ignored files left out.
 *
 * The working tree is recorded as a git tree through a throw-away index, so both sides can be
 * read and compared with git. Nothing in the working tree or the real index is touched; git
 * stores the blobs of uncommitted files in its object store, where `git gc` later drops them.
 */
public final class Change {
    /** The empty tree in this repository's hash format: the base of a repository with no commit, or of a first commit. */
    private static String emptyTree() {
        Proc p = Proc.runWithInput(Ctx.root, "", 30, "git", "hash-object", "-t", "tree", "--stdin");
        if (!p.ok() || p.out().isBlank()) throw new UsageException("git hash-object failed: " + p.err().strip());
        return p.out().strip();
    }

    /** One changed file: A added, M modified, D deleted. Renames show as a deletion and an addition. */
    public record File(String status, String path) {}

    /** The commit (or empty tree) the change starts from. */
    public final String base;
    /** How the base was chosen, for the report: "origin/main", "--base v1.2", "HEAD (no base branch found)". */
    public final String baseName;
    /** The working tree as a git tree. */
    public final String head;
    public final List<File> files;

    private Change(String base, String baseName, String head, List<File> files) {
        this.base = base;
        this.baseName = baseName;
        this.head = head;
        this.files = List.copyOf(files);
    }

    /** The change of the repository at Ctx.root. baseOption is --base's value, or null or blank to find the base. */
    public static Change of(String baseOption) {
        if (!Proc.run(Ctx.root, "git", "rev-parse", "--git-dir").ok()) throw new UsageException("sdlc check needs a git repository: " + Ctx.root + " is not one");
        String[] base = base(baseOption);
        String head = snapshot();
        return new Change(base[0], base[1], head, diff(base[0], head));
    }

    /** One commit as a change: from its first parent (the empty tree for a first commit) to the commit itself. */
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

    /** {commit or empty tree, how it was found}. */
    private static String[] base(String option) {
        boolean hasHead = Git.refExists("HEAD");
        if (option != null && !option.isBlank()) {
            if (option.startsWith("-") || !Git.refExists(option + "^{commit}")) throw new UsageException("--base " + option + " is not a commit of this repository");
            return new String[] {hasHead ? mergeBase(option) : rev(option), "--base " + option};
        }
        if (!hasHead) return new String[] {emptyTree(), "an empty repository (no commit yet)"};
        if (Trusted.inCi()) {
            // in a pull request build the base is the event's, or nothing: falling back to HEAD would report "nothing has changed"
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
        return p.ok() && !p.out().isBlank() ? p.out().strip() : rev(ref); // unrelated histories: compare with the ref itself
    }

    private static String rev(String ref) {
        return Proc.run(Ctx.root, "git", "rev-parse", ref + "^{commit}").out().strip();
    }

    /** The working tree as a tree object, written through a copy of the index so the real one stays as it is. */
    private static String snapshot() {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("sdlc-index", "");
            Proc where = Proc.run(Ctx.root, "git", "rev-parse", "--git-path", "index");
            Path index = where.ok() ? Ctx.root.resolve(where.out().strip()) : null;
            if (index != null && Files.isRegularFile(index)) Files.copy(index, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else Files.delete(tmp); // git creates it
            Map<String, String> env = Map.of("GIT_INDEX_FILE", tmp.toString());
            Proc add = Proc.runWithEnv(Ctx.root, env, null, 300, "git", "add", "-A", "--", ".");
            if (!add.ok()) throw new UsageException("cannot record the working tree (git add): " + add.err().strip());
            Proc tree = Proc.runWithEnv(Ctx.root, env, null, 120, "git", "write-tree");
            if (!tree.ok()) throw new UsageException("cannot record the working tree (git write-tree): " + tree.err().strip());
            return tree.out().strip();
        } catch (IOException e) {
            throw new UsageException("cannot record the working tree: " + e.getMessage());
        } finally {
            try {
                if (tmp != null) Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // a stray temp file
            }
        }
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** Paths whose status is one of the given letters, e.g. "AM". */
    public List<String> paths(String statuses) {
        return files.stream().filter(f -> statuses.contains(f.status())).map(File::path).toList();
    }

    /** A file's text before the change, or null when it did not exist. */
    public String before(String path) {
        return Trusted.read(base, path);
    }

    /** A file's text after the change, or null when it is gone. */
    public String after(String path) {
        return Trusted.read(head, path);
    }

    /** Lines the change adds, per file (no context lines). */
    public Map<String, List<String>> addedLines() {
        return Git.addedLines(Git.patch(base, head));
    }

    /** Lines the change removes, per file (no context lines). */
    public Map<String, List<String>> removedLines() {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        String file = null;
        for (String line : Git.patch(base, head).split("\n", -1)) {
            if (line.startsWith("--- ")) {
                String f = line.substring(4).trim();
                file = f.equals("/dev/null") ? null : f.replaceFirst("^a/", "");
                if (file != null) out.putIfAbsent(file, new ArrayList<>());
            } else if (line.startsWith("+++ ")) {
                continue;
            } else if (file != null && line.startsWith("-") && !line.startsWith("---")) {
                out.get(file).add(line.substring(1));
            }
        }
        return out;
    }
}
