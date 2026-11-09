package sdlc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Rules for commands an agent runs in the terminal. */
final class ShellRules {
    private static final Set<String> WRAPPERS = Set.of("env", "command", "exec", "time", "nohup", "nice", "builtin", "xargs");
    private static final Set<String> SHELLS = Set.of("bash", "sh", "zsh", "dash", "ksh");
    private static final Set<String> POWERSHELLS = Set.of("powershell", "pwsh");
    private static final Set<String> NETWORK = Set.of("curl", "wget", "invoke-webrequest", "iwr", "invoke-restmethod", "irm", "nc", "ncat", "netcat", "scp", "sftp", "ftp", "ssh", "rsync", "telnet");
    private static final Set<String> SYSTEM = Set.of("sudo", "su", "doas", "runas", "shutdown", "reboot", "mkfs", "dd", "format", "diskpart", "chown", "setx", "reg");
    private static final Set<String> ENV_DUMP = Set.of("env", "printenv", "set", "export", "declare", "compgen");
    private static final Set<String> WRITERS = Set.of("rm", "rmdir", "mv", "tee", "truncate", "touch", "chmod", "ln", "unlink", "shred",
            "set-content", "add-content", "out-file", "remove-item", "move-item", "new-item", "rename-item", "clear-content",
            "del", "erase", "move", "rename", "ren", "rd", "sc", "ac", "ri", "mi", "ni");
    private static final Set<String> INLINE = Set.of("node", "python", "python3", "perl", "ruby", "deno", "bun", "php");
    private static final Set<String> COPIERS = Set.of("cp", "copy", "copy-item", "cpi", "xcopy", "robocopy");
    private static final Pattern SECRET_VAR = Pattern.compile("\\$(\\{)?(env:)?[A-Za-z0-9_]*(TOKEN|SECRET|PASSWORD|PASSWD|PASSPHRASE|API_KEY|APIKEY|CREDENTIAL)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUARD_VAR = Pattern.compile("^(SDLC_|GIT_DIR=|GIT_WORK_TREE=|GIT_CONFIG|HUSKY=0|SKIP=)");
    private static final Pattern PIPE_TO_SHELL = Pattern.compile("(?i)\\b(curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod)\\b[^;&]*\\|\\s*(sudo\\s+)?(bash|sh|zsh|dash|ksh|python3?|node|perl|ruby|pwsh|powershell|iex|invoke-expression)\\b");
    private static final Pattern IDENTITY_VAR = Pattern.compile("^(JIRA_|CONFLUENCE_|ATLASSIAN_|JENKINS_|SONAR_|GH_TOKEN|GITHUB_TOKEN|GH_HOST|GH_CONFIG_DIR|COPILOT_)");
    static final String APPROVAL_WORD = "SDLC-APPROVED";

    private ShellRules() {}

    static Decision check(String line) {
        if (line.toUpperCase(Locale.ROOT).contains(APPROVAL_WORD)) return Decision.deny("approvals are posted by a human in Jira, never by an agent.");
        if (SECRET_VAR.matcher(line).find()) return Decision.deny("the command reads a secret from an environment variable.");
        if (PIPE_TO_SHELL.matcher(line).find()) return Decision.deny("downloaded code is piped straight into an interpreter; it cannot be checked.");
        Decision result = Decision.allow();
        List<List<String>> commands = ShellWords.commands(line);
        Map<String, String> vars = assignments(commands);
        for (List<String> raw : commands) {
            List<String> words = raw.stream().map(w -> substitute(w, vars)).toList();
            Decision d = command(words);
            if (d.denied()) return d;
            result = result.strictest(d).strictest(unresolved(words));
        }
        for (String nested : ShellWords.substitutions(line)) {
            Decision d = check(nested);
            if (d.denied()) return d;
            result = result.strictest(d);
        }
        return result;
    }

    private static final Pattern VAR_REF = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?");
    private static final Pattern ASSIGNMENT = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$", Pattern.DOTALL);
    private static final Set<String> SAFE_ENV = Set.of("HOME", "PWD", "USER", "TMPDIR", "TMP", "TEMP", "USERPROFILE");

    /** NAME=value set anywhere in the line (also after export / declare / local / readonly), in order. */
    private static Map<String, String> assignments(List<List<String>> commands) {
        Map<String, String> vars = new HashMap<>();
        for (List<String> words : commands) {
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (i == 0 && Set.of("export", "declare", "local", "readonly", "typeset").contains(w)) continue;
                Matcher m = ASSIGNMENT.matcher(w);
                if (!m.matches()) {
                    if (i > 0 && !Set.of("export", "declare", "local", "readonly", "typeset").contains(words.get(0))) break;
                    continue;
                }
                vars.put(m.group(1), substitute(m.group(2), vars));
            }
        }
        return vars;
    }

