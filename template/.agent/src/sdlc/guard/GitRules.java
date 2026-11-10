package sdlc.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import sdlc.util.ShellWords;

/** Rules for git commands an agent runs. The option and config-key helpers are static: they only look at text. */
final class GitRules {
    private final Guard g;

    GitRules(Guard g) {
        this.g = g;
    }

    Decision check(List<String> args) {
        return check(args, 0);
    }

    private Decision check(List<String> args, int depth) {
        int i = 0;
        while (i < args.size() && args.get(i).startsWith("-")) {
            String a = args.get(i);
            if (a.equals("-c") && i + 1 < args.size()) {
                Decision d = configKey(args.get(i + 1));
                if (d != null) return d;
            }
            if (a.startsWith("--config-env")) {
                Decision d = configKey(a.contains("=") ? a.substring(a.indexOf('=') + 1) : i + 1 < args.size() ? args.get(i + 1) : "");
                if (d != null) return d;
            }
            if (noVerify(a)) return Decision.deny("skips the git hooks.");
            i += (a.equals("-C") || a.equals("-c") || a.equals("--git-dir") || a.equals("--work-tree") || a.equals("--config-env") || a.equals("--namespace")) ? 2 : 1;
        }
        if (i >= args.size()) return Decision.allow();
        String sub = args.get(i);
        List<String> rest = args.subList(i + 1, args.size());
        if (rest.stream().anyMatch(GitRules::noVerify)) return Decision.deny("skips the git hooks. Fix what the hook reports instead.");
        String branch = g.branch();
        boolean onProtected = g.cfg.isProtectedBranch(branch);
        return switch (sub) {
            case "push" -> push(rest, branch);
            case "commit" -> rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*n[a-zA-Z]*$")) ? Decision.deny("`-n` skips the git hooks.")
                    : onProtected ? Decision.deny("commits go on a feature branch, never on `" + branch + "`.") : Decision.allow();
            case "merge", "rebase", "cherry-pick", "revert", "am" -> onProtected ? Decision.deny("changes protected branch `" + branch + "`. Work on a feature branch.") : Decision.allow();
            case "tag" -> rest.isEmpty() || rest.contains("-l") || rest.contains("--list") ? Decision.allow() : Decision.deny("tags are made by the release process.");
            case "config" -> config(rest);
            case "reset" -> rest.contains("--hard") ? Decision.ask("`git reset --hard` throws away uncommitted work.") : Decision.allow();
            case "clean" -> rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[xX][a-zA-Z]*$")) ? Decision.deny("`git clean -x` deletes ignored files such as local settings and state.")
                    : rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*f[a-zA-Z]*$")) ? Decision.ask("`git clean -f` deletes untracked files.") : Decision.allow();
            case "branch" -> rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[dDmMfF][a-zA-Z]*$") || a.equals("--delete") || a.equals("--force") || a.equals("--move"))
                    ? Decision.ask("deletes, renames or resets a branch.") : Decision.allow();
            case "update-ref", "filter-branch", "filter-repo", "replace" -> Decision.deny("rewrites git history or refs.");
            case "stash" -> !rest.isEmpty() && (rest.get(0).equals("drop") || rest.get(0).equals("clear")) ? Decision.ask("drops stashed work.") : Decision.allow();
            case "checkout", "restore" -> rest.contains(".") ? Decision.ask("discards uncommitted changes.") : Decision.allow();
            case "worktree" -> !rest.isEmpty() && (rest.get(0).equals("remove") || rest.get(0).equals("prune")) ? Decision.ask("removes a worktree.") : Decision.allow();
            default -> alias(sub, rest, depth);
        };
    }

    /** git accepts any unambiguous prefix of a long option: --no-veri is --no-verify. */
    static boolean abbrev(String arg, String full, int min) {
        String flag = arg.split("=", 2)[0];
        return flag.length() >= min && full.startsWith(flag);
    }

    private static boolean noVerify(String a) {
        return abbrev(a, "--no-verify", 6);
    }

    /** -c / --config-env settings that change hooks or define commands the guard cannot see. */
    private static Decision configKey(String spec) {
        String key = spec.split("=", 2)[0].toLowerCase(Locale.ROOT);
        if (key.startsWith("core.hookspath")) return Decision.deny("changes where git hooks run from.");
        if (runsProgram(key) && !harmlessProgram(spec.contains("=") ? spec.substring(spec.indexOf('=') + 1) : "true")) {
            return Decision.deny("`" + key + "` makes git run a program the guard cannot check.");
        }
        if (key.startsWith("alias.") || key.startsWith("include.") || key.startsWith("includeif.")) {
            return Decision.deny("defines a git alias or config include the guard cannot check. Run the git command directly.");
        }
        return null;
    }

