package sdlc.guard;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import sdlc.core.Work;
import sdlc.util.Glob;

final class RunRules {
    private static final Set<String> SHELLS = Set.of("bash", "sh", "zsh", "dash", "ksh");
    private static final Set<String> POWERSHELLS = Set.of("powershell", "pwsh");
    private static final Set<String> TEXT_RUNNERS = Set.of("tmux", "screen", "parallel", "sg");
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

    Decision nested(ShellCommand c) {
        String name = c.name();
        List<String> args = c.args();
        if (SHELLS.contains(name)) {
            int i = -1;
            for (int k = 0; k < args.size() && i < 0; k++) if (args.get(k).matches("^-[a-zA-Z]*c[a-zA-Z]*$")) i = k;
            if (i >= 0) return i + 1 < args.size() ? shell.check(args.get(i + 1)) : Decision.allow();
            for (int k = 0; k < args.size(); k++) {
                if (args.get(k).startsWith("-") || args.get(k).startsWith("+")) continue;
                List<String> script = new ArrayList<>(args.subList(k, args.size()));
                if (build.isTool(script.get(0))) return build.check(script.subList(1, script.size()));
                return file(script.get(0));
            }
            return Decision.ask("`" + name + "` reads its commands from what is typed or piped into it; the guard cannot see them. Give the command with -c, or approve only if you know what is piped in.");
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
        if (name.equals("script")) return pseudoTerminal(args);
        if (name.equals("expect")) return expect(args);
        if (name.equals("socat")) return socat(args);
        if (name.equals("watch") || name.equals("flock")) return held(name, args);
        if (TEXT_RUNNERS.contains(name)) return text(args);
        return null;
    }

    // script -c '<command>' [file] (Linux), script [file [command ...]] (BSD and macOS), or a shell that reads what is typed or piped in
    private Decision pseudoTerminal(List<String> args) {
        Decision result = null;
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (a.startsWith("--command=")) result = strictest(result, shell.check(a.substring("--command=".length())));
            else if ((a.equals("--command") || a.matches("^-[a-zA-Z]*c$")) && k + 1 < args.size()) result = strictest(result, shell.check(args.get(++k)));
            else if (a.matches("^-[a-zA-Z]*c.+$")) result = strictest(result, shell.check(a.substring(a.indexOf('c') + 1)));
        }
        if (result != null) return result;
        Decision words = Decision.allow();
        for (int k = 0; k < args.size() && !words.denied(); k++) if (!args.get(k).startsWith("-")) words = words.strictest(shell.command(args.subList(k, args.size())));
        if (words.denied()) return words;
        return words.strictest(Decision.ask("`script` starts a shell in a pseudo terminal; the guard cannot see what is typed or piped into it. Approve only if you know what it runs."));
    }

    private Decision expect(List<String> args) {
        Decision result = null;
        for (int k = 0; k < args.size(); k++) {
            if (!args.get(k).equals("-c") || k + 1 >= args.size()) continue;
            for (String statement : args.get(++k).split("[;\\n]")) {
                String t = statement.strip();
                while (t.startsWith("spawn ") || t.startsWith("exec ") || t.startsWith("system ") || t.startsWith("-noecho ") || t.startsWith("-ignore ")) t = t.substring(t.indexOf(' ') + 1).strip();
                if (!t.isEmpty()) result = strictest(result, shell.check(t));
            }
        }
        Decision unread = Decision.ask("`expect` drives other programs and answers their questions; the guard cannot check its script. Approve only if you read it.");
        return result == null ? unread : result.denied() ? result : result.strictest(unread);
    }

    private Decision socat(List<String> args) {
        Decision result = null;
        for (String a : args) {
            java.util.regex.Matcher m = Pattern.compile("(?is)^(exec|system|shell):(.*)$").matcher(a);
            if (!m.matches()) continue;
            String text = m.group(2);
            result = strictest(result, shell.check(text));
            int comma = text.indexOf(',');
            if (comma > 0) result = strictest(result, shell.check(text.substring(0, comma)));
        }
        return result;
    }

    // watch '<command>' or watch <command ...>; flock <file> -c '<command>' or flock <file> <command ...>
    private Decision held(String name, List<String> args) {
        Decision result = Decision.allow();
        boolean lockFile = name.equals("flock");
        for (int k = 0; k < args.size() && !result.denied(); k++) {
            String a = args.get(k);
            if (a.startsWith("-")) continue;
            if (lockFile && !args.get(Math.max(0, k - 1)).matches("^-[a-zA-Z]*c$")) {
                lockFile = false;
                continue;
            }
            result = result.strictest(a.chars().anyMatch(Character::isWhitespace) ? shell.check(a) : shell.command(args.subList(k, args.size())));
            if (!a.chars().anyMatch(Character::isWhitespace) && !(k > 0 && args.get(k - 1).startsWith("-"))) break;
        }
        return result;
    }

    // tmux new-session '<command>', parallel ::: '<command>', sg group -c '<command>': a word with a blank in it is a command line
    private Decision text(List<String> args) {
        Decision result = null;
        for (String a : args) if (a.chars().anyMatch(Character::isWhitespace)) result = strictest(result, shell.check(a));
        return result;
    }

    private static Decision strictest(Decision so, Decision d) {
        return so == null ? d : so.strictest(d);
    }

    Decision inlineCode(ShellCommand c) {
        String name = c.name();
        if (INLINE.contains(name) && c.args().stream().anyMatch(a -> a.equals("-e") || a.equals("--eval") || a.equals("-p") || a.equals("--print") || a.equals("-c") || a.equals("eval"))) {
            return Decision.ask("`" + name + "` runs inline code the guard cannot check (it could write files outside the task or read secrets). Approve only if you read the code.");
        }
        return null;
    }

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
                if (module) return null;
                boolean runner = name.equals("deno") || name.equals("bun");
                if (runner && i == 0 && Set.of("run", "test", "x").contains(a)) continue;
                if (name.equals("java") && !a.endsWith(".java")) return null;
                if (runner && !(a.contains("/") || a.contains("."))) return null;
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
                download = true;
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