    /** Replaces $NAME and ${NAME} with values set in the line, or a few harmless environment values. */
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

    /** A path built from a variable the guard cannot resolve might name a secret: a human decides. */
    private static Decision unresolved(List<String> words) {
        for (String w : words) {
            if (ASSIGNMENT.matcher(w).matches()) continue;
            if (VAR_REF.matcher(w).find() && (w.contains("/") || w.contains("\\") || w.contains(".") || FileRules.isGlob(w))) {
                return Decision.ask("`" + w + "` builds a path from a variable the guard cannot resolve. Approve only if it cannot name a credential file.");
            }
        }
        return Decision.allow();
    }

    static Decision command(List<String> raw) {
        List<String> words = new ArrayList<>(raw);
        while (!words.isEmpty()) {
            String w = words.get(0);
            if (w.matches("^[A-Za-z_][A-Za-z0-9_]*=.*")) {
                if (GUARD_VAR.matcher(w).find()) return Decision.deny("the command changes a variable that controls the guardrails or git.");
                if (IDENTITY_VAR.matcher(w).find()) return Decision.deny("the command changes the identity or token the tools run with.");
                words.remove(0);
            } else if (WRAPPERS.contains(w) && words.size() > 1) {
                words.remove(0);
                while (!words.isEmpty() && words.get(0).startsWith("-")) words.remove(0);
            } else {
                break;
            }
        }
        if (words.isEmpty()) return Decision.allow();
        for (String w : words) {
            if (FileRules.isSecretWord(w)) return Decision.deny("`" + w + "` holds credentials. Agents never read secrets.");
            if (w.replace('\\', '/').contains(".agent/.build")) return Decision.deny("the compiled tool copy is managed by the tool only.");
        }
        Decision tool = sdlcInvocation(words);
        if (tool != null) return tool;
        String cmd = words.get(0);
        String name = baseName(cmd);
        List<String> args = words.subList(1, words.size());
        Decision tree = recursiveRead(name, args);
        if (tree != null) return tree;

        if (SHELLS.contains(name)) {
            int i = -1; // -c, also combined as in bash -lc / sh -ec
            for (int k = 0; k < args.size() && i < 0; k++) if (args.get(k).matches("^-[a-zA-Z]*c[a-zA-Z]*$")) i = k;
            return i >= 0 && i + 1 < args.size() ? check(args.get(i + 1)) : Decision.allow();
        }
        if (POWERSHELLS.contains(name)) {
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i).toLowerCase(Locale.ROOT);
                if ((a.equals("-command") || a.equals("-c")) && i + 1 < args.size()) return check(String.join(" ", args.subList(i + 1, args.size())));
                if (a.equals("-encodedcommand") || a.equals("-enc") || a.equals("-e")) return Decision.deny("encoded PowerShell commands cannot be checked.");
            }
            return Decision.allow();
        }
        if (name.equals("cmd")) {
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equalsIgnoreCase("/c") || args.get(i).equalsIgnoreCase("/k")) return check(String.join(" ", args.subList(i + 1, args.size())));
            }
            return Decision.allow();
        }
        if (name.equals("eval")) return check(String.join(" ", args));
        if (SYSTEM.contains(name)) return Decision.deny("`" + name + "` changes the machine, not the project.");
        if (ENV_DUMP.contains(name) && (args.isEmpty() || args.contains("-p"))) return Decision.deny("printing all environment variables would expose tokens.");
        if (ENV_DUMP.contains(name)) {
            for (String a : args) {
                if (GUARD_VAR.matcher(a).find() || IDENTITY_VAR.matcher(a).find()) return Decision.deny("the command changes a variable that controls the guardrails, git or the tools' identity.");
            }
        }
        if ((name.equals("get-childitem") || name.equals("gci") || name.equals("dir") || name.equals("ls"))
                && args.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).startsWith("env:"))) {
            return Decision.deny("listing env: would expose tokens.");
        }
        Decision writes = protectedWrite(name, args, raw);
        if (writes != null) return writes;
        if (name.equals("git")) return GitRules.check(args);
        if (name.equals("gh")) return GhRules.check(args);
        List<String> tools = Ctx.cfg.list("build.tools");
        if (tools.contains(cmd) || tools.contains(name)) return buildRule(args);
        if (INLINE.contains(name) && args.stream().anyMatch(a -> a.equals("-e") || a.equals("--eval") || a.equals("-p") || a.equals("--print") || a.equals("-c") || a.equals("eval"))) {
            return Decision.ask("`" + name + "` runs inline code the guard cannot check (it could write files outside the task or read secrets). Approve only if you read the code.");
        }
        if (NETWORK.contains(name)) return Decision.ask("`" + name + "` talks to the network. Approve only if you know the destination and nothing secret is sent.");
        if (Set.of("rm", "remove-item", "ri", "rd", "rmdir", "del").contains(name)) return removeRule(args);
        if ((name.equals("pip") || name.equals("pip3")) && args.contains("install")) return Decision.ask("installs Python packages on this machine.");
        if (Set.of("docker", "podman", "kubectl", "helm", "terraform").contains(name)) return Decision.ask("`" + name + "` can change shared infrastructure.");
        return Decision.allow();
    }

    static String baseName(String cmd) {
        String n = cmd.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".bat", ".ps1")) if (n.endsWith(ext) && !n.equals("gradlew.bat")) n = n.substring(0, n.length() - ext.length());
        if (n.endsWith(".cmd") && !n.equals("mvnw.cmd")) n = n.substring(0, n.length() - 4);
        return n;
    }

    private static final Set<String> GREPS = Set.of("grep", "egrep", "fgrep", "zgrep", "rgrep");
    private static final Set<String> ARCHIVERS = Set.of("tar", "zip", "7z", "7za", "jar", "cpio", "rsync");
    private static final Set<String> SEARCHERS = Set.of("rg", "ag", "ack");

    /**
     * Commands that read every file below a folder (grep -r, tar, zip, cp -r, find -exec ...): refused
     * when the folder holds a credential file, because the secret's own name never appears in the command.
     */
    private static Decision recursiveRead(String name, List<String> args) {
        boolean grep = GREPS.contains(name) && (name.equals("rgrep") || args.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[rR][a-zA-Z]*$")
                || a.startsWith("--recursive") || a.startsWith("--dereference-recursive") || a.equals("--directories=recurse")));
        // rg/ag skip hidden and git-ignored files (local.env) unless told otherwise; ack does not
        boolean search = name.equals("ack") || SEARCHERS.contains(name) && args.stream().anyMatch(a -> a.matches("^-u+$|^-[a-zA-Z]*\\.[a-zA-Z]*$")
                || a.equals("--hidden") || a.startsWith("--no-ignore") || a.equals("--unrestricted") || a.equals("--skip-vcs-ignores") || a.equals("--all-types"));
        boolean find = name.equals("find") && args.stream().anyMatch(a -> a.matches("^-(exec|execdir|ok|okdir)$"));
        boolean copy = COPIERS.contains(name) && args.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[rRa][a-zA-Z]*$") || a.equals("--recursive")
                || a.equalsIgnoreCase("/s") || a.equalsIgnoreCase("/e") || a.equalsIgnoreCase("-recurse"));
        if (!(grep || search || find || copy || ARCHIVERS.contains(name))) return null;
        boolean namedFolder = false;
        for (String a : args.stream().flatMap(x -> ShellWords.braces(x).stream()).toList()) {
            if (a.startsWith("-") || a.isBlank()) continue;
            String home = a.replace("~", System.getProperty("user.home"));
            List<Path> candidates;
            if (FileRules.isGlob(home)) {
                candidates = FileRules.expandGlob(home);
                if (candidates == null) return denyTree(name, a);
            } else {
                try {
                    Path raw = Path.of(home);
                    candidates = List.of(raw.isAbsolute() ? raw : Ctx.root.resolve(raw).normalize());
                } catch (RuntimeException e) {
                    continue;
                }
            }
            for (Path p : candidates) {
                if (!Files.isDirectory(p)) continue;
                namedFolder = true;
                if (FileRules.dirHoldsSecret(p)) return denyTree(name, a);
            }
        }
        // grep -r PATTERN, rg -uu PATTERN, find -exec ... with no path at all search the current folder
        if (!namedFolder && defaultsToCurrentFolder(name, find, args) && FileRules.dirHoldsSecret(Ctx.root)) return denyTree(name, ".");
        return null;
    }

    /** True when the command names no path, so it reads the current folder. */
    private static boolean defaultsToCurrentFolder(String name, boolean find, List<String> args) {
        if (find) return args.isEmpty() || args.get(0).startsWith("-") || args.get(0).equals("(") || args.get(0).equals("!");
        if (!GREPS.contains(name) && !SEARCHERS.contains(name)) return false;
        int positional = 0;
        boolean patternAsOption = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-e") || a.equals("-f") || a.equals("--regexp") || a.equals("--file")) {
                patternAsOption = true;
                i++; // the pattern (or pattern file) that follows is not a path
            } else if (a.startsWith("--regexp=") || a.startsWith("--file=")) {
                patternAsOption = true;
            } else if (!a.startsWith("-")) {
                positional++;
            }
        }
        return (patternAsOption ? positional : positional - 1) <= 0;
    }

    private static Decision denyTree(String name, String folder) {
        return Decision.deny("`" + name + "` would read every file below `" + folder + "`, including a credential file. "
                + "Search tracked files with `git grep`, or name the code folders (src/, docs/) instead.");
    }

    /** java .agent/Sdlc.java ... and .agent/bin/sdlc ...: commands only a human or the IDE runs. */
    private static Decision sdlcInvocation(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).replace('\\', '/');
            if (!(w.endsWith("Sdlc.java") || w.endsWith("bin/sdlc") || w.endsWith("sdlc.cmd") || w.equals("sdlc.Main"))) continue;
            String sub = i + 1 < words.size() ? words.get(i + 1) : "";
            String sub2 = i + 2 < words.size() ? words.get(i + 2) : "";
            if (sub.equals("hook") || sub.equals("git-hook")) return Decision.deny("hooks are run by the IDE and git, not by hand.");
            if (sub.equals("setup") || sub.equals("config") && sub2.equals("apply")) return Decision.deny("setup and config apply change guardrail files; a human runs them.");
            if (sub.equals("review") && sub2.equals("record")) return Gates.reviewRecordAllowed();
            return Decision.allow();
        }
        return null;
    }

    /** Shell writes (redirections, rm/mv/cp/sed -i/Set-Content ...) into guardrail files or code outside the task scope. */
    private static Decision protectedWrite(String name, List<String> args, List<String> raw) {
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < raw.size() - 1; i++) {
            if (raw.get(i).matches("^[0-9]?>>?$|^&>$")) targets.add(raw.get(i + 1));
        }
        if (WRITERS.contains(name)) targets.addAll(args.stream().filter(a -> !a.startsWith("-")).toList());
        if (COPIERS.contains(name) && !args.isEmpty()) targets.add(args.get(args.size() - 1));
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
                List<Path> hits = FileRules.expandGlob(b.replace("~", System.getProperty("user.home")));
                if (hits == null) return Decision.deny("`" + t + "` matches too many files to check.");
                hits.forEach(h -> expanded.add(h.toString()));
            }
        }
        for (String t : expanded) {
            if (t.isBlank() || t.equals("/dev/null") || t.equalsIgnoreCase("nul")) continue;
            String rel = FileRules.relative(t);
            if (FileRules.isProtected(rel)) return Decision.deny("`" + rel + "` is a guardrail or state file. Only a human or the sdlc tool changes it.");
            if (rel.startsWith("..") || !Glob.any(Ctx.cfg.list("paths.code"), rel) || Glob.any(Ctx.cfg.list("paths.agentWritable"), rel)) continue;
            Decision scope = FileRules.scope(rel, Work.currentTask());
            if (scope.denied()) return scope;
        }
        return null;
    }

    private static Decision removeRule(List<String> args) {
        boolean recursive = args.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[rR][a-zA-Z]*$") || a.equalsIgnoreCase("--recursive") || a.equalsIgnoreCase("-recurse") || a.equalsIgnoreCase("/s"));
        for (String a : args) {
            if (a.startsWith("-") || a.equalsIgnoreCase("/s") || a.equalsIgnoreCase("/q")) continue;
            String rel = FileRules.relative(a.replace("~", System.getProperty("user.home")));
            if (a.equals("/") || a.equals("~") || a.equals("*") || a.equals(".") || a.equals("..") || a.startsWith("$HOME") || rel.startsWith("..") || rel.isEmpty() || rel.equals(".git") || rel.startsWith(".git/")) {
                return Decision.deny("refusing to delete `" + a + "` (outside the project, the whole project, or .git).");
            }
        }
        if (!recursive) return Decision.allow();
        boolean buildOutput = args.stream().filter(a -> !a.startsWith("-")).allMatch(a -> FileRules.relative(a).matches("^(.*/)?(target|build|out|dist|coverage|node_modules|\\.next)(/.*)?$"));
        return buildOutput ? Decision.allow() : Decision.ask("recursive delete of " + String.join(" ", args));
    }

    private static Decision buildRule(List<String> args) {
        for (String a : args) {
            for (String re : Ctx.cfg.list("build.forbiddenArgs")) {
                if (a.matches(re)) return Decision.deny("`" + a + "` switches off tests or quality checks, or hides failures. Fix the failure instead.");
            }
        }
        List<String> plain = args.stream().filter(a -> !a.startsWith("-") && !a.contains("=")).toList();
        for (String a : plain) {
            for (String re : Ctx.cfg.list("build.forbiddenGoals")) {
                if (a.matches(re)) return Decision.deny("`" + a + "` belongs to CI or the release manager, not to an agent.");
            }
        }
        for (String a : args) {
            for (String re : Ctx.cfg.list("build.askArgs")) {
                if (a.equals(re) || a.matches(re)) {
                    return Decision.ask("`" + a + "` changes dependencies or test snapshots. Approve only if the LLD asks for it.");
                }
            }
        }
        return Decision.allow();
    }
}
