package sdlc.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Glob;
import sdlc.util.ShellWords;

/**
 * Rules for agent read and edit tools: secrets, guardrail files, branch, task scope. The
 * methods that need the repository, its configuration or the shell's folder are instance
 * methods and take them from the Guard; the pure text helpers (paths, isGlob, diffTargets) are static.
 */
public final class FileRules {
    private static final Set<String> PATH_KEYS = Set.of("filePath", "file_path", "path", "filename", "file", "target_file", "targetFile", "uri", "dirPath", "directory", "notebook_path", "notebookPath");
    private static final Pattern PATCH_FILE = Pattern.compile("^\\*\\*\\* (?:Update|Add|Delete) File: (.+)$", Pattern.MULTILINE);
    private static final Pattern MOVE_FILE = Pattern.compile("^\\*\\*\\* Move to: (.+)$", Pattern.MULTILINE);

    private final Guard g;

    FileRules(Guard g) {
        this.g = g;
    }

    /**
     * What else a relative path may name, relative to the repository: the same words read from the
     * other folders the shell may be in, and a path that climbs with ../ read as if it came back
     * inside (the terminal may sit in a subfolder the guard never saw). Used for the checks that
     * must not be fooled - secrets and guardrail files - not for the task scope.
     */
    List<String> otherReadings(String path) {
        List<String> out = new ArrayList<>();
        try {
            String clean = path.replace('\\', '/');
            if (clean.isBlank() || clean.startsWith("~") || clean.startsWith("-") || Path.of(clean).isAbsolute()) return out;
            String primary = relative(path);
            List<Path> candidates = new ArrayList<>();
            for (Path dir : g.folders.bases()) candidates.add(dir.resolve(clean).normalize());
            for (String s = clean; s.startsWith("../"); ) {
                s = s.substring(3);
                if (!s.isEmpty() && !s.equals("..")) candidates.add(g.root.resolve(s).normalize());
            }
            for (Path abs : candidates) {
                if (!abs.startsWith(g.root)) continue;
                String rel = g.root.relativize(abs).toString().replace('\\', '/');
                if (!rel.isEmpty() && !rel.equals(primary) && !out.contains(rel)) out.add(rel);
            }
        } catch (RuntimeException e) {
            // not a path
        }
        return out;
    }

    /** Every file path a tool call names, however the host spells it. */
    public static List<String> paths(Map<String, Object> args) {
        Set<String> out = new LinkedHashSet<>();
        collect(args, out);
        return new ArrayList<>(out);
    }

