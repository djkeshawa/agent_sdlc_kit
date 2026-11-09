package sdlc;

import java.util.List;
import java.util.Set;

/** Rules for GitHub CLI commands an agent runs. */
final class GhRules {
    private GhRules() {}

    static Decision check(List<String> args) {
        String group = args.isEmpty() ? "" : args.get(0);
        String action = args.size() > 1 ? args.get(1) : "";
        if (group.equals("pr")) {
            switch (action) {
                case "merge":
                    return Decision.deny("merging is a human decision after review and a green build.");
                case "review":
                    return args.contains("--approve") ? Decision.deny("agents never approve pull requests.") : Decision.allow();
                case "close":
                    return Decision.ask("closes a pull request.");
                case "create":
                    return Gates.prCreate();
                default:
                    return Decision.allow();
            }
        }
        if (group.equals("release") && !Set.of("view", "list", "download").contains(action)) return Decision.deny("releases are made by humans.");
        if (group.equals("repo") && Set.of("delete", "archive", "rename", "edit").contains(action)) return Decision.deny("changes repository settings.");
        if (group.equals("auth")) return action.equals("status") ? Decision.allow() : Decision.deny("agents never read or change GitHub credentials.");
        if (Set.of("secret", "variable", "ruleset", "workflow", "cache").contains(group) && !Set.of("list", "view").contains(action)) return Decision.deny("changes repository security or CI settings.");
        if (group.equals("api")) return api(args.subList(1, args.size()));
        if (group.equals("alias")) return Set.of("list", "ls").contains(action) ? Decision.allow() : Decision.deny("gh aliases hide commands from the guard. Run the gh command directly.");
        if (group.equals("extension") || group.equals("ext")) {
            return Set.of("list", "ls", "browse", "search").contains(action) ? Decision.allow() : Decision.deny("installs or runs gh extensions, code the guard cannot check.");
        }
        if (group.equals("config")) return Set.of("get", "list").contains(action) ? Decision.allow() : Decision.ask("changes gh settings.");
        if (!group.isEmpty() && !group.startsWith("-") && !KNOWN.contains(group)) {
            return Decision.ask("`gh " + group + "` is an alias or extension the guard does not know. Approve only if you know what it runs.");
        }
        return Decision.allow();
    }

    private static final Set<String> KNOWN = Set.of("pr", "issue", "repo", "release", "run", "workflow", "auth", "api", "alias", "browse", "cache",
            "codespace", "cs", "gist", "gpg-key", "label", "org", "project", "ruleset", "rs", "search", "secret", "ssh-key", "status", "variable",
            "extension", "ext", "config", "completion", "help", "attestation", "at", "copilot", "agent-task", "preview", "version");

    /**
     * gh api: any method but GET, and any field (-f/-F/--field/--raw-field/--input, in every spelling
     * pflag accepts) without an explicit GET, writes. GraphQL mutations write.
     */
    private static Decision api(List<String> args) {
        String method = null;
        boolean fields = false;
        boolean graphql = !args.isEmpty() && args.stream().filter(a -> !a.startsWith("-")).findFirst().orElse("").equals("graphql");
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-X") || a.equals("--method")) method = i + 1 < args.size() ? args.get(++i) : "";
            else if (a.startsWith("--method=")) method = a.substring("--method=".length());
            else if (a.startsWith("-X")) method = a.substring(2).replaceFirst("^=", "");
            else if (a.matches("^-[fF].*") || GitRules.abbrev(a, "--field", 5) || GitRules.abbrev(a, "--raw-field", 5) || GitRules.abbrev(a, "--input", 4)) fields = true;
            if (graphql && a.toLowerCase(java.util.Locale.ROOT).contains("mutation")) return Decision.deny("runs a GraphQL mutation (a write) through the raw API.");
        }
        if (graphql) return Decision.allow();
        if (method != null && !method.equalsIgnoreCase("GET")) return Decision.deny("writes to GitHub through the raw API.");
        if (method == null && fields) return Decision.deny("writes to GitHub through the raw API (fields make gh api a POST; add -X GET for a query).");
        return Decision.allow();
    }
}
