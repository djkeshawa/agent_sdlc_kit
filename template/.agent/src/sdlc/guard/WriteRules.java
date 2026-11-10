package sdlc.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import sdlc.util.Glob;
import sdlc.util.ShellWords;

/**
 * Shell rules about commands that change files: redirections and file commands (rm, mv, cp,
 * sed -i, Set-Content ...), patches, archives and deletes. The files they would touch are
 * held to the same rules as an edit tool: no guardrail files, code only inside the task scope.
 */
final class WriteRules {
    private static final Set<String> WRITERS = Set.of("rm", "rmdir", "mv", "tee", "truncate", "touch", "chmod", "ln", "unlink", "shred",
            "set-content", "add-content", "out-file", "remove-item", "move-item", "new-item", "rename-item", "clear-content",
            "del", "erase", "move", "rename", "ren", "rd", "sc", "ac", "ri", "mi", "ni");
    private static final Set<String> REMOVERS = Set.of("rm", "remove-item", "ri", "rd", "rmdir", "del");

    private final Guard g;

    WriteRules(Guard g) {
        this.g = g;
    }

    /** Shell writes (redirections, rm/mv/cp/sed -i/Set-Content ...) into guardrail files or code outside the task scope. */
    Decision targets(ShellCommand c) {
        String name = c.name();
        List<String> args = c.args(), raw = c.raw();
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < raw.size() - 1; i++) {
            if (raw.get(i).matches("^[0-9]?>>?$|^&>$")) targets.add(raw.get(i + 1));
        }
        if (WRITERS.contains(name)) targets.addAll(args.stream().filter(a -> !a.startsWith("-")).toList());
        if (ShellRules.COPIERS.contains(name) && !args.isEmpty()) targets.add(args.get(args.size() - 1));
        if ((name.equals("sed") || name.equals("perl")) && args.stream().anyMatch(a -> a.startsWith("-i") || a.equals("--in-place"))) {
            targets.addAll(args.stream().filter(a -> !a.startsWith("-")).toList());
        }
        if (name.equals("git") && !args.isEmpty() && Set.of("rm", "mv", "checkout", "restore").contains(args.get(0))) {
            targets.addAll(args.subList(1, args.size()).stream().filter(a -> !a.startsWith("-")).toList());
        }
        List<String> expanded = new ArrayList<>(); // the files the shell will really touch: braces and globs expanded
        for (String t : targets) {
            for (String b : ShellWords.braces(t)) {
                expanded.add(b);
                if (!FileRules.isGlob(b)) continue;
                List<Path> hits = g.files.expandGlob(b.replace("~", System.getProperty("user.home")));
                if (hits == null) return Decision.deny("`" + t + "` matches too many files to check.");
                hits.forEach(h -> expanded.add(h.toString()));
            }
        }
        for (String t : expanded) {
            if (t.isBlank() || t.equals("/dev/null") || t.equalsIgnoreCase("nul")) continue;
            for (String alt : g.files.otherReadings(t)) { // the same words read from another folder the shell may be in
                if (g.files.isProtected(alt)) return Decision.deny("`" + t + "` may name `" + alt + "`, a guardrail or state file. Only a human or the sdlc tool changes it.");
            }
            for (String rel : g.files.writeTargets(t)) { // the path, and the file a symbolic link on the way leads to
                if (g.files.isProtected(rel)) return Decision.deny("`" + rel + "` is a guardrail or state file. Only a human or the sdlc tool changes it.");
                if (rel.startsWith("..") || !Glob.any(g.cfg.list("paths.code"), rel) || Glob.any(g.cfg.list("paths.agentWritable"), rel)) continue;
                Decision scope = g.files.scope(rel);
                if (scope.denied()) return scope;
            }
        }
        return null;
    }

    /** Commands that write files the guard cannot name from the command line: patches and archives. */
    Decision patchOrArchive(ShellCommand c) {
        String name = c.name();
        List<String> args = c.args();
        String first = c.firstArg();
        if (name.equals("patch") || name.equals("git") && (first.equals("apply") || first.equals("am"))) return patch(name, args);
        boolean extract = switch (name) {
            case "tar", "bsdtar" -> first.matches("^-?[a-zA-Z]*x[a-zA-Z]*$") || args.stream().anyMatch(a -> a.equals("--extract") || a.equals("--get") || a.matches("^-[a-zA-Z]*x[a-zA-Z]*$"));
            case "unzip" -> args.stream().noneMatch(a -> a.matches("^-[a-zA-Z]*[lptvzZ][a-zA-Z]*$"));
            case "7z", "7za" -> first.equals("x") || first.equals("e");
            case "jar" -> first.matches("^-?[a-zA-Z]*x[a-zA-Z]*$") || args.contains("--extract");
            case "cpio" -> args.stream().anyMatch(a -> a.equals("--extract") || a.matches("^-[a-zA-Z]*i[a-zA-Z]*$"));
            default -> false;
        };
        if (!extract) return null;
        String dest = ".";
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            boolean flag = name.equals("unzip") ? a.equals("-d") : a.equals("-C") || a.equals("--directory");
            if (flag && i + 1 < args.size()) dest = args.get(i + 1);
            else if (a.startsWith("--directory=")) dest = a.substring("--directory=".length());
            else if (name.startsWith("7z") && a.startsWith("-o") && a.length() > 2) dest = a.substring(2);
        }
        String rel = g.files.relative(ShellRules.expandHome(dest));
        if (rel.startsWith("..") || ShellRules.BUILD_OUTPUT.matcher(rel).matches() || Glob.any(g.cfg.list("paths.agentWritable"), rel + "/x")) return null;
        return Decision.ask("`" + name + "` unpacks files the guard cannot list into `" + dest + "`. Approve only if nothing lands outside the task scope or on a guardrail file.");
    }

    /** git apply / git am / patch: the files named in the patch are checked like edits; an unreadable patch needs a human. */
    private Decision patch(String name, List<String> args) {
        List<String> rest = name.equals("git") ? args.subList(1, args.size()) : args;
        if (rest.stream().anyMatch(a -> Set.of("--check", "--stat", "--numstat", "--summary", "--dry-run").contains(a))) return null;
        int strip = name.equals("git") ? 1 : -1;
        String dir = "";
        List<String> files = new ArrayList<>();
        for (int i = 0; i < rest.size(); i++) {
            String a = rest.get(i);
            boolean next = i + 1 < rest.size();
            if (a.matches("^-p\\d+$")) strip = Integer.parseInt(a.substring(2));
            else if (a.matches("^--strip=\\d+$")) strip = Integer.parseInt(a.substring("--strip=".length()));
            else if ((a.equals("-p") || a.equals("--strip")) && next && rest.get(i + 1).matches("\\d+")) strip = Integer.parseInt(rest.get(++i));
            else if ((a.equals("-d") || a.equals("--directory")) && next) dir = rest.get(++i);
            else if (a.startsWith("--directory=")) dir = a.substring("--directory=".length());
            else if (!a.startsWith("-")) files.add(a);
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String f : files) {
            try {
                Path p = g.folders.base().resolve(ShellRules.expandHome(f));
                if (Files.isRegularFile(p) && Files.size(p) < 5_000_000) targets.addAll(FileRules.diffTargets(Files.readString(p), strip));
            } catch (IOException | RuntimeException e) {
                // unreadable: handled below
            }
        }
        String what = name.equals("git") ? "git " + args.get(0) : name;
        if (targets.isEmpty()) return Decision.ask("`" + what + "` applies a patch the guard cannot read. Approve only if every file it changes is inside the task scope.");
        String prefix = dir.isEmpty() ? "" : dir.replaceAll("/+$", "") + "/";
        return g.files.checkEdit(targets.stream().map(t -> prefix + t).toList());
    }

    /** rm and friends: never outside the project, the whole project or .git; a recursive delete outside build output needs a human. */
    Decision remove(ShellCommand c) {
        if (!REMOVERS.contains(c.name())) return null;
        List<String> args = c.args();
        boolean recursive = args.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[rR][a-zA-Z]*$") || a.equalsIgnoreCase("--recursive") || a.equalsIgnoreCase("-recurse") || a.equalsIgnoreCase("/s"));
        for (String a : args) {
            if (a.startsWith("-") || a.equalsIgnoreCase("/s") || a.equalsIgnoreCase("/q")) continue;
            String rel = g.files.relative(a.replace("~", System.getProperty("user.home")));
            if (a.equals("/") || a.equals("~") || a.equals("*") || a.equals(".") || a.equals("..") || a.startsWith("$HOME") || rel.startsWith("..") || rel.isEmpty() || rel.equals(".git") || rel.startsWith(".git/")) {
                return Decision.deny("refusing to delete `" + a + "` (outside the project, the whole project, or .git).");
            }
        }
        if (!recursive) return Decision.allow();
        boolean buildOutput = args.stream().filter(a -> !a.startsWith("-")).allMatch(a -> ShellRules.BUILD_OUTPUT.matcher(g.files.relative(a)).matches());
        return buildOutput ? Decision.allow() : Decision.ask("recursive delete of " + String.join(" ", args));
    }
}
