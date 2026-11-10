package sdlc.selftest;

import java.nio.file.Path;
import java.util.Map;

import sdlc.cli.Hooks;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.guard.Decision;
import sdlc.util.Json;

/** What the self test classes share: the pass and fail counters and short ways to ask the guard for a decision. */
final class Asserts {
    private Asserts() {}

    static int passed;
    static int failed;

    static boolean throwsUsage(Runnable r) {
        try {
            r.run();
            return false;
        } catch (UsageException e) {
            return true;
        }
    }

    static Decision shell(String command) {
        return Hooks.decide("run_in_terminal", Json.obj(Json.parse("{\"command\":" + Json.quote(command) + "}")));
    }

    /** A shell command with the agent's shell in the given folder (the hook payload's cwd). */
    static Decision shellIn(Path folder, String command) {
        return Hooks.decide("run_in_terminal", Json.obj(Json.parse("{\"command\":" + Json.quote(command) + "}")), folder);
    }

    static Decision tool(String name, String argsJson) {
        return Hooks.decide(name, Json.obj(Json.parse(argsJson)));
    }

    static Decision toolCli(String name, String argsJson) {
        Map<String, Object> input = Store.map("toolName", name, "toolArgs", argsJson);
        return Hooks.decide(Hooks.toolName(input), Hooks.toolArgs(input));
    }

    static void expect(String name, Decision d, Decision.Kind want) {
        if (d.kind() == want) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + ": expected " + want + " got " + d.kind() + " " + d.reason());
        }
    }

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
