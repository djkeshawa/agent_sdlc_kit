package sdlc.guard;

import java.util.List;

/** Shell rule about the build tools of the stack (build.tools): no skipping or weakening checks, no CI-only goals. */
final class BuildRules {
    private final Guard g;

    BuildRules(Guard g) {
        this.g = g;
    }

    /** True when the word is a build tool of this stack, as written (./mvnw) or by name (mvnw). */
    boolean isTool(String word) {
        List<String> tools = g.cfg.list("build.tools");
        return tools.contains(word) || tools.contains(ShellRules.baseName(word));
    }

    /** The arguments of a build tool call. */
    Decision check(List<String> args) {
        for (String a : args) {
            for (String re : g.cfg.list("build.forbiddenArgs")) {
                if (a.matches(re)) return Decision.deny("`" + a + "` switches off tests or quality checks, or hides failures. Fix the failure instead.");
            }
        }
        List<String> plain = args.stream().filter(a -> !a.startsWith("-") && !a.contains("=")).toList();
        for (String a : plain) {
            for (String re : g.cfg.list("build.forbiddenGoals")) {
                if (a.matches(re)) return Decision.deny("`" + a + "` belongs to CI or the release manager, not to an agent.");
            }
        }
        for (int i = 0; i + 1 < args.size(); i++) { // mvn -P -quality / -P '!quality': the profile list as its own word
            if ((args.get(i).equals("-P") || args.get(i).equals("--activate-profiles")) && args.get(i + 1).matches("(.*,)?[!-].*")) {
                return Decision.ask("`-P " + args.get(i + 1) + "` switches a build profile off. Approve only if no test or quality check lives in it.");
            }
        }
        for (String a : args) {
            for (String re : g.cfg.list("build.askArgs")) {
                if (a.equals(re) || a.matches(re)) {
                    return Decision.ask("`" + a + "` changes dependencies, test snapshots or the build profiles that run. Approve only if the LLD asks for it.");
                }
            }
        }
        return Decision.allow();
    }
}
