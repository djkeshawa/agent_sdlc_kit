package sdlc.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.Markers;
import sdlc.util.ShellWords;

public final class ShellRules {
    static final Set<String> COPIERS = Set.of("cp", "copy", "copy-item", "cpi", "xcopy", "robocopy");
    static final Pattern BUILD_OUTPUT = Pattern.compile("^(.*/)?(target|build|out|dist|coverage|node_modules|\\.next)(/.*)?$");
    static final Pattern ASSIGNMENT = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$", Pattern.DOTALL);

    private static final Set<String> WRAPPERS = Set.of("env", "command", "exec", "time", "nohup", "nice", "builtin", "xargs");
    private static final Set<String> NETWORK = Set.of("curl", "wget", "invoke-webrequest", "iwr", "invoke-restmethod", "irm", "nc", "ncat", "netcat", "scp", "sftp", "ftp", "ssh", "rsync", "telnet");
    private static final Set<String> SYSTEM = Set.of("sudo", "su", "doas", "runas", "shutdown", "reboot", "mkfs", "dd", "format", "diskpart", "chown", "setx", "reg");
    private static final Set<String> INFRASTRUCTURE = Set.of("docker", "podman", "kubectl", "helm", "terraform");
    private static final Pattern SECRET_VAR = Pattern.compile("\\$(\\{)?(env:)?[A-Za-z0-9_]*(TOKEN|SECRET|PASSWORD|PASSWD|PASSPHRASE|API_KEY|APIKEY|CREDENTIAL)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIPE_TO_SHELL = Pattern.compile("(?i)\\b(curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod)\\b[^;&]*\\|\\s*(sudo\\s+)?(bash|sh|zsh|dash|ksh|python3?|node|perl|ruby|pwsh|powershell|iex|invoke-expression)\\b");
    private static final Pattern VAR_REF = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?");
    private static final Set<String> SAFE_ENV = Set.of("HOME", "PWD", "USER", "TMPDIR", "TMP", "TEMP", "USERPROFILE");
    private static final Set<String> DECLARERS = Set.of("export", "declare", "local", "readonly", "typeset");
    private static final Set<String> PREDICTABLE = Set.of("date", "pwd", "whoami", "hostname", "uname", "nproc", "id", "mktemp", "basename", "dirname",
            "which", "lsof", "pgrep", "pidof", "wc", "expr", "seq", "true");
    private static final Set<String> PREDICTABLE_GIT = Set.of("rev-parse", "merge-base", "describe", "symbolic-ref", "branch", "rev-list", "diff", "ls-files");

    private final Guard g;
    private final EnvRules env;
    private final CdRules cd;
    private final BuildRules build;
    private final RunRules run;
    private final WriteRules write;
    private final List<ShellRule> rules;
    private int depth;
    private static final int MAX_NESTING = 12;

    ShellRules(Guard g) {
        this.g = g;
        this.env = new EnvRules(g);
        this.cd = new CdRules(g);
        this.build = new BuildRules(g);
        this.run = new RunRules(g, this, build);
        this.write = new WriteRules(g);
        ReadRules read = new ReadRules(g);
        this.rules = List.of(
                env::powershellAssignment,
                this::secretsAndToolCopy,
                this::ownTool,
                read::wholeFolder,
                cd::check,
                run::nested,
                this::machine,
                env::dump,
                write::targets,
                this::gitAndPatches,
                this::gh,
                this::buildAndPackages,
                run::inlineCode,
                run::scriptFile,
                this::network,
                write::remove,
                this::installers,
                this::infrastructure);
    }

    public Decision check(String line) {
        depth++;
        try {
            if (depth > MAX_NESTING) return Decision.deny("the command nests commands more than " + MAX_NESTING + " levels deep ($(...), sh -c, eval); the guard cannot check that. Write it in plain steps.");
            return line(line);
        } finally {
            if (--depth == 0) g.folders.forgetLine();
        }
    }

    private Decision line(String line) {
        if (line.toUpperCase(Locale.ROOT).contains(Markers.APPROVAL_WORD)) return Decision.deny("approvals are posted by a human in Jira, never by an agent.");
        if (SECRET_VAR.matcher(line).find()) return Decision.deny("the command reads a secret from an environment variable.");
        if (PIPE_TO_SHELL.matcher(line).find()) return Decision.deny("downloaded code is piped straight into an interpreter; it cannot be checked.");
        List<List<String>> commands = ShellWords.commands(line);
        Decision result = commands(commands);
        if (result.denied()) return result;
        if (line.indexOf('\\') >= 0) { // sh/bash read local.e\nv as local.env; cmd and PowerShell keep the backslash: check both
            List<List<String>> posix = ShellWords.commands(line, true);
            if (!posix.equals(commands)) {
                Decision d = commands(posix);
                if (d.denied()) return d;
                result = result.strictest(d);
            }
        }
        for (String nested : ShellWords.substitutions(line)) {
            Decision d = check(nested);
            if (d.denied()) return d;
            result = result.strictest(d);
        }
        for (String sub : ShellWords.liveSubstitutions(line)) {
            if (!predictable(sub)) {
                result = result.strictest(Decision.ask("the output of `" + brief(sub) + "` becomes part of the command, and the guard cannot see it. "
                        + "Approve only if it cannot name a credential or guardrail file."));
            }
        }
        return result;
    }

    private Decision commands(List<List<String>> commands) {
        Decision result = Decision.allow();
        Map<String, String> vars = assignments(commands);
        Path lineStart = g.folders.cd();
        try {
            for (List<String> raw : commands) {
                List<String> words = raw.stream().map(w -> substitute(w, vars)).toList();
                Decision d = command(words);
                if (d.denied()) return d;
                result = result.strictest(d).strictest(unresolved(words));
                cd.follow(words, lineStart);
            }
        } finally {
            g.folders.returnTo(lineStart);
        }
        return result;
    }

    Decision command(List<String> raw) {
        List<String> words = new ArrayList<>(raw);
        Decision question = Decision.allow();
        while (!words.isEmpty()) {
            String w = words.get(0);
            if (w.matches("(?s)^[A-Za-z_][A-Za-z0-9_]*=.*")) {
                Decision variable = env.variable(w);
                if (variable != null && variable.denied()) return variable;
                if (variable != null) question = question.strictest(variable);
                words.remove(0);
            } else if (WRAPPERS.contains(w) && words.size() > 1) {
                words.remove(0);
                while (!words.isEmpty() && words.get(0).startsWith("-")) words.remove(0);
            } else {
                break;
            }
        }
        if (words.isEmpty()) return question;
        ShellCommand command = new ShellCommand(raw, words);
        for (ShellRule rule : rules) {
            Decision d = rule.check(command);
            if (d != null) return d.strictest(question);
        }
        return question;
    }

    private Decision secretsAndToolCopy(ShellCommand c) {
        for (String w : c.words()) {
            if (w.toUpperCase(Locale.ROOT).contains(Markers.APPROVAL_WORD)) return Decision.deny("approvals are posted by a human in Jira, never by an agent.");
            if (g.files.isSecretWord(w)) return Decision.deny("`" + w + "` holds credentials or hidden test data. Agents never read it.");
            if (w.replace('\\', '/').contains(".agent/.build") || !w.startsWith("-") && (g.files.relative(w).startsWith(".agent/.build")
                    || g.files.otherReadings(w).stream().anyMatch(r -> r.startsWith(".agent/.build")))) return Decision.deny("the compiled tool copy is managed by the tool only.");
        }
        return null;
    }

    private Decision ownTool(ShellCommand c) {
        List<String> words = c.words();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).replace('\\', '/');
            boolean run = i == 0 || isRunBy(words, i);
            boolean kit = run && (w.endsWith(".agent/Sdlc.java") || w.endsWith(".agent/bin/sdlc") || w.endsWith(".agent/bin/sdlc.cmd") || w.equals("sdlc.Main"));
            boolean plain = w.equals("sdlc") && i == 0;
            boolean named = run && (w.equals("sdlc") || w.endsWith("/sdlc") || w.endsWith("sdlc.cmd") || w.endsWith("Sdlc.java")) || w.equals("sdlc");
            if (!(kit || plain || named)) continue;
            boolean vouched = kit || plain;
            int written = c.raw().size() - (words.size() - i);
            if (!vouched && !w.contains("/") && i > 0 && !wrappersOnly(c.raw().subList(0, Math.max(0, written)))) continue;
            String sub = i + 1 < words.size() ? words.get(i + 1) : "";
            String sub2 = i + 2 < words.size() ? words.get(i + 2) : "";
            if (kit && i > 1 && words.subList(1, i).stream().anyMatch(o -> o.startsWith("-"))) {
                return Decision.deny("Java options (`" + brief(String.join(" ", words.subList(1, i))) + "`) change how the guard's tool runs. Run it plainly: java .agent/Sdlc.java <command>");
            }
            if (sub.equals("hook") || sub.equals("git-hook")) return Decision.deny("hooks are run by the IDE and git, not by hand.");
            if (sub.equals("setup") || sub.equals("upgrade") || sub.equals("config") && sub2.equals("apply")) return Decision.deny("setup, upgrade and config apply change guardrail files; a human runs them.");
            if (sub.equals("review") && sub2.equals("record")) return g.gate.recordReview();
            if (sub.equals("tests") && sub2.equals("lock")) return Decision.deny("locking acceptance tests is QE's approval; a human runs tests lock.");
            if (sub.equals("mandate") && sub2.equals("approve")) return Decision.deny("approving a mandate is a person's decision; a human runs mandate approve.");
            if (sub.equals("eval")) return Decision.deny("the scoping eval is collected and scored by a human (its report shows the ground truth); agents only answer cases with /eval-scoping.");
            if (vouched) return Decision.allow();
        }
        return null;
    }

    private static final Set<String> INTERPRETERS = Set.of("java", "sh", "bash", "dash", "zsh", "ksh", "cmd");

    private static boolean isRunBy(List<String> words, int i) {
        String interpreter = baseName(words.get(0));
        if (!INTERPRETERS.contains(interpreter)) return false;
        int operand = 1;
        while (operand < i && (words.get(operand).startsWith("-") || words.get(operand).equalsIgnoreCase("/c"))) operand++;
        if (operand == i) return true;
        return interpreter.equals("java") && words.subList(1, i).stream().anyMatch(o -> o.startsWith("-")) && words.subList(1, i).stream().noneMatch(o -> o.endsWith(".java") || o.endsWith(".jar"));
    }
    private static final Set<String> BEFORE_A_COMMAND = Set.of("env", "command", "exec", "nohup", "time", "nice", "sudo", "doas", "stdbuf", "timeout", "builtin", "xargs");

    private static boolean wrappersOnly(List<String> before) {
        for (int k = 0; k < before.size(); k++) {
            String w = before.get(k).replace('\\', '/');
            String name = w.substring(w.lastIndexOf('/') + 1);
            if (BEFORE_A_COMMAND.contains(name) || w.startsWith("-") || w.contains("=")) continue;
            if (k > 0 && before.get(k - 1).startsWith("-")) continue;
            if (name.matches("\\d+[smhd]?")) continue;
            return false;
        }
        return true;
    }

    private Decision machine(ShellCommand c) {
        return SYSTEM.contains(c.name()) ? Decision.deny("`" + c.name() + "` changes the machine, not the project.") : null;
    }

    private Decision gitAndPatches(ShellCommand c) {
        Decision unseen = write.patchOrArchive(c);
        return c.name().equals("git") ? g.git.check(c.args()).strictest(unseen) : unseen;
    }

    private Decision gh(ShellCommand c) {
        return c.name().equals("gh") ? g.gh.check(c.args()) : null;
    }

    private Decision buildAndPackages(ShellCommand c) {
        Decision runner = run.packageRunner(c);
        return build.isTool(c.cmd()) ? build.check(c.args()).strictest(runner) : runner;
    }

    private Decision network(ShellCommand c) {
        return NETWORK.contains(c.name()) ? Decision.ask("`" + c.name() + "` talks to the network. Approve only if you know the destination and nothing secret is sent.") : null;
    }

    private Decision installers(ShellCommand c) {
        boolean pip = c.name().equals("pip") || c.name().equals("pip3");
        return pip && c.args().contains("install") ? Decision.ask("installs Python packages on this machine.") : null;
    }

    private Decision infrastructure(ShellCommand c) {
        return INFRASTRUCTURE.contains(c.name()) ? Decision.ask("`" + c.name() + "` can change shared infrastructure.") : null;
    }

    private static Map<String, String> assignments(List<List<String>> commands) {
        Map<String, String> vars = new HashMap<>();
        for (List<String> words : commands) {
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (i == 0 && DECLARERS.contains(w)) continue;
                Matcher m = ASSIGNMENT.matcher(w);
                if (!m.matches()) {
                    if (i > 0 && !DECLARERS.contains(words.get(0))) break;
                    continue;
                }
                vars.put(m.group(1), substitute(m.group(2), vars));
            }
        }
        return vars;
    }

    private static String substitute(String word, Map<String, String> vars) {
        if (word.indexOf('$') < 0) return word;
        Matcher m = VAR_REF.matcher(word);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = vars.containsKey(name) ? vars.get(name) : SAFE_ENV.contains(name) ? System.getenv(name) : null;
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Decision unresolved(List<String> words) {
        for (String w : words) {
            if (ASSIGNMENT.matcher(w).matches()) continue;
            if (VAR_REF.matcher(w).find() && (w.contains("/") || w.contains("\\") || w.contains(".") || FileRules.isGlob(w))) {
                return Decision.ask("`" + w + "` builds a path from a variable the guard cannot resolve. Approve only if it cannot name a credential file.");
            }
        }
        return Decision.allow();
    }

    static boolean predictable(String sub) {
        String t = sub.strip();
        if (t.isEmpty() || t.startsWith("(")) return true;
        if (t.matches("(?s)^cat\\s*<<-?\\s*(['\"]\\w+['\"]|\\\\\\w+)\\s*$")) return true;
        for (List<String> words : ShellWords.commands(t)) {
            String name = baseName(words.get(0));
            if (PREDICTABLE.contains(name)) continue;
            if (name.equals("command") && words.size() > 1 && words.get(1).equals("-v")) continue;
            if (name.equals("git") && words.size() > 1 && PREDICTABLE_GIT.contains(words.get(1))
                    && words.stream().noneMatch(w -> w.equals("-o") || w.equals("--others") || w.equals("-i") || w.equals("--ignored"))) continue;
            return false;
        }
        return true;
    }

    static String brief(String text) {
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() > 60 ? t.substring(0, 57) + "..." : t;
    }

    static String expandHome(String word) {
        return word.startsWith("~") ? System.getProperty("user.home") + word.substring(1) : word;
    }

    static String baseName(String cmd) {
        String n = cmd.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".bat", ".ps1")) if (n.endsWith(ext) && !n.equals("gradlew.bat")) n = n.substring(0, n.length() - ext.length());
        if (n.endsWith(".cmd") && !n.equals("mvnw.cmd")) n = n.substring(0, n.length() - 4);
        return n;
    }
}
