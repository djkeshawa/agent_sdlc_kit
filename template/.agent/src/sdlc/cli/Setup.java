package sdlc.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.util.Http;
import sdlc.util.Json;
import sdlc.util.Proc;
import sdlc.workflow.IssueTracker;
import sdlc.workflow.Wiki;

/** setup (once per clone), config check / apply, doctor. */
public final class Setup {
    private Setup() {}

    public static int setup(java.util.function.IntSupplier selfTest) throws IOException {
        System.out.println("Setting up the agent SDLC kit in " + Ctx.root);
        if (!Proc.run(Ctx.root, "git", "rev-parse", "--git-dir").ok()) throw new UsageException(Ctx.root + " is not a git repository");
        Proc.run(Ctx.root, "git", "config", "core.hooksPath", ".githooks");
        System.out.println("- git hooks: core.hooksPath = .githooks");
        addLines(Ctx.root.resolve(".gitignore"), List.of("# agent SDLC kit (local only)", ".sdlc/", ".agent/local.env", ".agent/.build/"));
        addLines(Ctx.root.resolve(".gitattributes"), List.of("# agent SDLC kit: shell scripts keep LF, Windows scripts CRLF", ".githooks/* text eol=lf", ".agent/bin/sdlc text eol=lf", "*.cmd text eol=crlf"));
        System.out.println("- .gitignore / .gitattributes updated");
        Path env = Ctx.root.resolve(".agent/local.env");
        if (!Files.exists(env)) {
            Files.copy(Ctx.root.resolve(".agent/local.env.example"), env);
            System.out.println("- created .agent/local.env from the example: fill in your tokens");
        }
        if (!Ctx.WINDOWS) {
            try (Stream<Path> hooks = Files.list(Ctx.root.resolve(".githooks"))) {
                for (Path h : hooks.toList()) h.toFile().setExecutable(true);
            }
            Ctx.root.resolve(".agent/bin/sdlc").toFile().setExecutable(true);
        }
        Files.createDirectories(Ctx.work());
        configApply();
        List<String> problems = Ctx.cfg.problems();
        if (!problems.isEmpty()) {
            System.out.println("\nFix these in .agent/sdlc.yml:");
            problems.forEach(p -> System.out.println("  - " + p));
        }
        System.out.println();
        int self = selfTest.getAsInt();
        System.out.println("""

                Next:
                  1. Fill in .agent/local.env (tokens) and .agent/sdlc.yml (Jira, Confluence, CI)
                  2. java .agent/Sdlc.java doctor          check every connection
                  3. Open the folder in VS Code, run the hook test (docs/02-setup.md, step 8)
                  4. In Copilot Chat: /analyse              learn this codebase once
                  5. Commit on a branch: git switch -c chore/<KEY>-agent-kit""");
        return self;
    }

    private static void addLines(Path file, List<String> lines) throws IOException {
        List<String> have = Files.exists(file) ? Files.readAllLines(file) : new ArrayList<>();
        StringBuilder add = new StringBuilder();
        for (String l : lines) if (!have.contains(l)) add.append(l).append('\n');
        if (add.length() == 0) return;
        String prefix = Files.exists(file) && Files.size(file) > 0 && !Files.readString(file).endsWith("\n") ? "\n" : "";
        Files.writeString(file, (Files.exists(file) ? Files.readString(file) : "") + prefix + add);
    }

    public static int configCheck() {
        List<String> problems = Ctx.cfg.problems();
        System.out.println("Stack: " + Ctx.cfg.stack() + "   verify: " + Ctx.cfg.verifyCommand());
        System.out.println("Jira: " + Ctx.cfg.str("jira.url") + " " + Ctx.cfg.projectKeys() + " (" + (Ctx.cfg.cloud() ? "cloud" : "data center") + ")");
        System.out.println("Confluence: " + Ctx.cfg.str("confluence.url") + " search " + Ctx.cfg.list("confluence.searchSpaces"));
        System.out.println("Gates: test design " + Ctx.cfg.str("gates.testDesign") + ", LLD " + Ctx.cfg.str("gates.lld"));
        System.out.println("Loops: review " + Ctx.cfg.num("loops.review.min", 2) + ".." + Ctx.cfg.num("loops.review.max", 5) + ", QA max " + Ctx.cfg.num("loops.qa.max", 3) + ", CI max " + Ctx.cfg.num("loops.ci.max", 3));
        problems.forEach(p -> System.out.println("PROBLEM " + p));
        System.out.println(problems.isEmpty() ? "Configuration OK." : problems.size() + " problem(s).");
        return problems.isEmpty() ? 0 : 1;
    }

