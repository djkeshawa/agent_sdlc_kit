package sdlc.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.Config;

/** Shell rules about environment variables: setting the ones that steer the guard, git or the build, and printing them. */
public final class EnvRules {
    private static final Set<String> ENV_DUMP = Set.of("env", "printenv", "set", "export", "declare", "compgen");
    private static final Pattern GUARD_VAR = Pattern.compile("^(SDLC_|GIT_DIR=|GIT_WORK_TREE=|GIT_CONFIG|HUSKY=0|SKIP=)");
    private static final Pattern IDENTITY_VAR = Pattern.compile("^(JIRA_|CONFLUENCE_|ATLASSIAN_|JENKINS_|SONAR_|GH_TOKEN|GITHUB_TOKEN|GH_HOST|GH_CONFIG_DIR|COPILOT_)");
    private static final Pattern PS_ENV = Pattern.compile("(?is)^\\$env:([A-Za-z_][A-Za-z0-9_]*)(?:=(.*))?$");
    private static final Pattern PROGRAM_VAR = Pattern.compile("^(GIT_SSH|GIT_SSH_COMMAND|GIT_EXTERNAL_DIFF|GIT_ASKPASS|SSH_ASKPASS|GIT_EDITOR|GIT_SEQUENCE_EDITOR|GIT_PAGER|GIT_EXEC_PATH"
            + "|GIT_TEMPLATE_DIR|GIT_PROXY_COMMAND|PAGER|EDITOR|VISUAL)$");
    private static final Pattern BUILD_ENV = Pattern.compile("^(MAVEN_OPTS|MAVEN_ARGS|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|JAVA_OPTS|GRADLE_OPTS|NODE_OPTIONS)$");
    private static final Pattern NPM_CONFIG = Pattern.compile("(?i)^npm_config_(.+)$");
    /** Variables that load code into, or run a script before, every program the command starts. */
    private static final Pattern INJECT_VAR = Pattern.compile("^(LD_PRELOAD|LD_AUDIT|LD_LIBRARY_PATH|DYLD_[A-Z_]+|BASH_ENV|ENV|PROMPT_COMMAND|PERL5OPT|RUBYOPT|PYTHONSTARTUP)$");
    private static final Pattern JVM_VAR = Pattern.compile("^(MAVEN_OPTS|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|JAVA_OPTS|GRADLE_OPTS)$");
    /** JVM options that load an agent or change which certificates, proxies and security settings Java trusts. */
    private static final Pattern JVM_INJECT = Pattern.compile("(?i)^(-javaagent|-agentlib|-agentpath|-Xbootclasspath|--patch-module|-Djava\\.security\\.|-Djavax\\.net\\.ssl\\.|-Dcom\\.sun\\.net\\.ssl\\."
            + "|-Dsdlc\\.|-D(https?|socks)\\.?proxy|-Djdk\\.internal\\.httpclient\\.).*");
    private static final Pattern NODE_INJECT = Pattern.compile("^(-r|--require|--import|--loader|--experimental-loader)(=.*)?$");
    private static final Pattern PROXY_VAR = Pattern.compile("(?i)^(https?_proxy|all_proxy)$");

    private final Guard g;

    EnvRules(Guard g) {
        this.g = g;
    }

    /** NAME=value before a command, after export / set, or as $env:NAME = value. Null when harmless; a refusal, or a question for the human. */
    Decision variable(String assignment) {
        if (GUARD_VAR.matcher(assignment).find()) return Decision.deny("the command changes a variable that controls the guardrails or git.");
        if (IDENTITY_VAR.matcher(assignment).find()) return Decision.deny("the command changes the identity or token the tools run with.");
        int eq = assignment.indexOf('=');
        String name = assignment.substring(0, eq), value = assignment.substring(eq + 1);
        if (PROGRAM_VAR.matcher(name).matches() && !GitRules.harmlessProgram(value)) {
            return Decision.deny("`" + name + "` makes git run `" + ShellRules.brief(value) + "`, a program the guard cannot check.");
        }
        if (INJECT_VAR.matcher(name).matches() && !value.isBlank()) {
            return Decision.deny("`" + name + "` loads code into every program the command starts, the guard's own tool included.");
        }
        List<String> options = List.of(value.trim().split("\\s+"));
        if (JVM_VAR.matcher(name).matches() && options.stream().anyMatch(o -> JVM_INJECT.matcher(o).matches())) {
            return Decision.deny("`" + name + "` loads an agent into, or changes the security settings of, every Java program the command starts.");
        }
        if (name.equals("NODE_OPTIONS") && options.stream().anyMatch(o -> NODE_INJECT.matcher(o).matches())) {
            return Decision.deny("`NODE_OPTIONS` loads a script into every Node program the command starts.");
        }
        String build = buildEnvProblem(g.cfg, name, value);
        if (build != null) return Decision.deny(build);
        if (PROXY_VAR.matcher(name).matches() && !value.isBlank()) {
            return Decision.ask("`" + name + "` sends the tools' network traffic through `" + ShellRules.brief(value) + "`. Approve only if that is your company's proxy.");
        }
        if (name.equalsIgnoreCase("PATH") && writablePathEntry(value) != null) {
            return Decision.ask("`PATH` puts `" + writablePathEntry(value) + "` first, a folder agents can write to: `git`, `mvn` or `java` could then be another program. "
                    + "Approve only if you know what is in it.");
        }
        return null;
    }

