package sdlc;

/** The answer of a guardrail rule to one tool call. */
record Decision(Kind kind, String reason) {
    enum Kind { ALLOW, ASK, DENY }

    static Decision allow() { return new Decision(Kind.ALLOW, ""); }
    static Decision ask(String reason) { return new Decision(Kind.ASK, reason); }
    static Decision deny(String reason) { return new Decision(Kind.DENY, reason); }

    boolean denied() { return kind == Kind.DENY; }

    Decision strictest(Decision other) {
        if (other == null) return this;
        return other.kind.ordinal() > kind.ordinal() ? other : this;
    }
}
