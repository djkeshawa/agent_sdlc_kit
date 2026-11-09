package sdlc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Rules for git commands an agent runs. */
final class GitRules {
    private GitRules() {}

    static Decision check(List<String> args) {
        return check(args, 0);
    }

    private static Decision check(List<String> args, int depth) {
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
        String branch = Git.branch();
        boolean onProtected = Ctx.cfg.isProtectedBranch(branch);
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
        if (key.startsWith("alias.") || key.startsWith("include.") || key.startsWith("includeif.")) {
            return Decision.deny("defines a git alias or config include the guard cannot check. Run the git command directly.");
        }
        return null;
    }

    /** An unknown subcommand may be an alias: check what it expands to (builtins cannot be aliased). */
    private static Decision alias(String sub, List<String> rest, int depth) {
        if (depth > 3) return Decision.deny("git aliases nest too deeply to check.");
        Proc p = Proc.run(Ctx.root, "git", "config", "--get", "alias." + sub);
        String expansion = p.ok() ? p.out().trim() : "";
        if (expansion.isEmpty()) return Decision.allow();
        if (expansion.startsWith("!")) return Decision.deny("`git " + sub + "` is a shell alias (" + expansion + ") the guard cannot check. Run the commands directly.");
        List<List<String>> parsed = ShellWords.commands(expansion);
        List<String> expanded = new ArrayList<>(parsed.isEmpty() ? List.of() : parsed.get(0));
        expanded.addAll(rest);
        return check(expanded, depth + 1);
    }

    private static Decision config(List<String> rest) {
        boolean read = rest.stream().anyMatch(a -> a.equals("--get") || a.equals("--list") || a.equals("-l") || a.equals("--get-all"));
        if (rest.stream().anyMatch(a -> a.equals("--global") || a.equals("--system"))) return read ? Decision.allow() : Decision.deny("changes git settings outside this repository.");
        for (String a : rest) {
            String k = a.toLowerCase(Locale.ROOT);
            if (k.startsWith("core.hookspath") || k.startsWith("user.") || k.startsWith("credential") || k.startsWith("url.") || k.startsWith("remote.")
                    || k.startsWith("alias.") || k.startsWith("include.") || k.startsWith("includeif.")) {
                return read || rest.size() == 1 ? Decision.allow() : Decision.deny("changes git hooks, identity, credentials, remotes, aliases or config includes.");
            }
        }
        return Decision.allow();
    }

    private static Decision push(List<String> rest, String branch) {
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
        if (refspecs.isEmpty() && Ctx.cfg.isProtectedBranch(branch)) return Decision.deny("pushes to protected branch `" + branch + "`. Open a pull request instead.");
        for (String spec : refspecs) {
            if (spec.startsWith("+")) return Decision.deny("force pushes rewrite shared history.");
            if (spec.startsWith(":")) return Decision.deny("deletes a remote branch.");
            String target = spec.contains(":") ? spec.substring(spec.indexOf(':') + 1) : spec;
            if (target.startsWith("refs/tags/")) return Decision.deny("pushes a tag.");
            target = target.replaceFirst("^refs/heads/", "");
            if (target.equals("HEAD")) target = branch;
            if (Ctx.cfg.isProtectedBranch(target)) return Decision.deny("pushes to protected branch `" + target + "`. Open a pull request instead.");
        }
        return Gates.push();
    }
}
