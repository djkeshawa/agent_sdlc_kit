package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import sdlc.cli.AgentTools;
import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.mandate.Mandate;
import sdlc.mandate.MandateCli;
import sdlc.mandate.SelfApproval;
import sdlc.util.Json;
import sdlc.util.Proc;

// The kit's own prompt files, generated for Claude Code and Codex as config apply does in a repository.
final class PromptTests {
    private PromptTests() {}

    // Each sentence is a rule of /mandate; an edit of the prompt that drops one fails its check.
    static final Map<String, String> MANDATE_RULES = Map.of(
            "the agent never writes the approver's sentence", "Never write `failsIf`",
            "the agent never approves", "Never run `mandate approve`, and never imitate it",
            "the agent leaves an approved mandate alone", "Never edit a mandate a person approved",
            "the intent is not read from the diff", "Do not read the diff to write the intent",
            "the agent runs mandate draft until it passes", "Run `java .agent/Sdlc.java mandate draft <ID>`. When it reports a problem, fix the file and run it again, until it exits 0.",
            "the hand-over names the command a person runs", "for the person to run in a terminal: `java .agent/Sdlc.java mandate approve <ID>`",
            "the agent stops after the hand-over", "Then stop. Do not start the change in this turn unless the user asked for that.");

    // The tool on its own (the packaged sdlc) carries no prompts; the kit's run counts these checks (verify/selftest-count.txt).
    static void run(Path kit) throws IOException {
        if (!Files.isRegularFile(kit.resolve(".github/prompts/mandate.prompt.md"))) return;
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path repo = Files.createTempDirectory("sdlc-prompts").toRealPath();
        Path drafted = Files.createTempDirectory("sdlc-prompt-example").toRealPath();
        try {
            String body = generated(kit, repo);
            rules(body);
            example(body, drafted);
        } finally {
            Ctx.root = savedRoot;
            Ctx.cfg = savedCfg;
            delete(repo);
            delete(drafted);
        }
    }

