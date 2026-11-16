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

public final class FileRules {
    private static final Set<String> PATH_KEYS = Set.of("filePath", "file_path", "path", "filename", "file", "target_file", "targetFile", "uri", "dirPath", "directory", "notebook_path", "notebookPath");
    private static final Pattern PATCH_FILE = Pattern.compile("^\\*\\*\\* (?:Update|Add|Delete) File: (.+)$", Pattern.MULTILINE);
    private static final Pattern MOVE_FILE = Pattern.compile("^\\*\\*\\* Move to: (.+)$", Pattern.MULTILINE);

    private final Guard g;

    FileRules(Guard g) {
        this.g = g;
    }

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
        }
        return out;
    }

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

    private static Path realish(Path abs, int depth) {
        Path existing = abs;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) return abs;
        try {
            Path rest = existing.relativize(abs);
            if (Files.isSymbolicLink(existing) && !Files.exists(existing) && depth < 8) {
                Path up = existing.getParent();
                Path parent = up == null ? existing : up.toRealPath();
                return realish(parent.resolve(Files.readSymbolicLink(existing)).normalize(), depth + 1).resolve(rest).normalize();
            }
            return existing.toRealPath().resolve(rest).normalize();
        } catch (IOException e) {
            return abs;
        }
    }

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
        }
        return out;
    }

    private static final Pattern DIFF_PAIR = Pattern.compile("^--- ([^\\t\\n]+)[^\\n]*\\n\\+\\+\\+ ([^\\t\\n]+)", Pattern.MULTILINE);
    private static final Pattern DIFF_RENAME = Pattern.compile("^(?:rename|copy) (?:from|to) (.+)$", Pattern.MULTILINE);

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

    public boolean isCredential(String path) {
        String clean = path.replace('\\', '/');
        String name = clean.substring(clean.lastIndexOf('/') + 1);
        List<String> globs = g.cfg.list("paths.secret");
        return Glob.any(globs, relative(path)) || Glob.any(globs, clean) || Glob.any(globs, "x/" + name);
    }

    public boolean isSecret(String path) {
        String clean = path.replace('\\', '/');
        String name = clean.substring(clean.lastIndexOf('/') + 1);
        List<String> globs = new java.util.ArrayList<>(g.cfg.list("paths.secret"));
        globs.addAll(g.cfg.list("paths.hidden"));
        return Glob.any(globs, relative(path)) || Glob.any(globs, clean) || Glob.any(globs, "x/" + name) || otherReadings(path).stream().anyMatch(r -> Glob.any(globs, r));
    }

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

    // As sh/bash: *, ? and [..] do not match a leading dot unless the pattern does. Too many matches: null (fail closed).
    List<Path> expandGlob(String pattern) {
        String p = pattern.replace('\\', '/');
        boolean absolute;
        try {
            absolute = p.startsWith("/") || Path.of(p.replaceAll("[*?\\[\\]]", "x")).isAbsolute();
        } catch (RuntimeException e) {
            return List.of();
        }
        if (absolute) return expandFrom(g.root, p);
        Set<Path> out = new LinkedHashSet<>();
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
                return List.of();
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
                }
                if (next.size() > WALK_LIMIT) return null;
            }
            current = next;
            if (current.isEmpty()) return List.of();
        }
        return current;
    }

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
            return true;
        }
        return found[0];
    }

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
        if (Glob.any(g.cfg.list("paths.protected"), rel) && !Glob.any(g.cfg.list("paths.agentWritable"), rel) || sdlc.core.Locks.isLocked(g.root, rel)) return true;
        String inGit = inGitFolder(rel);
        return inGit != null && Glob.any(g.cfg.list("paths.protected"), inGit);
    }

    String inGitFolder(String rel) {
        try {
            Path abs = g.root.resolve(rel).normalize();
            for (Path git : gitFolders()) {
                if (abs.equals(git) || git.startsWith(abs)) return ".git";
                if (abs.startsWith(git)) return ".git/" + git.relativize(abs).toString().replace('\\', '/');
            }
        } catch (RuntimeException e) {
        }
        return null;
    }

    private List<Path> gitFolders;

    private List<Path> gitFolders() {
        if (gitFolders != null) return gitFolders;
        List<Path> out = new ArrayList<>();
        try {
            Path dot = g.root.resolve(".git");
            if (Files.isRegularFile(dot)) {
                String first = Files.readString(dot).lines().findFirst().orElse("").strip();
                if (first.startsWith("gitdir:")) {
                    Path own = g.root.resolve(first.substring("gitdir:".length()).strip()).normalize();
                    Path shared = own;
                    Path pointer = own.resolve("commondir");
                    if (Files.isRegularFile(pointer)) shared = own.resolve(Files.readString(pointer).strip()).normalize();
                    for (Path p : List.of(shared, own)) {
                        if (out.stream().anyMatch(p::startsWith)) continue;
                        out.add(p);
                        Path real = realish(p);
                        if (!real.equals(p)) out.add(real);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
        }
        gitFolders = out;
        return out;
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
        for (String path : paths) for (String rel : writeTargets(path)) {
            if (rel.startsWith("..")) return Decision.deny("`" + path + "` is outside the repository.");
            if (isSecret(path) || isSecret(rel)) return Decision.deny("`" + rel + "` holds credentials or hidden test data. Agents never touch it.");
            if (sdlc.core.Locks.isLocked(g.root, rel)) return Decision.deny("`" + rel + "` is an acceptance test locked by QE (" + sdlc.core.Locks.DIR + "). Make the code pass it; if the test is wrong, ask QE to change and lock it again.");
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
        if (Glob.any(g.cfg.list("paths.ask"), rel)) return Decision.allow();
        return Decision.deny("`" + rel + "` is outside the scope of task " + task.key() + " (" + String.join(", ", task.scope())
                + "). If the task truly needs it, stop and ask the human: the LLD's task scope must change and be re-approved.");
    }
}
