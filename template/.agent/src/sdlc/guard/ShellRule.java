package sdlc.guard;

@FunctionalInterface
interface ShellRule {
    Decision check(ShellCommand command);
}
