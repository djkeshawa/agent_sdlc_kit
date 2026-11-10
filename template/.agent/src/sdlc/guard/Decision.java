package sdlc.guard;

/** The answer of a guardrail rule to one tool call. */
public record Decision(Kind kind, String reason) {
    public enum Kind { ALLOW, ASK, DENY }

    public static Decision allow() { return new Decision(Kind.ALLOW, ""); }
    static Decision ask(String reason) { return new Decision(Kind.ASK, reason); }
    public static Decision deny(String reason) { return new Decision(Kind.DENY, reason); }

    public boolean denied() { return kind == Kind.DENY; }

    public Decision strictest(Decision other) {
        if (other == null) return this;
        return other.kind.ordinal() > kind.ordinal() ? other : this;
    }
}
