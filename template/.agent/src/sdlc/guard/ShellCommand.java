package sdlc.guard;

import java.util.List;

/**
 * One simple command of a shell line.
 *
 * @param raw   its words as written, with redirections (">", ">>") still in them
 * @param words the same without the leading NAME=value words and wrappers (env, nohup, xargs ...); never empty
 */
record ShellCommand(List<String> raw, List<String> words) {
    /** The command word as written: ./mvnw, /usr/bin/git, npm. */
    String cmd() {
        return words.get(0);
    }

    /** The command's name without folder and .exe / .cmd: mvnw, git, npm. */
    String name() {
        return ShellRules.baseName(cmd());
    }

    List<String> args() {
        return words.subList(1, words.size());
    }

    String firstArg() {
        return words.size() > 1 ? words.get(1) : "";
    }
}
