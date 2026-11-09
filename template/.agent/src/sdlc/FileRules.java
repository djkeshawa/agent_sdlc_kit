package sdlc;

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

/** Rules for agent read and edit tools: secrets, guardrail files, branch, task scope. */
final class FileRules {
    private static final Set<String> PATH_KEYS = Set.of("filePath", "file_path", "path", "filename", "file", "target_file", "targetFile", "uri", "dirPath", "directory", "notebook_path", "notebookPath");
    private static final Pattern PATCH_FILE = Pattern.compile("^\\*\\*\\* (?:Update|Add|Delete) File: (.+)$", Pattern.MULTILINE);
    private static final Pattern MOVE_FILE = Pattern.compile("^\\*\\*\\* Move to: (.+)$", Pattern.MULTILINE);

    private FileRules() {}

    /** Every file path a tool call names, however the host spells it. */
    static List<String> paths(Map<String, Object> args) {
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
    static String relative(String path) {
        try {
            Path p = Path.of(path.replace('\\', '/'));
            Path abs = p.isAbsolute() ? p.normalize() : Ctx.root.resolve(p).normalize();
            if (!abs.startsWith(Ctx.root)) {
                Path real = realish(abs);
                if (real.startsWith(Ctx.root)) abs = real;
            }
            return Ctx.root.relativize(abs).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return path.replace('\\', '/');
        }
    }

    private static Path realish(Path abs) {
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return abs;
        try {
            return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
        } catch (IOException e) {
            return abs;
        }
    }

    static boolean isSecret(String path) {
        String clean = path.replace('\\', '/');
        String name = clean.substring(clean.lastIndexOf('/') + 1);
        List<String> globs = Ctx.cfg.list("paths.secret");
        return Glob.any(globs, relative(path)) || Glob.any(globs, clean) || Glob.any(globs, "x/" + name);
    }

    /**
     * A shell word that names a credential file, e.g. cat .agent/local.env or --file=~/.netrc,
     * including a glob the shell would expand to one (cat .agent/*, cat .agent/local.en?).
     */
    static boolean isSecretWord(String word) {
        if (word.startsWith("-") && !word.contains("=")) return false;
        String v = word.contains("=") ? word.substring(word.indexOf('=') + 1) : word;
        if (v.isBlank()) return false;
        List<String> variants = ShellWords.braces(v);
        if (variants.size() > 1) return variants.stream().anyMatch(FileRules::isSecretWord);
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

    static boolean isGlob(String word) {
        return GLOB_CHARS.matcher(word).find();
    }

    /**
     * What the shell would expand a glob to, segment by segment like sh/bash: `*`, `?` and `[..]`
     * do not match a leading dot unless the pattern does, and `**` acts as `*`. Relative globs
     * expand from the repository root. Null when there are too many matches to tell (fail closed).
     */
    static List<Path> expandGlob(String pattern) {
        String p = pattern.replace('\\', '/');
        String[] segs = p.split("/", -1);
        int first = 0;
        while (first < segs.length && !isGlob(segs[first])) first++;
        String fixed = String.join("/", java.util.Arrays.copyOfRange(segs, 0, first));
        List<Path> current = new ArrayList<>();
        try {
            current.add(p.startsWith("/") || Path.of(p.replaceAll("[*?\\[\\]]", "x")).isAbsolute()
                    ? Path.of(fixed.isEmpty() ? "/" : fixed) : Ctx.root.resolve(fixed.isEmpty() ? "." : fixed).normalize());
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
    static boolean dirHoldsSecret(Path dir) {
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
    static Path safeInput(String file) {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) throw new Main.UsageException("no such file: " + file);
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {
            throw new Main.UsageException("cannot read " + file + ": " + e.getMessage());
        }
        if (isSecret(file) || isSecret(p.toAbsolutePath().normalize().toString()) || isSecret(real.toString())) {
            throw new Main.UsageException("Refused: " + file + " holds credentials; the tool never sends secrets anywhere.");
        }
        return p;
    }

    static boolean isProtected(String rel) {
        return Glob.any(Ctx.cfg.list("paths.protected"), rel) && !Glob.any(Ctx.cfg.list("paths.agentWritable"), rel);
    }

    static Decision checkRead(List<String> paths) {
        for (String path : paths) {
            if (isSecret(path)) return Decision.deny("`" + path + "` holds credentials. Agents never read secrets.");
        }
        return Decision.allow();
    }

    static Decision checkEdit(List<String> paths) {
        Decision result = Decision.allow();
        String branch = Git.branch();
        Work.Task task = Work.currentTask();
        for (String path : paths) {
            String rel = relative(path);
            if (rel.startsWith("..")) return Decision.deny("`" + path + "` is outside the repository.");
            if (isSecret(path)) return Decision.deny("`" + rel + "` holds credentials. Agents never touch secrets.");
            if (isProtected(rel)) return Decision.deny("`" + rel + "` is a guardrail or state file. Only a human changes it (CODEOWNERS review), or the sdlc tool itself.");
            boolean scratch = Glob.any(Ctx.cfg.list("paths.agentWritable"), rel);
            if (!scratch && Ctx.cfg.isProtectedBranch(branch)) {
                return Decision.deny("you are on protected branch `" + branch + "`. Create a feature branch first: git switch -c feature/<KEY>-short-name origin/" + Ctx.cfg.baseBranch());
            }
            if (scratch) continue;
            Decision d = scope(rel, task);
            if (d.denied()) return d;
            result = result.strictest(d);
            if (Glob.any(Ctx.cfg.list("paths.ask"), rel)) {
                result = result.strictest(Decision.ask("`" + rel + "` changes the build, dependencies, lint/test configuration or the database. Approve only if the task needs it."));
            }
        }
        return result;
    }

    static Decision scope(String rel, Work.Task task) {
        if (Glob.any(Ctx.cfg.list("paths.doc"), rel)) return Decision.allow();
        boolean code = Glob.any(Ctx.cfg.list("paths.code"), rel);
        if (task == null) {
            if (code && Ctx.cfg.bool("guard.requireTaskForCodeEdits", true)) {
                return Decision.deny("no active task. Code edits start after the LLD is approved and a task is started: java .agent/Sdlc.java task start <TASK-KEY>");
            }
            return Decision.allow();
        }
        if (Glob.any(task.scope(), rel)) return Decision.allow();
        if (Glob.any(Ctx.cfg.list("paths.ask"), rel)) return Decision.allow(); // the ask rule applies
        return Decision.deny("`" + rel + "` is outside the scope of task " + task.key() + " (" + String.join(", ", task.scope())
                + "). If the task truly needs it, stop and ask the human: the LLD's task scope must change and be re-approved.");
    }
}
