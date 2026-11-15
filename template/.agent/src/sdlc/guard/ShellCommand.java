package sdlc.guard;

import java.util.List;

record ShellCommand(List<String> raw, List<String> words) {
    String cmd() {
        return words.get(0);
    }

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