    /** Writes agent models into .github/agents/*.agent.md and generates .vscode/mcp.json. */
    public static int configApply() throws IOException {
        Path agents = Ctx.root.resolve(".github/agents");
        if (Files.isDirectory(agents)) {
            try (Stream<Path> files = Files.list(agents)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".agent.md")).toList()) {
                    String name = f.getFileName().toString().replace(".agent.md", "");
                    String model = Ctx.cfg.str("agents." + name + ".model");
                    String text = Files.readString(f);
                    String updated = setModel(text, model);
                    if (!updated.equals(text)) {
                        Files.writeString(f, updated);
                        System.out.println("- " + name + ": model " + (model.isBlank() ? "(the model picker's choice)" : model));
                    }
                }
            }
        }
        AgentTools.apply();
        Path mcp = Ctx.root.resolve(".vscode/mcp.json");
        if (!Files.exists(mcp) || Files.readString(mcp).contains("\"_generatedBy\"")) {
            Store.writeText(mcp, Json.pretty(mcpConfig()) + "\n");
            System.out.println("- .vscode/mcp.json generated (Atlassian" + (Ctx.cfg.bool("mcp.servers.github", true) ? ", GitHub" : "") + (sonarMcp() ? ", SonarQube" : "") + ", all read-only)");
        } else {
            System.out.println("- .vscode/mcp.json kept (yours; delete it to regenerate)");
        }
        return 0;
    }

    public static String setModel(String text, String model) {
        if (!text.startsWith("---")) return text;
        int end = text.indexOf("\n---", 3);
        if (end < 0) return text;
        List<String> lines = new ArrayList<>(List.of(text.substring(0, end).split("\n", -1)));
        lines.removeIf(l -> l.startsWith("model:"));
        if (!model.isBlank()) {
            String value = model.contains(",") ? "[" + String.join(", ", Stream.of(model.split(",")).map(s -> "'" + s.trim() + "'").toList()) + "]" : "'" + model + "'";
            int at = 1;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("description:")) at = i + 1;
            lines.add(at, "model: " + value);
        }
        return String.join("\n", lines) + text.substring(end);
    }

    private static boolean sonarMcp() {
        return Ctx.cfg.bool("mcp.servers.sonarqube", true) && !Ctx.cfg.str("ci.sonar.url").isBlank();
    }

    static Map<String, Object> mcpConfig() {
        Map<String, Object> servers = new LinkedHashMap<>();
        List<Object> inputs = new ArrayList<>();
        if (Ctx.cfg.bool("mcp.servers.atlassian", true)) {
            if (Ctx.cfg.cloud()) {
                servers.put("atlassian", Store.map("type", "http", "url", "https://mcp.atlassian.com/v2/mcp"));
            } else {
                inputs.add(Store.map("type", "promptString", "id", "jira-pat", "description", "Jira personal access token (read access is enough)", "password", true));
                inputs.add(Store.map("type", "promptString", "id", "confluence-pat", "description", "Confluence personal access token (read access is enough)", "password", true));
                servers.put("atlassian", Store.map("type", "stdio", "command", "docker",
                        "args", List.of("run", "-i", "--rm", "-e", "JIRA_URL", "-e", "JIRA_PERSONAL_TOKEN", "-e", "CONFLUENCE_URL", "-e", "CONFLUENCE_PERSONAL_TOKEN", "-e", "READ_ONLY_MODE", "ghcr.io/sooperset/mcp-atlassian:latest"),
                        "env", Store.map("JIRA_URL", Ctx.cfg.str("jira.url"), "JIRA_PERSONAL_TOKEN", "${input:jira-pat}", "CONFLUENCE_URL", Ctx.cfg.str("confluence.url"),
                                "CONFLUENCE_PERSONAL_TOKEN", "${input:confluence-pat}", "READ_ONLY_MODE", "true")));
            }
        }
        if (Ctx.cfg.bool("mcp.servers.github", true)) {
            String host = Ctx.cfg.str("github.host", "github.com");
            if (host.equals("github.com")) {
                servers.put("github", Store.map("type", "http", "url", "https://api.githubcopilot.com/mcp/readonly"));
            } else {
                inputs.add(Store.map("type", "promptString", "id", "github-pat", "description", "GitHub Enterprise token (read-only repo access)", "password", true));
                servers.put("github", Store.map("type", "stdio", "command", "docker",
                        "args", List.of("run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "-e", "GITHUB_HOST", "-e", "GITHUB_READ_ONLY", "ghcr.io/github/github-mcp-server"),
                        "env", Store.map("GITHUB_PERSONAL_ACCESS_TOKEN", "${input:github-pat}", "GITHUB_HOST", "https://" + host, "GITHUB_READ_ONLY", "1")));
            }
        }
        if (sonarMcp()) {
            inputs.add(Store.map("type", "promptString", "id", "sonar-token", "description", "SonarQube user token", "password", true));
            servers.put("sonarqube", Store.map("type", "stdio", "command", "docker",
                    "args", List.of("run", "-i", "--rm", "-e", "SONARQUBE_TOKEN", "-e", "SONARQUBE_URL", "mcp/sonarqube"),
                    "env", Store.map("SONARQUBE_TOKEN", "${input:sonar-token}", "SONARQUBE_URL", Ctx.cfg.str("ci.sonar.url"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_generatedBy", "sdlc config apply - edit .agent/sdlc.yml and re-run, or delete this key to keep manual edits");
        if (!inputs.isEmpty()) out.put("inputs", inputs);
        out.put("servers", servers);
        return out;
    }

    public static int doctor(IssueTracker issues, Wiki wiki) {
        int bad = 0;
        bad += line("Java " + Runtime.version().feature(), Runtime.version().feature() >= 17, "JDK 17+ needed");
        bad += line("git", Proc.run(Ctx.root, "git", "--version").ok(), "install git");
        bad += line("git hooks active", ".githooks".equals(Proc.run(Ctx.root, "git", "config", "core.hooksPath").out().trim()), "run: java .agent/Sdlc.java setup");
        Proc gh = Proc.run(Ctx.root, "gh", "auth", "status");
        bad += line("gh (GitHub CLI) logged in", gh.ok(), "install gh and run: gh auth login (needed for PRs and the CI loop)");
        if (!Ctx.cfg.cloud()) line("docker (for the Data Center MCP server)", Proc.run(Ctx.root, "docker", "--version").ok(), "optional: install Docker, or use uvx (see docs)");
        List<String> problems = Ctx.cfg.problems();
        bad += line("sdlc.yml", problems.isEmpty(), String.join("; ", problems));
        bad += check("Jira login", issues::login);
        bad += check("Confluence", () -> {
            wiki.ping();
            return "reachable";
        });
        if (!Ctx.cfg.str("ci.jenkins.url").isBlank()) {
            if (Ctx.secret("JENKINS_TOKEN", "").isBlank()) line("Jenkins API token", false, "set JENKINS_USER and JENKINS_TOKEN in .agent/local.env to read failed builds");
            else bad += check("Jenkins", () -> {
                Http.json("GET", Ctx.cfg.str("ci.jenkins.url").replaceAll("/+$", "") + "/api/json?tree=mode", Http.basic(Ctx.secret("JENKINS_USER", ""), Ctx.secret("JENKINS_TOKEN", "")), null);
                return "reachable";
            });
        }
        if (!Ctx.cfg.str("ci.sonar.url").isBlank()) {
            if (Ctx.secret("SONAR_TOKEN", "").isBlank()) line("SonarQube token (none: only public projects can be read)", true, "");
            else bad += check("SonarQube", () -> {
                Object r = Http.json("GET", Ctx.cfg.str("ci.sonar.url").replaceAll("/+$", "") + "/api/authentication/validate", Http.basic(Ctx.secret("SONAR_TOKEN", ""), ""), null);
                if (!"true".equals(Json.str(r, "valid"))) throw new IllegalStateException("token not valid");
                return "token valid";
            });
        }
        bad += line("project profile (" + Ctx.cfg.str("analyse.output") + ")", Files.exists(Ctx.root.resolve(Ctx.cfg.str("analyse.output"))), "run /analyse in Copilot Chat once");
        System.out.println(bad == 0 ? "\nAll good." : "\n" + bad + " problem(s). See the troubleshooting guide.");
        return bad == 0 ? 0 : 1;
    }

    private interface Probe {
        String run() throws Exception;
    }

    private static int check(String what, Probe p) {
        try {
            return line(what + ": " + p.run(), true, "");
        } catch (Exception e) {
            return line(what, false, e.getMessage());
        }
    }

    private static int line(String what, boolean ok, String fix) {
        System.out.println((ok ? "OK    " : "FAIL  ") + what + (ok || fix.isBlank() ? "" : "  ->  " + fix));
        return ok ? 0 : 1;
    }
}
