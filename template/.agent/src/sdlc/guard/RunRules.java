package sdlc.guard;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import sdlc.core.Work;
import sdlc.util.Glob;

/**
 * Shell rules about commands that run other code: a shell given a command line or a script,
 * interpreters given inline code or a file, a file run by its path, and package runners.
 */
final class RunRules {
    private static final Set<String> SHELLS = Set.of("bash", "sh", "zsh", "dash", "ksh");
    private static final Set<String> POWERSHELLS = Set.of("powershell", "pwsh");
    private static final Set<String> INLINE = Set.of("node", "python", "python3", "perl", "ruby", "deno", "bun", "php");
    private static final Pattern SCRIPT_FILE = Pattern.compile("(?i).*\\.(sh|bash|zsh|py|js|mjs|cjs|ts|rb|pl|php|ps1|bat|cmd)$");

    private final Guard g;
    private final ShellRules shell;
    private final BuildRules build;

    RunRules(Guard g, ShellRules shell, BuildRules build) {
        this.g = g;
        this.shell = shell;
        this.build = build;
    }

    /** bash -c "...", sh script.sh, source x.sh, powershell -Command, cmd /c, eval: the inner command line is checked like the outer one. */
    Decision nested(ShellCommand c) {
        String name = c.name();
        List<String> args = c.args();
        if (SHELLS.contains(name)) {
            int i = -1; // -c, also combined as in bash -lc / sh -ec
            for (int k = 0; k < args.size() && i < 0; k++) if (args.get(k).matches("^-[a-zA-Z]*c[a-zA-Z]*$")) i = k;
            if (i >= 0) return i + 1 < args.size() ? shell.check(args.get(i + 1)) : Decision.allow();
            for (int k = 0; k < args.size(); k++) { // sh mvnw ..., bash scripts/x.sh: the script is the command
                if (args.get(k).startsWith("-") || args.get(k).startsWith("+")) continue;
                List<String> script = new ArrayList<>(args.subList(k, args.size()));
                if (build.isTool(script.get(0))) return build.check(script.subList(1, script.size()));
                return file(script.get(0));
            }
            return Decision.allow();
        }
        if (name.equals("source") || name.equals(".")) return args.isEmpty() ? Decision.allow() : file(args.get(0));
        if (POWERSHELLS.contains(name)) {
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i).toLowerCase(Locale.ROOT);
                if ((a.equals("-command") || a.equals("-c")) && i + 1 < args.size()) return shell.check(String.join(" ", args.subList(i + 1, args.size())));
                if (a.equals("-encodedcommand") || a.equals("-enc") || a.equals("-e")) return Decision.deny("encoded PowerShell commands cannot be checked.");
            }
            return Decision.allow();
        }
        if (name.equals("cmd")) {
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equalsIgnoreCase("/c") || args.get(i).equalsIgnoreCase("/k")) return shell.check(String.join(" ", args.subList(i + 1, args.size())));
            }
            return Decision.allow();
        }
        if (name.equals("eval")) return shell.check(String.join(" ", args));
        return null;
    }

    /** node -e, python -c, perl -e ...: code the guard cannot read. */
    Decision inlineCode(ShellCommand c) {
        String name = c.name();
        if (INLINE.contains(name) && c.args().stream().anyMatch(a -> a.equals("-e") || a.equals("--eval") || a.equals("-p") || a.equals("--print") || a.equals("-c") || a.equals("eval"))) {
            return Decision.ask("`" + name + "` runs inline code the guard cannot check (it could write files outside the task or read secrets). Approve only if you read the code.");
        }
        return null;
    }

    /** A script handed to an interpreter (node x.js, python3 x.py, java X.java) or run by its path (./x.sh). */
    Decision scriptFile(ShellCommand c) {
        String cmd = c.cmd(), name = c.name();
        List<String> args = c.args();
        if (INLINE.contains(name) || name.equals("java")) {
            boolean module = args.contains("-m");
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                if (Set.of("-r", "--require", "--import", "--loader", "-jar").contains(a) && i + 1 < args.size()) {
                    String v = args.get(++i);
                    if (a.equals("-jar") || v.startsWith(".") || v.startsWith("/") || v.startsWith("~")) {
                        Decision d = file(v);
                        if (d.kind() != Decision.Kind.ALLOW) return d;
                    }
                    if (a.equals("-jar")) return null;
                    continue;
                }
                if (a.startsWith("-")) continue;
                if (module) return null; // python -m pytest: a module, not a file
                boolean runner = name.equals("deno") || name.equals("bun");
                if (runner && i == 0 && Set.of("run", "test", "x").contains(a)) continue;
                if (name.equals("java") && !a.endsWith(".java")) return null; // java -cp x Main
                if (runner && !(a.contains("/") || a.contains("."))) return null; // bun run <package script>
                return unlessAllowed(file(a));
            }
            return null;
        }
        if (cmd.contains("/") || cmd.contains("\\")) {
            String path = ShellRules.expandHome(cmd).replace('\\', '/');
            boolean temp = path.startsWith("/tmp/") || path.startsWith("/var/tmp/") || path.startsWith("/dev/shm/")
                    || path.startsWith(System.getProperty("java.io.tmpdir").replace('\\', '/'));
            if (!g.files.relative(path).startsWith("..") || temp || SCRIPT_FILE.matcher(path).matches()) return unlessAllowed(file(cmd));
        }
        return null;
    }

    private static Decision unlessAllowed(Decision d) {
        return d.kind() == Decision.Kind.ALLOW ? null : d;
    }

    /**
     * A file an interpreter is asked to run. Committed, unchanged files (and build output) run freely;
     * a file nobody reviewed - outside the repository, an agent draft, not committed, or changed
     * outside the task scope - needs a human, because the guard cannot see what it does.
     */
    Decision file(String word) {
        String rel = g.files.relative(ShellRules.expandHome(word));
        String why;
        if (rel.startsWith("..")) {
            why = "is outside the repository";
        } else {
            if (Files.isDirectory(g.root.resolve(rel)) || ShellRules.BUILD_OUTPUT.matcher(rel).matches() && !rel.startsWith(".sdlc/")) return Decision.allow();
            Work.Task task = g.task();
            if (rel.startsWith(".sdlc/")) why = "is an agent draft";
            else if (!g.repo.inHead(rel)) why = "is not a committed file of this repository";
            else if (g.repo.changedSinceHead(rel) && (task == null || !Glob.any(task.scope(), rel))) why = "was changed outside the task scope";
            else return Decision.allow();
        }
        return Decision.ask("`" + word + "` " + why + ", so nobody has reviewed what it runs (it could read secrets or edit files the guard protects). Approve only if you read it.");
    }

    /** npx / pnpx / bunx / npm exec / pnpm dlx / yarn dlx / bun x: a package that is not installed here is downloaded and run. */
    Decision packageRunner(ShellCommand c) {
        String name = c.name();
        List<String> args = c.args();
        boolean npx = Set.of("npx", "pnpx", "bunx").contains(name);
        String sub = c.firstArg();
        boolean exec = name.equals("npm") && sub.equals("exec") || (name.equals("pnpm") || name.equals("yarn")) && sub.equals("dlx") || name.equals("bun") && sub.equals("x");
        if (!npx && !exec) return null;
        List<String> rest = npx ? args : args.subList(1, args.size());
        boolean download = sub.equals("dlx");
        String pkg = null;
        for (int i = 0; i < rest.size() && pkg == null; i++) {
            String a = rest.get(i);
            if (a.equals("-c") || a.equals("--call")) return i + 1 < rest.size() ? shell.check(rest.get(i + 1)) : null;
            if (a.equals("-p") || a.equals("--package")) {
                download = true; // the command comes from a package named here
                i++;
            } else if (a.startsWith("--package=") || a.equals("-y") || a.equals("--yes")) {
                download = true;
            } else if (!a.startsWith("-")) {
                pkg = a;
            }
        }
        if (pkg == null) return null;
        String scoped = pkg.startsWith("@") ? pkg.substring(pkg.indexOf('/') + 1) : pkg;
        String bin = scoped.indexOf('@') > 0 ? scoped.substring(0, scoped.indexOf('@')) : scoped;
        boolean local = !bin.contains("/") && g.folders.bases().stream().map(b -> b.resolve("node_modules/.bin"))
                .anyMatch(bins -> Files.exists(bins.resolve(bin)) || Files.exists(bins.resolve(bin + ".cmd")));
        if (!download && local) return null;
        return Decision.ask("`" + name + " " + pkg + "` downloads and runs a package that is not installed in this project. Approve only if you know the package.");
    }
}
