package sdlc.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sdlc.core.Ctx;
import sdlc.core.Git;
import sdlc.core.Store;
import sdlc.core.Work;
import sdlc.guard.Decision;
import sdlc.guard.FileRules;
import sdlc.guard.Guard;
import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.ShellWords;
import sdlc.workflow.Checks;
import sdlc.workflow.Gates;
import sdlc.workflow.Sync;
import sdlc.workflow.Verify;

/**
 * The agent hooks of Copilot (VS Code, JetBrains, Copilot CLI), Claude Code and Codex. Reads the
 * event JSON on stdin; both the VS Code payload (tool_name, tool_input) and the CLI payload
 * (toolName, toolArgs) work. What to answer is decided by the rules behind a Guard; how the
 * answer is written for the host is the HookFormat's business.
 */
public final class Hooks {
    private Hooks() {}

    public static int run(String event, String stdin, HookFormat format, Sync sync) {
        if (Files.exists(Ctx.work("hook-debug"))) Store.log("hook-payloads", Store.map("event", event, "stdin", stdin)); // opt-in: touch .sdlc/hook-debug
        Map<String, Object> input = stdin.isBlank() ? new LinkedHashMap<>() : Json.obj(Json.parse(stdin));
        return switch (event) {
            case "pre-tool-use" -> preToolUse(input, format);
            case "post-tool-use" -> postToolUse(input, format, sync);
            case "stop" -> stop(format, sync);
            case "session-start" -> sessionStart(format);
            default -> 0;
        };
    }

    /** Where the agent's shell is, as the host reports it (the payload's cwd), or null when it does not say. */
    private static Path shellFolder(Map<String, Object> input) {
        return Guard.folder(Ctx.root, Ctx.root, input.get("cwd"));
    }

    public static String toolName(Map<String, Object> input) {
        Object n = input.containsKey("tool_name") ? input.get("tool_name") : input.get("toolName");
        return n == null ? "" : n.toString();
    }

