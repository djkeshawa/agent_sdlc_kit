package sdlc.guard;

/**
 * One rule about shell commands. ShellRules asks its rules in order; the first one that
 * answers decides.
 */
@FunctionalInterface
interface ShellRule {
    /** Allow, ask or deny when the command is this rule's business; null when it is not (the next rule is asked). */
    Decision check(ShellCommand command);
}
