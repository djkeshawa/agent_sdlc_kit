package sdlc;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Copilot agent hooks (VS Code, JetBrains, Copilot CLI). Reads the event JSON on stdin; both the
 * VS Code payload (tool_name, tool_input) and the CLI payload (toolName, toolArgs) work.
 */
final class Hooks {
    private Hooks() {}

    /** copilot (VS Code / Copilot CLI: tolerant, both shapes) or claude / codex (strict Claude Code hook schema). */
    static String format = "copilot";

    static int run(String event, String stdin) {
        return run(event, stdin, "copilot");
    }

    static int run(String event, String stdin, String forTool) {
        format = forTool;
        if (Files.exists(Ctx.work("hook-debug"))) Store.log("hook-payloads", Store.map("event", event, "stdin", stdin)); // opt-in: touch .sdlc/hook-debug
        Map<String, Object> input = stdin.isBlank() ? new LinkedHashMap<>() : Json.obj(Json.parse(stdin));
        return switch (event) {
            case "pre-tool-use" -> preToolUse(input);
            case "post-tool-use" -> postToolUse(input);
            case "stop" -> stop(input);
            case "session-start" -> sessionStart();
            default -> 0;
        };
    }

    static String toolName(Map<String, Object> input) {
        Object n = input.containsKey("tool_name") ? input.get("tool_name") : input.get("toolName");
        return n == null ? "" : n.toString();
    }

    static Map<String, Object> toolArgs(Map<String, Object> input) {
        Object a = input.containsKey("tool_input") ? input.get("tool_input") : input.get("toolArgs");
        if (a instanceof String s) {
            try {
                a = Json.parse(s);
            } catch (RuntimeException e) {
                return new LinkedHashMap<>(Map.of("raw", s));
            }
        }
        return a instanceof Map ? Json.obj(a) : new LinkedHashMap<>();
    }

    // ---------------- PreToolUse ----------------

    static int preToolUse(Map<String, Object> input) {
        String tool = toolName(input);
        Decision d = decide(tool, toolArgs(input));
        if (d.kind() != Decision.Kind.ALLOW) {
            Store.log("guard-decisions", Store.map("tool", tool, "decision", d.kind().name(), "reason", d.reason(), "story", Work.currentStory(), "branch", Git.branch()));
        }
        return emit(d);
    }

    static Decision decide(String tool, Map<String, Object> args) {
        Decision sub = McpRules.subagent(tool, args);
        if (sub != null) return sub;
        if (McpRules.isExternal(tool)) return McpRules.check(tool);
        Object command = args.get("command");
        if (command instanceof List<?> argv) command = ShellWords.join(argv.stream().map(String::valueOf).toList()); // ["bash", "-lc", "..."]
        if (command instanceof String cmd) {
            Decision d = ShellRules.check(cmd);
            if (d.denied() || !cmd.contains("*** ")) return d;
            List<String> patched = FileRules.paths(Map.of("input", cmd)); // apply_patch run as a shell command
            return patched.isEmpty() ? d : d.strictest(FileRules.checkEdit(patched));
        }
        List<String> paths = FileRules.paths(args);
        if (!paths.isEmpty()) return isReadTool(tool) ? FileRules.checkRead(paths) : FileRules.checkEdit(paths);
        return Decision.allow();
    }

    private static boolean isReadTool(String tool) {
        String t = tool.toLowerCase(Locale.ROOT);
        return t.matches(".*(read|view|open|get_file|list_dir|search|grep|glob|find|errors|usages).*")
                && !t.matches(".*(edit|write|create|replace|insert|patch|delete|rename|move).*");
    }

    static int emit(Decision d) {
        if (d.kind() == Decision.Kind.ALLOW) return 0;
        String word = d.denied() ? "deny" : "ask";
        String reason = (d.denied() ? "Refused by project guardrail: " : "Needs human approval: ") + d.reason();
        Map<String, Object> specific = Store.map("hookEventName", "PreToolUse", "permissionDecision", word, "permissionDecisionReason", reason);
        Map<String, Object> out = strict() ? Store.map("hookSpecificOutput", specific)
                : Store.map("permissionDecision", word, "permissionDecisionReason", reason, "hookSpecificOutput", specific);
        System.out.println(Json.write(out));
        if (d.denied()) {
            System.err.println(reason);
            return 2; // exit 2 = deny in VS Code and the Copilot CLI
        }
        return 0;
    }

    static boolean strict() {
        return format.equals("claude") || format.equals("codex");
    }