    private static void collect(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object v = e.getValue();
                if (PATH_KEYS.contains(String.valueOf(e.getKey())) && v instanceof String s && !s.isBlank()) out.add(s.startsWith("file://") ? s.substring(7) : s);
                else if (v instanceof String s && s.contains("*** ")) patchPaths(s, out);
                else collect(v, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) collect(item, out);
        }
    }

    private static void patchPaths(String patch, Set<String> out) {
        for (Pattern p : List.of(PATCH_FILE, MOVE_FILE)) {
            Matcher m = p.matcher(patch);
            while (m.find()) out.add(m.group(1).trim());
        }
    }

    /** Path relative to the repository root with forward slashes; "../x" when outside. */
    public String relative(String path) {
        try {
            Path p = Path.of(path.replace('\\', '/'));
            Path abs = p.isAbsolute() ? p.normalize() : g.folders.base().resolve(p).normalize();
            if (!abs.startsWith(g.root)) {
                Path real = realish(abs);
                if (real.startsWith(g.root)) abs = real;
            }
            return g.root.relativize(abs).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return path.replace('\\', '/');
        }
    }

    private static Path realish(Path abs) {
        return realish(abs, 0);
    }

    /** The path with every symbolic link on the way resolved, also for a file that does not exist yet or a link that points nowhere yet. */
    private static Path realish(Path abs, int depth) {
        Path existing = abs;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) return abs;
        try {
            Path rest = existing.relativize(abs);
            if (Files.isSymbolicLink(existing) && !Files.exists(existing) && depth < 8) { // dangling link: a write creates its target
                Path parent = existing.getParent() == null ? existing : existing.getParent().toRealPath();
                return realish(parent.resolve(Files.readSymbolicLink(existing)).normalize(), depth + 1).resolve(rest).normalize();
            }
            return existing.toRealPath().resolve(rest).normalize();
        } catch (IOException e) {
            return abs;
        }
    }

    /**
     * Where a write to this path can land, relative to the repository: the path as written and,
     * when a symbolic link is on the way, the file the link leads to ("../..." when that is outside).
     * A link in a folder agents may write to must not open a door to code or guardrail files.
     */
    List<String> writeTargets(String path) {
        String rel = relative(path);
        List<String> out = new ArrayList<>(List.of(rel));
        try {
            Path p = Path.of(path.replace('\\', '/'));
            Path abs = p.isAbsolute() ? p.normalize() : g.folders.base().resolve(p).normalize();
            Path real = realish(abs);
            Path realRoot = g.root.toRealPath();
            String realRel = (real.startsWith(realRoot) ? realRoot.relativize(real) : g.root.relativize(real)).toString().replace('\\', '/');
            if (!realRel.equals(rel)) out.add(realRel);
        } catch (IOException | RuntimeException e) {
            // keep the path as written
        }
        return out;
    }

    private static final Pattern DIFF_PAIR = Pattern.compile("^--- ([^\\t\\n]+)[^\\n]*\\n\\+\\+\\+ ([^\\t\\n]+)", Pattern.MULTILINE);
    private static final Pattern DIFF_RENAME = Pattern.compile("^(?:rename|copy) (?:from|to) (.+)$", Pattern.MULTILINE);

    /** Files a unified diff changes. strip = leading path parts to drop (-p); -1 = one when the paths start with a/ or b/. */
    public static List<String> diffTargets(String patch, int strip) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = DIFF_PAIR.matcher(patch);
        while (m.find()) {
            for (int g = 1; g <= 2; g++) {
                String f = m.group(g).trim();
                if (f.equals("/dev/null")) continue;
                int n = strip >= 0 ? strip : f.startsWith("a/") || f.startsWith("b/") ? 1 : 0;
                for (int i = 0; i < n && f.contains("/"); i++) f = f.substring(f.indexOf('/') + 1);
                out.add(f);
            }
        }
        Matcher r = DIFF_RENAME.matcher(patch);
        while (r.find()) out.add(r.group(1).trim());
        return new ArrayList<>(out);
    }

    public boolean isSecret(String path) {
        String clean = path.replace('\\', '/');
        String name = clean.substring(clean.lastIndexOf('/') + 1);
        List<String> globs = new java.util.ArrayList<>(g.cfg.list("paths.secret"));
        globs.addAll(g.cfg.list("paths.hidden")); // eval truth and hold-out tests: never read by agents either
        return Glob.any(globs, relative(path)) || Glob.any(globs, clean) || Glob.any(globs, "x/" + name) || otherReadings(path).stream().anyMatch(r -> Glob.any(globs, r));
    }

    /**
     * A shell word that names a credential file, e.g. cat .agent/local.env or --file=~/.netrc,
     * including a glob the shell would expand to one (cat .agent/*, cat .agent/local.en?).
     */
    boolean isSecretWord(String word) {
        if (word.startsWith("-") && !word.contains("=")) return false;
        String v = word.contains("=") ? word.substring(word.indexOf('=') + 1) : word;
        if (v.isBlank()) return false;
        List<String> variants = ShellWords.braces(v);
        if (variants.size() > 1) return variants.stream().anyMatch(this::isSecretWord);
        String path = v.replace("~", System.getProperty("user.home"));
        if (isGlob(path)) {
            List<Path> hits = expandGlob(path);
            return hits == null || hits.stream().anyMatch(h -> !Files.isDirectory(h) && isSecret(h.toString()));
        }
        if (!(v.contains("/") || v.contains("\\") || v.contains("."))) return false;
        return isSecret(path);
    }

    private static final Pattern GLOB_CHARS = Pattern.compile("[*?\\[]");
    private static final Set<String> WALK_SKIP = Set.of(".git", "node_modules", "target", "build", "dist", "coverage", ".next");
    private static final int WALK_LIMIT = 50_000;

    public static boolean isGlob(String word) {
        return GLOB_CHARS.matcher(word).find();
    }

    /**
     * What the shell would expand a glob to, segment by segment like sh/bash: `*`, `?` and `[..]`
     * do not match a leading dot unless the pattern does, and `**` acts as `*`. A relative glob
     * expands in every folder the shell may be in. Null when there are too many matches to tell (fail closed).
     */
    List<Path> expandGlob(String pattern) {
        String p = pattern.replace('\\', '/');
        boolean absolute;
        try {
            absolute = p.startsWith("/") || Path.of(p.replaceAll("[*?\\[\\]]", "x")).isAbsolute();
        } catch (RuntimeException e) {
            return List.of();
        }
        if (absolute) return expandFrom(g.root, p);
        Set<Path> out = new LinkedHashSet<>(); // a relative glob expands in whichever folder the shell is in
        for (Path dir : g.folders.bases()) {
            List<Path> hits = expandFrom(dir, p);
            if (hits == null) return null;
            out.addAll(hits);
        }
        return new ArrayList<>(out);
    }

    private static List<Path> expandFrom(Path from, String p) {
        String[] segs = p.split("/", -1);
        int first = 0;
        while (first < segs.length && !isGlob(segs[first])) first++;
        String fixed = String.join("/", java.util.Arrays.copyOfRange(segs, 0, first));
        List<Path> current = new ArrayList<>();
        try {
            current.add(p.startsWith("/") || Path.of(p.replaceAll("[*?\\[\\]]", "x")).isAbsolute()
                    ? Path.of(fixed.isEmpty() ? "/" : fixed) : from.resolve(fixed.isEmpty() ? "." : fixed).normalize());
        } catch (RuntimeException e) {
            return List.of();
        }
        for (int i = first; i < segs.length; i++) {
            String seg = segs[i].replaceAll("\\*\\*+", "*");
            if (seg.isEmpty()) continue;
            java.nio.file.PathMatcher m;
            try {
                m = current.get(0).getFileSystem().getPathMatcher("glob:" + seg);
            } catch (RuntimeException e) {
                return List.of(); // not a valid glob (a regex argument, say): the shell would not expand it either
            }
            boolean dotOk = seg.startsWith(".");
            List<Path> next = new ArrayList<>();
            for (Path dir : current) {
                if (!Files.isDirectory(dir)) continue;
                try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                    s.filter(x -> {
                        String n = String.valueOf(x.getFileName());
                        return (dotOk || !n.startsWith(".")) && m.matches(x.getFileName());
                    }).forEach(next::add);
                } catch (IOException e) {
                    // an unreadable folder expands to nothing, as in the shell
                }
                if (next.size() > WALK_LIMIT) return null;
            }
            current = next;
            if (current.isEmpty()) return List.of();
        }
        return current;
    }

    /** True when a folder holds a credential file anywhere below it (build output and .git skipped). */
    boolean dirHoldsSecret(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        boolean[] found = {false};
        int[] seen = {0};
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                    if (!d.equals(dir) && WALK_SKIP.contains(String.valueOf(d.getFileName()))) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    if (++seen[0] > WALK_LIMIT || isSecret(f.toString())) {
                        found[0] = true;
                        return java.nio.file.FileVisitResult.TERMINATE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException e) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return true; // cannot tell: fail closed
        }
        return found[0];
    }

    /** A file the tool reads on request (--file): refused when it is, or links to, a credential file. */
    public Path safeInput(String file) {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) throw new UsageException("no such file: " + file);
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {
            throw new UsageException("cannot read " + file + ": " + e.getMessage());
        }
        if (isSecret(file) || isSecret(p.toAbsolutePath().normalize().toString()) || isSecret(real.toString())) {
            throw new UsageException("Refused: " + file + " holds credentials; the tool never sends secrets anywhere.");
        }
        return p;
    }

    boolean isProtected(String rel) {
        return Glob.any(g.cfg.list("paths.protected"), rel) && !Glob.any(g.cfg.list("paths.agentWritable"), rel);
    }

    public Decision checkRead(List<String> paths) {
        for (String path : paths) {
            if (isSecret(path) || writeTargets(path).stream().anyMatch(this::isSecret)) return Decision.deny("`" + path + "` holds credentials or hidden test data. Agents never read it.");
        }
        return Decision.allow();
    }

    public Decision checkEdit(List<String> paths) {
        Decision result = Decision.allow();
        String branch = g.branch();
        for (String path : paths) {
            for (String alt : otherReadings(path)) {
                if (isProtected(alt)) return Decision.deny("`" + path + "` may name `" + alt + "`, a guardrail or state file. Use a path from the repository root.");
            }
        }
        for (String path : paths) for (String rel : writeTargets(path)) { // the path, and the file a symbolic link on the way leads to
            if (rel.startsWith("..")) return Decision.deny("`" + path + "` is outside the repository.");
            if (isSecret(path) || isSecret(rel)) return Decision.deny("`" + rel + "` holds credentials or hidden test data. Agents never touch it.");
            if (isProtected(rel)) return Decision.deny("`" + rel + "` is a guardrail or state file. Only a human changes it (CODEOWNERS review), or the sdlc tool itself.");
            boolean scratch = Glob.any(g.cfg.list("paths.agentWritable"), rel);
            if (!scratch && g.cfg.isProtectedBranch(branch)) {
                return Decision.deny("you are on protected branch `" + branch + "`. Create a feature branch first: git switch -c feature/<KEY>-short-name origin/" + g.cfg.baseBranch());
            }
            if (scratch) continue;
            Decision d = scope(rel);
            if (d.denied()) return d;
            result = result.strictest(d);
            if (Glob.any(g.cfg.list("paths.ask"), rel)) {
                result = result.strictest(Decision.ask("`" + rel + "` changes the build, dependencies, lint/test configuration or the database. Approve only if the task needs it."));
            }
        }
        return result;
    }

    /** May the task in progress change this code file? Docs are always free; without a task, code is not. */
    Decision scope(String rel) {
        Work.Task task = g.task();
        if (Glob.any(g.cfg.list("paths.doc"), rel)) return Decision.allow();
        boolean code = Glob.any(g.cfg.list("paths.code"), rel);
        if (task == null) {
            if (code && g.cfg.bool("guard.requireTaskForCodeEdits", true)) {
                return Decision.deny("no active task. Code edits start after the LLD is approved and a task is started: java .agent/Sdlc.java task start <TASK-KEY>");
            }
            return Decision.allow();
        }
        if (Glob.any(task.scope(), rel)) return Decision.allow();
        if (Glob.any(g.cfg.list("paths.ask"), rel)) return Decision.allow(); // the ask rule applies
        return Decision.deny("`" + rel + "` is outside the scope of task " + task.key() + " (" + String.join(", ", task.scope())
                + "). If the task truly needs it, stop and ask the human: the LLD's task scope must change and be re-approved.");
    }
}
