package sdlc.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import sdlc.util.Json;

/** Rules for MCP tools (Jira, Confluence, GitHub, SonarQube servers) and subagent calls. */
public final class McpRules {
    private static final Set<String> READ_VERBS = Set.of("get", "search", "list", "fetch", "read", "lookup", "find", "query", "view", "show", "describe", "download", "discover", "whoami", "info", "analyze", "analyse", "check");
    private static final Set<String> WRITE_VERBS = Set.of("create", "update", "edit", "delete", "remove", "transition", "add", "post", "upload", "link", "move", "assign", "set", "write", "publish", "merge", "push", "close", "approve", "submit", "archive", "rename", "destructive", "comment", "reply", "resolve", "dismiss", "fork", "request", "change", "toggle", "mark",
            "run", "rerun", "cancel", "dispatch", "trigger", "start", "stop", "enable", "disable", "lock", "unlock", "invite", "import", "send",
            "install", "uninstall", "revoke", "grant", "restore", "retry", "deploy", "redeploy", "release", "transfer", "star", "unstar", "watch",
            "unwatch", "pin", "unpin", "label", "convert", "sync", "reopen", "apply", "save", "put", "patch", "insert", "replace", "clear", "reset",
            "execute", "exec", "invoke", "call", "reject", "vote", "subscribe", "unsubscribe", "share", "attach", "detach", "bulk");

    private final Guard g;

    McpRules(Guard g) {
        this.g = g;
    }

    public boolean isExternal(String tool) {
        String lower = tool.toLowerCase(Locale.ROOT);
        for (String hint : g.cfg.list("mcp.serverHints")) if (lower.contains(hint.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /**
     * The action is the first verb in the tool name (get_pull_request: "request" is a noun), plus every
     * verb joined to it with "or"/"and" (get_or_create_branch). A write action denies, a read action
     * allows, and a name with no known verb asks a human (fail closed: run_workflow must not slip through).
     */
    public Decision check(String tool) {
        if (g.cfg.bool("mcp.allowWrites", false)) return Decision.allow();
        List<String> words = words(tool);
        if (words.contains("destructive") || words.contains("write") && !words.contains("read")) return deny(tool);
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            boolean verb = READ_VERBS.contains(w) || WRITE_VERBS.contains(w);
            boolean joined = i > 0 && (words.get(i - 1).equals("or") || words.get(i - 1).equals("and"));
            if (verb && (actions.isEmpty() || joined)) actions.add(w);
        }
        if (actions.isEmpty()) return Decision.ask("`" + tool + "` is an MCP action the guard does not recognise as a read. Approve only if it changes nothing.");
        if (actions.stream().anyMatch(WRITE_VERBS::contains)) return deny(tool);
        return Decision.allow();
    }

    private static Decision deny(String tool) {
        return Decision.deny("`" + tool + "` writes to an external system. Agents read through MCP and write through the audited tool, "
                + "e.g. java .agent/Sdlc.java jira comment <KEY> --file <file>, or gh pr create for pull requests.");
    }

    /** Name words; executeRead (Atlassian's read entry point) is a read, not an execute. */
    static List<String> words(String tool) {
        List<String> w = rawWords(tool);
        int i = w.indexOf("execute");
        if (i >= 0 && i + 1 < w.size() && w.get(i + 1).equals("read")) w.remove(i);
        return w;
    }

    private static List<String> rawWords(String tool) {
        String spaced = tool.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ");
        List<String> out = new ArrayList<>();
        for (String w : spaced.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** runSubagent / agent tools: stop starting implementers once the review loop is exhausted. */
    public Decision subagent(String tool, Map<String, Object> args) {
        String lower = tool.toLowerCase(Locale.ROOT);
        if (!lower.contains("subagent") && !lower.equals("agent") && !lower.equals("task")) return null;
        String text = Json.write(args).toLowerCase(Locale.ROOT);
        if (text.contains("implementer") || text.contains("ci-fixer")) return g.gate.anotherFixRound();
        return Decision.allow();
    }
}
