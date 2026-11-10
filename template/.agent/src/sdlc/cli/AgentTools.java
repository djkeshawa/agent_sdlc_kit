package sdlc.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.util.Json;

/**
 * Generates the Claude Code and Codex setups from the Copilot sources in .github (agents,
 * prompts, skills), so one set of files drives every tool (sdlc.yml: tools). Generated files
 * are guardrail files: agents cannot edit them; re-run `config apply` after changing .github.
 */
public final class AgentTools {
    static final List<String> EVENTS = List.of("PreToolUse:pre-tool-use", "PostToolUse:post-tool-use", "Stop:stop", "SessionStart:session-start");

    record Agent(String name, String description, List<String> tools, String body) {}

    record Prompt(String name, String description, String agent, String argumentHint) {}

    private AgentTools() {}

    static void apply() throws IOException {
        List<String> tools = Ctx.cfg.list("tools");
        if (tools.contains("claude")) claude();
        if (tools.contains("codex")) codex();
    }

    /**
     * The command a hook runs (POSIX shell, Git Bash on Windows). The repository is found from the
     * folder the agent was started in (Claude Code: CLAUDE_PROJECT_DIR), not from wherever its
     * shell has wandered to. A guard that cannot be found refuses the tool call: a `cd` out of the
     * repository, or into another one, must not switch the guard off.
     */
    static String hookCommand(String event, String tool) {
        String missing = event.equals("pre-tool-use")
                ? "echo \"Refused by project guardrail: the guard (.agent/bin/sdlc) was not found. Start the agent inside the repository.\" >&2; exit 2"
                : "exit 0";
        return "d=$(git -C \"${CLAUDE_PROJECT_DIR:-.}\" rev-parse --show-toplevel 2>/dev/null); "
                + "if [ -f \"$d/.agent/bin/sdlc\" ]; then sh \"$d/.agent/bin/sdlc\" hook " + event + " --for " + tool + "; else " + missing + "; fi";
    }

    static Map<String, Object> hooks(String tool) {
        Map<String, Object> hooks = new LinkedHashMap<>();
        for (String e : EVENTS) {
            String[] p = e.split(":");
            Map<String, Object> entry = new LinkedHashMap<>();
            if (p[0].endsWith("ToolUse")) entry.put("matcher", "*");
            entry.put("hooks", List.of(Store.map("type", "command", "command", hookCommand(p[1], tool), "timeout", p[0].equals("Stop") ? 120 : 30)));
            hooks.put(p[0], List.of(entry));
        }
        return hooks;
    }

    // ---------------- Claude Code ----------------

    static void claude() throws IOException {
        Path root = Ctx.root;
        Path settings = root.resolve(".claude/settings.json");
        Map<String, Object> s = Files.exists(settings) ? Json.obj(Json.parse(Files.readString(settings))) : new LinkedHashMap<>();
        s.put("hooks", hooks("claude"));
        Store.writeText(settings, Json.pretty(s) + "\n");
        for (Agent a : agents()) {
            StringBuilder fm = new StringBuilder("---\nname: " + a.name() + "\ndescription: " + yamlString(a.description()) + "\n");
            List<String> mapped = claudeTools(a.tools());
            if (mapped != null) fm.append("tools: ").append(String.join(", ", mapped)).append('\n');
            Store.writeText(root.resolve(".claude/agents/" + a.name() + ".md"), fm + "---\n" + generated() + a.body());
        }
        for (Prompt p : prompts()) {
            String body = p.agent().equals("orchestrator")
                    ? "You are the orchestrator for: $ARGUMENTS\nFollow `.claude/agents/orchestrator.md` exactly. Start the other agents it names as subagents (Task tool); never edit code yourself.\n"
                    : "Use the `" + p.agent() + "` subagent for: $ARGUMENTS\nGive it only the key. When it finishes, report its result to the user as it is.\n";
            Store.writeText(root.resolve(".claude/commands/" + p.name() + ".md"),
                    "---\ndescription: " + yamlString(p.description()) + "\nargument-hint: " + yamlString(p.argumentHint()) + "\n---\n" + generated() + body);
        }
        copySkills(root.resolve(".claude/skills"));
        Path claudeMd = root.resolve("CLAUDE.md");
        String import_ = "@AGENTS.md";
        if (!Files.exists(claudeMd)) Store.writeText(claudeMd, "# Claude Code\n\nThe rules for every agent in this repository:\n\n" + import_ + "\n");
        else if (!Files.readString(claudeMd).contains(import_)) Files.writeString(claudeMd, Files.readString(claudeMd).stripTrailing() + "\n\n" + import_ + "\n");
        System.out.println("- Claude Code: .claude/settings.json hooks, .claude/agents, .claude/commands, .claude/skills, CLAUDE.md -> AGENTS.md");
    }