    private static String generated(Path kit, Path repo) throws IOException {
        Path prompts = kit.resolve(".github/prompts");
        List<Path> files = Files.isDirectory(prompts) ? list(prompts) : List.of();
        for (Path f : files) copy(f, repo.resolve(".github/prompts").resolve(sdlc.util.Fs.name(f)));
        write(repo, ".agent/sdlc.yml", "stack: java-maven\ntools: [claude, codex]\n");
        Proc.run(repo, "git", "init", "-q", "-b", "develop");
        Ctx.init(repo);
        PrintStream out = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            AgentTools.apply();
        } finally {
            System.setOut(out);
        }
        AgentTools.Prompt mandate = AgentTools.prompts().stream().filter(p -> p.name().equals("mandate")).findFirst().orElse(null);
        check("prompts: the kit ships /mandate, found as a prompt with its own instructions and no agent to hand over to", mandate != null
                && mandate.agent().equals(AgentTools.OWN_INSTRUCTIONS) && mandate.argumentHint().equals("ID") && !mandate.body().isBlank());
        String body = mandate == null ? "" : mandate.body();
        String claude = read(repo.resolve(".claude/commands/mandate.md"));
        check("prompts: config apply writes /mandate for Claude Code with the prompt's own instructions and what the user typed",
                claude.startsWith("---\ndescription: ") && claude.contains("\nargument-hint: 'ID'\n") && claude.contains(body) && claude.contains("$ARGUMENTS")
                        && !claude.contains("subagent") && !claude.contains("orchestrator"));
        String codex = read(repo.resolve(".agents/skills/mandate/SKILL.md"));
        check("prompts: config apply writes /mandate for Codex as a skill with the same instructions",
                codex.startsWith("---\nname: mandate\ndescription: ") && codex.contains("/mandate ID") && codex.contains(body)
                        && !codex.contains("subagent") && !codex.contains("orchestrator"));
        boolean all = !files.isEmpty();
        for (Path f : files) {
            String name = sdlc.util.Fs.name(f).replace(".prompt.md", "");
            all &= Files.isRegularFile(repo.resolve(".claude/commands/" + name + ".md")) && Files.isRegularFile(repo.resolve(".agents/skills/" + name + "/SKILL.md"));
        }
        check("prompts: every prompt of the kit becomes a Claude Code command and a Codex skill", all);
        check("prompts: a prompt that names an agent still hands over to it",
                read(repo.resolve(".claude/commands/analyse.md")).contains("Use the `analyst` subagent for: $ARGUMENTS")
                        && read(repo.resolve(".agents/skills/analyse/SKILL.md")).contains("Start the `analyst` agent")
                        && read(repo.resolve(".claude/commands/feature.md")).contains("You are the orchestrator for: $ARGUMENTS"));
        return body;
    }

    private static void rules(String body) {
        for (Map.Entry<String, String> r : MANDATE_RULES.entrySet()) check("/mandate: " + r.getKey(), body.contains(r.getValue()));
        // the blind first line works only when the person writes it alone
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        check("/mandate: the prompt suggests nothing of the approver's sentence", !body.isBlank() && !lower.contains("fails if") && !lower.contains("failsif\""));
    }

    // The example in the prompt, written with its test files, is a draft mandate draft accepts as it is.
    private static void example(String body, Path repo) throws IOException {
        body = body.replace("\r\n", "\n"); // a Windows checkout may give the prompt CRLF line ends
        int start = body.indexOf("```json\n");
        int end = start < 0 ? -1 : body.indexOf("```", start + 8);
        String json = end < 0 ? "" : body.substring(start + 8, end).lines().map(String::strip).reduce("", (a, b) -> a + b + "\n");
        Map<String, Object> shape;
        try {
            shape = Json.obj(Json.parseStrict(json));
        } catch (RuntimeException e) {
            shape = Map.of();
        }
        check("/mandate: the example holds only the fields the agent writes", !shape.isEmpty() && Set.of("intent", "scope", "oracle", "mustStayTrue").containsAll(shape.keySet())
                && shape.keySet().containsAll(Set.of("intent", "scope", "oracle", "mustStayTrue")));
        Proc.run(repo, "git", "init", "-q", "-b", "develop");
        write(repo, "docs/mandates/example.json", json);
        for (String test : tests(shape)) write(repo, test, "class Example {}\n");
        Ctx.init(repo);
        PrintStream out = System.out;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = shape.isEmpty() ? -1 : MandateCli.run(new String[] {"mandate", "draft", "example"}, Map.of());
        } finally {
            System.setOut(out);
        }
        Mandate m = null;
        try {
            m = Mandate.load(repo, "example");
        } catch (RuntimeException e) {
            // reported by the check below
        }
        check("/mandate: mandate draft accepts the prompt's example as it is, and does not approve it", code == 0
                && sink.toString(StandardCharsets.UTF_8).contains("This is a draft. It is not approved.") && m != null && m.failsIf() == null
                && m.locked().size() == m.mustStayTrue().size() && SelfApproval.of(repo, "example") == null);
    }

    private static List<String> tests(Map<String, Object> shape) {
        List<String> out = new java.util.ArrayList<>();
        if (shape.get("oracle") instanceof Map<?, ?> o && o.get("scenarios") instanceof List<?> s)
            for (Object x : s) if (x instanceof String t) out.add(t.contains("#") ? t.substring(0, t.indexOf('#')) : t);
        if (shape.get("mustStayTrue") instanceof List<?> l)
            for (Object x : l) if (x instanceof Map<?, ?> t && t.get("test") instanceof String p) out.add(p);
        return out.stream().filter(t -> !t.isBlank() && !t.contains("*") && !t.contains("..") && !t.startsWith("/")).toList();
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> sdlc.util.Fs.name(p).endsWith(".prompt.md")).sorted().toList();
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        sdlc.util.Fs.makeParent(to);
        Files.copy(from, to);
    }

    private static void write(Path repo, String rel, String text) throws IOException {
        Path f = repo.resolve(rel);
        sdlc.util.Fs.makeParent(f);
        Files.writeString(f, text);
    }

    private static String read(Path f) throws IOException {
        return Files.isRegularFile(f) ? Files.readString(f) : "";
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }
}