    /** Extra context for the agent: Claude Code / Codex read only hookSpecificOutput; Copilot reads either. */
    static Map<String, Object> context(String event, String text) {
        Map<String, Object> specific = Store.map("hookEventName", event, "additionalContext", text);
        return strict() ? Store.map("hookSpecificOutput", specific) : Store.map("additionalContext", text, "hookSpecificOutput", specific);
    }

    // ---------------- PostToolUse ----------------

    static int postToolUse(Map<String, Object> input) {
        String tool = toolName(input);
        Map<String, Object> args = toolArgs(input);
        List<String> paths = FileRules.paths(args);
        Store.log("tool-use", Store.map("tool", tool, "paths", String.join(",", paths), "story", Work.currentStory()));
        List<String> notes = new ArrayList<>();
        if (args.get("command") instanceof String cmd && cmd.contains("gh pr create")) {
            try {
                Sync.prOpened();
                notes.add("The pull request was linked to Jira and the tickets moved to In Review. Now wait for CI: java .agent/Sdlc.java ci wait");
            } catch (RuntimeException e) {
                notes.add("Could not update Jira after the PR was created (" + e.getMessage() + "). Run: java .agent/Sdlc.java sync");
            }
        }
        for (String path : paths) {
            String rel = FileRules.relative(path);
            if (Checks.isMainCode(rel)) notes.add("You changed main code (" + rel + "). Add or update the test that proves it, then run: java .agent/Sdlc.java verify");
            try {
                if (Glob.any(Ctx.cfg.list("paths.test"), rel) && Files.isRegularFile(Ctx.root.resolve(rel)) && Checks.containsDisabled(Files.readString(Ctx.root.resolve(rel)))) {
                    notes.add("Test file " + rel + " contains a disabled or focused test. The git hook refuses it. Fix the test instead.");
                }
            } catch (java.io.IOException ignored) {
                // file vanished
            }
        }
        if (notes.isEmpty()) return 0;
        String text = String.join("\n", notes);
        System.out.println(Json.write(context("PostToolUse", text)));
        return 0;
    }

    // ---------------- Stop ----------------

    static int stop(Map<String, Object> input) {
        syncQuietly();
        if (!hasCodeChanges()) return 0;
        String fp = Verify.fingerprint();
        if (fp.equals(String.valueOf(Verify.last().get("fingerprint")))) return 0;
        Map<String, Object> blocks = Store.read(Ctx.work("stop-blocks.json"));
        int count = blocks.get(fp) instanceof Double d ? d.intValue() : 0;
        if (count >= Ctx.cfg.num("guard.maxStopBlocks", 3)) return 0; // hand the turn back to the human
        blocks.clear();
        blocks.put(fp, count + 1);
        Store.write(Ctx.work("stop-blocks.json"), blocks);
        String reason = "The code changed since the last passing build. Run `java .agent/Sdlc.java verify` now, fix every failure, "
                + "and only then finish. Report the result with the command output.";
        System.out.println(Json.write(strict() ? Store.map("decision", "block", "reason", reason)
                : Store.map("decision", "block", "reason", reason, "hookSpecificOutput", Store.map("hookEventName", "Stop", "decision", "block", "reason", reason))));
        return 0;
    }

    private static boolean hasCodeChanges() {
        List<String> code = Ctx.cfg.list("paths.code");
        return Git.branchFiles().stream().anyMatch(f -> Glob.any(code, f));
    }

    /** Keeps Jira in step with the local state, at most once a minute. */
    private static void syncQuietly() {
        String story = Work.currentStory();
        if (story.isEmpty()) return;
        Map<String, Object> stamp = Store.read(Ctx.work("sync-stamp.json"));
        Object last = stamp.get("at");
        if (last != null && Duration.between(Instant.parse(last.toString()), Instant.now()).toSeconds() < 60) return;
        Store.write(Ctx.work("sync-stamp.json"), Store.map("at", Instant.now().toString()));
        try {
            Sync.run(story, true);
        } catch (RuntimeException e) {
            Store.log("sync", Store.map("story", story, "error", e.getMessage()));
        }
    }

    // ---------------- SessionStart ----------------

    static int sessionStart() {
        String text = Status.text() + "\nRules: read docs/agent/project-profile.md before coding; work only on a feature branch; edit code only inside "
                + "the task scope; never skip tests or checks; run `java .agent/Sdlc.java verify` before you finish; never merge, tag or push to protected "
                + "branches. Humans approve the test design and the LLD and merge pull requests.";
        System.out.println(Json.write(context("SessionStart", text)));
        return 0;
    }
}