    /** A PATH entry inside the repository, in a temporary folder or relative: a place an agent can put a program of its own. Null when there is none. */
    private String writablePathEntry(String value) {
        for (String entry : value.split("[:;]")) {
            String e = entry.strip();
            if (e.isEmpty() || e.startsWith("$") || e.startsWith("%") || e.startsWith("~") || e.length() == 1) continue; // $PATH, $JAVA_HOME/bin, a drive letter
            String clean = e.replace('\\', '/');
            boolean absolute = clean.startsWith("/");
            if (!absolute || clean.startsWith("/tmp") || clean.startsWith("/var/tmp") || clean.startsWith("/dev/shm")) return e;
            try {
                if (Path.of(clean).normalize().startsWith(g.root)) return e;
            } catch (RuntimeException ex) {
                return e;
            }
        }
        return null;
    }

    /**
     * Build options smuggled in through the environment (MAVEN_OPTS=-DskipTests, npm_config_ignore_scripts=true):
     * the reason they are refused, or null. Also used by `sdlc verify` on its own environment.
     */
    public static String buildEnvProblem(Config cfg, String name, String value) {
        List<String> tokens = new ArrayList<>();
        Matcher npm = NPM_CONFIG.matcher(name);
        if (npm.matches()) tokens.add("--" + npm.group(1).toLowerCase(Locale.ROOT).replace('_', '-'));
        else if (BUILD_ENV.matcher(name).matches()) tokens.addAll(List.of(value.trim().split("\\s+")));
        else return null;
        for (String t : tokens) {
            for (String re : cfg.list("build.forbiddenArgs")) {
                if (t.matches(re)) return "`" + name + "` passes `" + t + "` to the build, which switches off tests or quality checks. Fix the failure instead.";
            }
        }
        return null;
    }

    /** PowerShell: $env:MAVEN_OPTS = "-DskipTests". */
    Decision powershellAssignment(ShellCommand c) {
        List<String> words = c.words();
        Matcher ps = PS_ENV.matcher(c.cmd());
        if (!ps.matches()) return null;
        String value = ps.group(2) != null ? ps.group(2) : words.size() > 2 && words.get(1).equals("=") ? String.join(" ", words.subList(2, words.size())) : "";
        return variable(ps.group(1) + "=" + value);
    }

    /** env, printenv, set, export ...: printing every variable would expose tokens; export NAME=value is checked like NAME=value. */
    Decision dump(ShellCommand c) {
        String name = c.name();
        List<String> args = c.args();
        if (ENV_DUMP.contains(name) && (args.isEmpty() || args.contains("-p"))) return Decision.deny("printing all environment variables would expose tokens.");
        if (ENV_DUMP.contains(name)) {
            Decision question = null;
            for (String a : args) {
                if (GUARD_VAR.matcher(a).find() || IDENTITY_VAR.matcher(a).find()) return Decision.deny("the command changes a variable that controls the guardrails, git or the tools' identity.");
                Decision env = ShellRules.ASSIGNMENT.matcher(a).matches() ? variable(a) : null;
                if (env != null && env.denied()) return env;
                if (env != null) question = env;
            }
            if (question != null) return question;
        }
        // ps e / ps eww (BSD style) and ps -E (macOS) print every process's environment
        if (name.equals("ps") && args.stream().anyMatch(a -> a.matches("^[A-Za-z]*e[A-Za-z]*$") || a.matches("^-[A-Za-z]*E[A-Za-z]*$"))) {
            return Decision.deny("`ps` with `e` prints the environment of running programs, tokens included.");
        }
        if ((name.equals("get-childitem") || name.equals("gci") || name.equals("dir") || name.equals("ls"))
                && args.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).startsWith("env:"))) {
            return Decision.deny("listing env: would expose tokens.");
        }
        return null;
    }
}