    public static Map<String, Object> toolArgs(Map<String, Object> input) {
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

    static int preToolUse(Map<String, Object> input, HookFormat format) {
        String tool = toolName(input);
        Decision d = decide(tool, toolArgs(input), shellFolder(input));
        if (d.kind() != Decision.Kind.ALLOW) {
            Store.log("guard-decisions", Store.map("tool", tool, "decision", d.kind().name(), "reason", d.reason(), "story", Work.currentStory(), "branch", Git.branch()));
        }
        return emit(d, format);
    }

    /** The guard's answer to one tool call, with the agent's shell in the repository root. */
    public static Decision decide(String tool, Map<String, Object> args) {
        return decide(tool, args, null);
    }

    /**
     * The guard's answer to one tool call. shellFolder is where the agent's shell is (null = the
     * repository root); a call that carries its own folder (Codex: workdir) starts there instead.
     */
    public static Decision decide(String tool, Map<String, Object> args, Path shellFolder) {
        Path base = shellFolder == null ? Ctx.root : shellFolder;
        Path workdir = Guard.folder(Ctx.root, base, args.containsKey("workdir") ? args.get("workdir") : args.get("cwd"));
        return decide(Gates.guard(workdir != null ? workdir : shellFolder), tool, args);
    }

    private static Decision decide(Guard guard, String tool, Map<String, Object> args) {
        Decision sub = guard.mcp.subagent(tool, args);
        if (sub != null) return sub;
        if (guard.mcp.isExternal(tool)) return guard.mcp.check(tool);
        Object command = args.get("command");
        if (command instanceof List<?> argv) command = ShellWords.join(argv.stream().map(String::valueOf).toList()); // ["bash", "-lc", "..."]
        if (command instanceof String cmd) {
            Decision d = guard.shell.check(cmd);
            if (d.denied() || !cmd.contains("*** ")) return d;
            List<String> patched = FileRules.paths(Map.of("input", cmd)); // apply_patch run as a shell command
            return patched.isEmpty() ? d : d.strictest(guard.files.checkEdit(patched));
        }
        List<String> paths = FileRules.paths(args);
        if (!paths.isEmpty()) return isReadTool(tool) ? guard.files.checkRead(paths) : guard.files.checkEdit(paths);
        return Decision.allow();
    }

    private static boolean isReadTool(String tool) {
        String t = tool.toLowerCase(Locale.ROOT);
        return t.matches(".*(read|view|open|get_file|list_dir|search|grep|glob|find|errors|usages).*")
                && !t.matches(".*(edit|write|create|replace|insert|patch|delete|rename|move).*");
    }

    /** Writes the answer to a PreToolUse event for the host and returns the exit code. */
    public static int emit(Decision d, HookFormat format) {
        if (d.kind() == Decision.Kind.ALLOW) return 0;
        String reason = (d.denied() ? "Refused by project guardrail: " : "Needs human approval: ") + d.reason();
        System.out.println(Json.write(format.permission(d.denied() ? "deny" : "ask", reason)));
        if (d.denied()) {
            System.err.println(reason);
            return 2; // exit 2 = deny in VS Code and the Copilot CLI
        }
        return 0;
    }

    // ---------------- PostToolUse ----------------

    static int postToolUse(Map<String, Object> input, HookFormat format, Sync sync) {
        String tool = toolName(input);
        Map<String, Object> args = toolArgs(input);
        List<String> paths = FileRules.paths(args);
        Store.log("tool-use", Store.map("tool", tool, "paths", String.join(",", paths), "story", Work.currentStory()));
        List<String> notes = new ArrayList<>();
        if (args.get("command") instanceof String cmd && (cmd.contains("gh pr create") || cmd.contains("gh pr new"))) {
            try {
                sync.prOpened();
                notes.add("The pull request was linked to Jira and the tickets moved to In Review. Now wait for CI: java .agent/Sdlc.java ci wait");
            } catch (RuntimeException e) {
                notes.add("Could not update Jira after the PR was created (" + e.getMessage() + "). Run: java .agent/Sdlc.java sync");
            }
        }
        FileRules files = Gates.guard(shellFolder(input)).files;
        for (String path : paths) {
            String rel = files.relative(path);
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
        System.out.println(Json.write(format.context("PostToolUse", text)));
        return 0;
    }

    // ---------------- Stop ----------------

    static int stop(HookFormat format, Sync sync) {
        syncQuietly(sync);
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
        System.out.println(Json.write(format.block(reason)));
        return 0;
    }

    private static boolean hasCodeChanges() {
        List<String> code = Ctx.cfg.list("paths.code");
        return Git.branchFiles().stream().anyMatch(f -> Glob.any(code, f));
    }

    /** Keeps Jira in step with the local state, at most once a minute. */
    private static void syncQuietly(Sync sync) {
        String story = Work.currentStory();
        if (story.isEmpty()) return;
        Map<String, Object> stamp = Store.read(Ctx.work("sync-stamp.json"));
        Object last = stamp.get("at");
        if (last != null && Duration.between(Instant.parse(last.toString()), Instant.now()).toSeconds() < 60) return;
        Store.write(Ctx.work("sync-stamp.json"), Store.map("at", Instant.now().toString()));
        try {
            sync.run(story, true);
        } catch (RuntimeException e) {
            Store.log("sync", Store.map("story", story, "error", e.getMessage()));
        }
    }

    // ---------------- SessionStart ----------------

    static int sessionStart(HookFormat format) {
        String text = Status.text() + "\nRules: read docs/agent/project-profile.md before coding; work only on a feature branch; edit code only inside "
                + "the task scope; never skip tests or checks; run `java .agent/Sdlc.java verify` before you finish; never merge, tag or push to protected "
                + "branches. Humans approve the test design and the LLD and merge pull requests.";
        System.out.println(Json.write(format.context("SessionStart", text)));
        return 0;
    }
}
