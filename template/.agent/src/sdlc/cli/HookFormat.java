package sdlc.cli;

import java.util.Map;

import sdlc.core.Store;

public enum HookFormat {
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

    public static HookFormat of(String tool) {
        return "claude".equals(tool) || "codex".equals(tool) ? STRICT : COPILOT;
    }

    public abstract Map<String, Object> permission(String word, String reason);

    public abstract Map<String, Object> context(String event, String text);

    public abstract Map<String, Object> block(String reason);
}