    /** Copilot tool sets -> Claude Code tool names; null = inherit all (agents that use MCP servers). */
    public static List<String> claudeTools(List<String> copilot) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : copilot) {
            switch (t) {
                case "read" -> out.addAll(List.of("Read", "Grep", "Glob"));
                case "search" -> out.addAll(List.of("Grep", "Glob"));
                case "edit" -> out.addAll(List.of("Edit", "Write", "MultiEdit"));
                case "execute" -> out.add("Bash");
                case "todo" -> out.add("TodoWrite");
                case "agent" -> out.add("Task");
                case "web" -> out.addAll(List.of("WebFetch", "WebSearch"));
                default -> {
                    if (t.endsWith("/*")) return null;
                }
            }
        }
        return new ArrayList<>(out);
    }

    // ---------------- Codex ----------------

    static void codex() throws IOException {
        Path root = Ctx.root;
        Store.writeText(root.resolve(".codex/hooks.json"), Json.pretty(Store.map("hooks", hooks("codex"))) + "\n");
        for (Agent a : agents()) {
            String toml = "# " + generated().trim() + "\n"
                    + "name = " + Json.write(a.name()) + "\n"
                    + "description = " + Json.write(a.description()) + "\n"
                    + (a.tools().contains("edit") || a.tools().contains("execute") ? "" : "sandbox_mode = \"read-only\"\n")
                    + "developer_instructions = " + Json.write(a.body()) + "\n";
            Store.writeText(root.resolve(".codex/agents/" + a.name() + ".toml"), toml);
        }
        Path skills = root.resolve(".agents/skills");
        copySkills(skills);
        for (Prompt p : prompts()) {
            String body = p.agent().equals("orchestrator")
                    ? "You are the orchestrator. Follow the developer_instructions in `.codex/agents/orchestrator.toml` exactly, for the story the user named. Start the other agents it names (designer, implementer, reviewer, qa-verifier, ci-fixer) as subagents by name; never edit code yourself.\n"
                    : "Start the `" + p.agent() + "` agent (`.codex/agents/" + p.agent() + ".toml`) as a subagent with only the key the user named, then report its result as it is.\n";
            Store.writeText(skills.resolve(p.name()).resolve("SKILL.md"), "---\nname: " + p.name() + "\ndescription: " + yamlString(p.description()
                    + " Use when the user types /" + p.name() + " " + p.argumentHint() + " or asks for this step.") + "\n---\n" + generated() + body);
        }
        System.out.println("- Codex: .codex/hooks.json, .codex/agents/*.toml, .agents/skills (skills and /analyse, /test-design, /design, /feature, /review, /fix-ci); AGENTS.md is read natively");
        System.out.println("  Codex asks you once to trust the hooks; check with: codex, then a guarded command (docs/02 step 8)");
    }

    // ---------------- sources ----------------

    static List<Agent> agents() throws IOException {
        List<Agent> out = new ArrayList<>();
        for (Path f : list(Ctx.root.resolve(".github/agents"), ".agent.md")) {
            String[] fm = frontMatter(Files.readString(f));
            Map<String, String> m = fields(fm[0]);
            out.add(new Agent(m.getOrDefault("name", f.getFileName().toString().replace(".agent.md", "")), unquote(m.getOrDefault("description", "")),
                    listValue(m.getOrDefault("tools", "")), fm[1].strip() + "\n"));
        }
        return out;
    }

    static List<Prompt> prompts() throws IOException {
        List<Prompt> out = new ArrayList<>();
        for (Path f : list(Ctx.root.resolve(".github/prompts"), ".prompt.md")) {
            Map<String, String> m = fields(frontMatter(Files.readString(f))[0]);
            out.add(new Prompt(m.getOrDefault("name", f.getFileName().toString().replace(".prompt.md", "")), unquote(m.getOrDefault("description", "")),
                    m.getOrDefault("agent", "orchestrator"), unquote(m.getOrDefault("argument-hint", ""))));
        }
        return out;
    }

    private static void copySkills(Path to) throws IOException {
        Path from = Ctx.root.resolve(".github/skills");
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) Store.writeText(to.resolve(from.relativize(f).toString()), Files.readString(f));
        }
    }

    private static List<Path> list(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    /** [front matter, body] of a Markdown file with a --- block. */
    public static String[] frontMatter(String text) {
        if (!text.startsWith("---")) return new String[] {"", text};
        int end = text.indexOf("\n---", 3);
        if (end < 0) return new String[] {"", text};
        int bodyStart = text.indexOf('\n', end + 4);
        return new String[] {text.substring(3, end), bodyStart < 0 ? "" : text.substring(bodyStart + 1)};
    }

    public static Map<String, String> fields(String fm) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : fm.split("\n")) {
            int c = line.indexOf(':');
            if (c > 0 && !line.startsWith(" ")) m.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
        }
        return m;
    }

    public static List<String> listValue(String v) {
        List<String> out = new ArrayList<>();
        String s = v.replace("[", "").replace("]", "");
        for (String p : s.split(",")) if (!unquote(p.trim()).isBlank()) out.add(unquote(p.trim()));
        return out;
    }

    public static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("'") && t.endsWith("'"))) return t.substring(1, t.length() - 1).replace("''", "'");
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1);
        return t;
    }

    static String yamlString(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String generated() {
        return "<!-- generated by `java .agent/Sdlc.java config apply` from .github; edit the source there -->\n";
    }
}
