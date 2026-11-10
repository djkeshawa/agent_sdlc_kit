package sdlc.cli;

import java.util.Map;

import sdlc.core.Store;

/**
 * How a host wants the answers of a hook shaped. The hook logic builds the answer; the format
 * turns it into the JSON that host reads.
 */
public enum HookFormat {
    /** VS Code and the Copilot CLI: tolerant, so both the flat and the nested shape are sent. */
    COPILOT {
        @Override
        public Map<String, Object> permission(String word, String reason) {
            return Store.map("permissionDecision", word, "permissionDecisionReason", reason, "hookSpecificOutput", STRICT.permission(word, reason).get("hookSpecificOutput"));
        }

        @Override
        public Map<String, Object> context(String event, String text) {
            return Store.map("additionalContext", text, "hookSpecificOutput", STRICT.context(event, text).get("hookSpecificOutput"));
        }

        @Override
        public Map<String, Object> block(String reason) {
            return Store.map("decision", "block", "reason", reason, "hookSpecificOutput", Store.map("hookEventName", "Stop", "decision", "block", "reason", reason));
        }
    },

    /** Claude Code and Codex: the Claude Code hook schema, which reads only hookSpecificOutput. */
    STRICT {
        @Override
        public Map<String, Object> permission(String word, String reason) {
            return Store.map("hookSpecificOutput", Store.map("hookEventName", "PreToolUse", "permissionDecision", word, "permissionDecisionReason", reason));
        }

        @Override
        public Map<String, Object> context(String event, String text) {
            return Store.map("hookSpecificOutput", Store.map("hookEventName", event, "additionalContext", text));
        }

        @Override
        public Map<String, Object> block(String reason) {
            return Store.map("decision", "block", "reason", reason);
        }
    };

    /** The format for `hook ... --for <tool>`: claude and codex are strict, everything else is Copilot. */
    public static HookFormat of(String tool) {
        return "claude".equals(tool) || "codex".equals(tool) ? STRICT : COPILOT;
    }

    /** PreToolUse: "deny" or "ask" with the reason. */
    public abstract Map<String, Object> permission(String word, String reason);

    /** Extra context for the agent after an event (SessionStart, PostToolUse). */
    public abstract Map<String, Object> context(String event, String text);

    /** Stop: send the agent back to work. */
    public abstract Map<String, Object> block(String reason);
}