    /** An unknown subcommand may be an alias: check what it expands to (builtins cannot be aliased). */
    private Decision alias(String sub, List<String> rest, int depth) {
        if (depth > 3) return Decision.deny("git aliases nest too deeply to check.");
        String expansion = g.repo.alias(sub);
        if (expansion.isEmpty()) return Decision.allow();
        if (expansion.startsWith("!")) return Decision.deny("`git " + sub + "` is a shell alias (" + expansion + ") the guard cannot check. Run the commands directly.");
        List<List<String>> parsed = ShellWords.commands(expansion);
        List<String> expanded = new ArrayList<>(parsed.isEmpty() ? List.of() : parsed.get(0));
        expanded.addAll(rest);
        return check(expanded, depth + 1);
    }

    private static final java.util.regex.Pattern PROGRAM_KEY = java.util.regex.Pattern.compile("^(core\\.(fsmonitor|pager|editor|sshcommand|askpass|gitproxy|worktree)|sequence\\.editor"
            + "|diff\\.external|diff\\..+\\.(textconv|command)|difftool\\..+|mergetool\\..+|merge\\..+\\.driver|filter\\..+|gpg\\.program|gpg\\..+\\.program|pager\\..+"
            + "|uploadpack\\.packobjectshook|sendemail\\..+|browser\\..+\\.cmd|man\\..+\\.cmd|trailer\\..+\\.(command|cmd)|interactive\\.difffilter|init\\.templatedir"
            + "|submodule\\..+\\.update|protocol\\.ext\\.allow)$");

    /** Config keys whose value git runs as a program (core.fsmonitor, core.pager, diff.external, filter.*, ...). */
    static boolean runsProgram(String key) {
        return PROGRAM_KEY.matcher(key.toLowerCase(Locale.ROOT)).matches();
    }

    /** Values that run nothing of the agent's making: off, or a plain pager. */
    static boolean harmlessProgram(String value) {
        return value.strip().matches("(?i)|cat|more|less( -[A-Za-z]+)*|true|false|:");
    }

    private static Decision config(List<String> rest) {
        String first = rest.stream().filter(a -> !a.startsWith("-")).findFirst().orElse("");
        boolean read = first.equals("get") || first.equals("list") || rest.stream().anyMatch(a -> a.equals("--get") || a.equals("--list") || a.equals("-l") || a.equals("--get-all") || a.equals("--get-regexp"));
        boolean unset = first.equals("unset") || rest.stream().anyMatch(a -> a.startsWith("--unset"));
        if (rest.stream().anyMatch(a -> a.equals("--global") || a.equals("--system"))) return read ? Decision.allow() : Decision.deny("changes git settings outside this repository.");
        for (int i = 0; i < rest.size(); i++) {
            String a = rest.get(i);
            String k = a.toLowerCase(Locale.ROOT);
            if (runsProgram(k) && !read && !unset && i + 1 < rest.size() && !harmlessProgram(rest.get(i + 1))) {
                return Decision.deny("`" + a + "` makes git run a program the guard cannot check.");
            }
            if (k.startsWith("core.hookspath") || k.startsWith("user.") || k.startsWith("credential") || k.startsWith("url.") || k.startsWith("remote.")
                    || k.startsWith("alias.") || k.startsWith("include.") || k.startsWith("includeif.")) {
                return read || rest.size() == 1 ? Decision.allow() : Decision.deny("changes git hooks, identity, credentials, remotes, aliases or config includes.");
            }
        }
        return Decision.allow();
    }

    private Decision push(List<String> rest, String branch) {
        List<String> positional = new ArrayList<>();
        for (String a : rest) {
            if (a.startsWith("--")) {
                if (abbrev(a, "--force", 5) || abbrev(a, "--force-with-lease", 9) || abbrev(a, "--force-if-includes", 9) || abbrev(a, "--mirror", 5)) {
                    return Decision.deny("force pushes rewrite shared history.");
                }
                if (abbrev(a, "--tags", 4) || abbrev(a, "--follow-tags", 5)) return Decision.deny("pushes tags.");
                if (abbrev(a, "--delete", 5) || abbrev(a, "--prune", 5) || abbrev(a, "--all", 4)) return Decision.deny("deletes or pushes every remote branch.");
            } else if (a.startsWith("-") && a.length() > 1) {
                if (a.contains("f")) return Decision.deny("force pushes rewrite shared history.");
                if (a.contains("d")) return Decision.deny("deletes a remote branch.");
            } else {
                positional.add(a);
            }
        }
        List<String> refspecs = positional.size() > 1 ? positional.subList(1, positional.size()) : List.of();
        if (refspecs.isEmpty() && g.cfg.isProtectedBranch(branch)) return Decision.deny("pushes to protected branch `" + branch + "`. Open a pull request instead.");
        for (String spec : refspecs) {
            if (spec.startsWith("+")) return Decision.deny("force pushes rewrite shared history.");
            if (spec.startsWith(":")) return Decision.deny("deletes a remote branch.");
            String target = spec.contains(":") ? spec.substring(spec.indexOf(':') + 1) : spec;
            if (target.startsWith("refs/tags/")) return Decision.deny("pushes a tag.");
            target = target.replaceFirst("^refs/heads/", "");
            if (target.equals("HEAD")) target = branch;
            if (g.cfg.isProtectedBranch(target)) return Decision.deny("pushes to protected branch `" + target + "`. Open a pull request instead.");
        }
        return g.gate.push();
    }
}
