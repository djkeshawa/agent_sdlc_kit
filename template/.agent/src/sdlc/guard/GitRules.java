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
            if (a.startsWith("--git-dir") || a.startsWith("--work-tree")) return Decision.deny("points git at another repository or working tree; the guard checks this one.");
            if (a.startsWith("--exec-path=")) return Decision.deny("makes git run its commands from another folder.");
            i += (a.equals("-C") || a.equals("-c") || a.equals("--git-dir") || a.equals("--work-tree") || a.equals("--config-env") || a.equals("--namespace")) ? 2 : 1;
        }
        if (i >= args.size()) return Decision.allow();
        String sub = args.get(i);
        List<String> rest = args.subList(i + 1, args.size());
        if (rest.stream().anyMatch(GitRules::noVerify)) return Decision.deny("skips the git hooks. Fix what the hook reports instead.");
        if (sub.startsWith("credential")) return Decision.deny("reads or changes the passwords and tokens git has stored.");
        String branch = g.branch();
        boolean onProtected = g.cfg.isProtectedBranch(branch);
        String first = rest.stream().filter(a -> !a.startsWith("-")).findFirst().orElse("");
        return switch (sub) {
            case "push" -> push(rest, branch);
            case "commit" -> rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*n[a-zA-Z]*$")) ? Decision.deny("`-n` skips the git hooks.")
                    : onProtected ? Decision.deny("commits go on a feature branch, never on `" + branch + "`.") : Decision.allow();
            case "merge", "rebase", "cherry-pick", "revert", "am" -> onProtected ? Decision.deny("changes protected branch `" + branch + "`. Work on a feature branch.")
                    : sub.equals("rebase") && rest.stream().anyMatch(a -> a.equals("-x") || a.startsWith("-x") && !a.startsWith("--") || abbrev(a, "--exec", 4))
                            ? Decision.ask("`git rebase --exec` runs a command at every commit, which the guard cannot check.") : Decision.allow();
            case "remote" -> switch (first) {
                case "set-url" -> Decision.deny("changes where git pushes to and fetches from.");
                case "add", "rename", "remove", "rm" -> Decision.ask("adds, renames or removes a git remote. Approve only if you know the address.");
                default -> Decision.allow();
            };
            case "bisect" -> first.equals("run") ? Decision.ask("`git bisect run` runs a command at every step, which the guard cannot check.") : Decision.allow();
            case "submodule" -> first.equals("foreach") ? Decision.ask("`git submodule foreach` runs a command in every submodule, which the guard cannot check.")
                    : first.equals("add") ? Decision.ask("adds another repository's code as a submodule.") : Decision.allow();
            case "lfs" -> Set.of("install", "uninstall", "update").contains(first) ? Decision.ask("`git lfs " + first + "` rewrites git hooks.") : Decision.allow();
            case "difftool", "mergetool" -> rest.stream().anyMatch(a -> a.startsWith("-x") || abbrev(a, "--extcmd", 5))
                    ? Decision.ask("runs a program of the command's choosing on the files.") : Decision.allow();
            case "daemon", "instaweb" -> Decision.deny("serves the repository on the network.");
            case "send-email" -> Decision.ask("sends commits by e-mail.");
            case "tag" -> rest.isEmpty() || rest.contains("-l") || rest.contains("--list") ? Decision.allow() : Decision.deny("tags are made by the release process.");
            case "config" -> config(rest);
            case "reset" -> rest.contains("--hard") ? Decision.ask("`git reset --hard` throws away uncommitted work.") : Decision.allow();
            case "clean" -> rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[xX][a-zA-Z]*$")) ? Decision.deny("`git clean -x` deletes ignored files such as local settings and state.")
                    : rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*f[a-zA-Z]*$")) ? Decision.ask("`git clean -f` deletes untracked files.") : Decision.allow();
            case "branch" -> rest.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[dDmMfF][a-zA-Z]*$") || a.equals("--delete") || a.equals("--force") || a.equals("--move"))
                    ? Decision.ask("deletes, renames or resets a branch.") : Decision.allow();
            case "update-ref", "filter-branch", "filter-repo", "replace" -> Decision.deny("rewrites git history or refs.");
            // --prefix writes the files of the index under any folder, the git folder and the approval record in it included
            case "checkout-index" -> rest.stream().anyMatch(x -> x.startsWith("--p") || x.equals("--temp") || abbrev(x, "--temp", 3))
                    ? Decision.deny("`git checkout-index --prefix` writes files of the index to a folder of the command's choosing, which the guard cannot check.") : Decision.allow();
            case "stash" -> !rest.isEmpty() && (rest.get(0).equals("drop") || rest.get(0).equals("clear")) ? Decision.ask("drops stashed work.") : Decision.allow();
            case "checkout", "restore" -> rest.contains(".") ? Decision.ask("discards uncommitted changes.") : Decision.allow();
            case "worktree" -> first.equals("remove") || first.equals("prune") ? Decision.ask("removes a worktree.")
                    : first.equals("add") ? Decision.ask("adds a second working tree. The guard's branch and scope checks look at this one only.") : Decision.allow();
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

    /**
     * True when the argument is the long option or a shortened form of it: git takes any start of
     * an option's name that fits no other option ("--remove-sect", "--edi"). A start that fits
     * several is refused by git itself, so treating it as each of them is safe.
     */
    static boolean longOption(String arg, String option) {
        String name = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
        return name.length() > 2 && name.startsWith("--") && option.startsWith(name);
    }

    // git config, by a list of what is allowed. A list of what is refused cannot be complete: git takes options in any
    // order, shortened, negated (--get --no-get sets), grouped, and with operands that look like other things (--file get).
    // So only these options, written out in full, are known; anything else is refused.
    private static final Set<String> CONFIG_SCOPE = Set.of("--local", "--global", "--system", "--worktree");
    private static final Set<String> CONFIG_READ = Set.of("--get", "--get-all", "--get-regexp", "--list", "-l");
    private static final Set<String> CONFIG_WRITE = Set.of("--add", "--unset", "--unset-all", "--replace-all");
    private static final Set<String> CONFIG_SHOW = Set.of("--show-origin", "--show-scope", "--name-only", "-z", "--null", "--bool", "--int", "--bool-or-int", "--path",
            "--expiry-date", "--includes");
    private static final Decision CONFIG_UNKNOWN = Decision.deny("uses a form of `git config` the guard does not know, so it cannot tell a read from a change. "
            + "Write the options out in full, each once: `git config --get <key>`, `git config --list`, `git config <key> <value>`.");

    private static Decision config(List<String> rest) {
        List<String> words = new ArrayList<>(); // the subcommand, the key, the value: never an option or an option's operand
        boolean file = false, outside = false, subcommandPossible = true;
        int reads = 0, writes = 0;
        String subcommand = "";
        for (int i = 0; i < rest.size(); i++) {
            String a = rest.get(i);
            if (a.equals("--")) { // git reads options up to here only: what follows are the key and its value, whatever they look like
                words.addAll(rest.subList(i + 1, rest.size()));
                break;
            }
            if (!a.startsWith("-") || a.equals("-")) {
                // the first word, before any key, may be a subcommand of newer git (get, list, set, unset, ...)
                if (subcommandPossible && words.isEmpty() && Set.of("get", "list", "set", "unset", "edit", "remove-section", "rename-section").contains(a)) subcommand = a;
                words.add(a);
                continue;
            }
            if (CONFIG_SCOPE.contains(a)) outside |= a.equals("--global") || a.equals("--system");
            else if (CONFIG_READ.contains(a)) reads++;
            else if (CONFIG_WRITE.contains(a)) writes++;
            else if (CONFIG_SHOW.contains(a) || a.startsWith("--type=")) continue;
            else if (a.equals("--file") || a.equals("-f") || a.equals("--default") || a.equals("--type")) {
                if (i + 1 >= rest.size()) return CONFIG_UNKNOWN;
                file |= !a.equals("--default") && !a.equals("--type");
                i++; // its operand is a file name or a value, never the subcommand or the key
            } else if (a.startsWith("--file=") || a.startsWith("--default=")) file |= a.startsWith("--file=");
            else if (a.equals("-lz") || a.equals("-zl")) reads++;
            else return CONFIG_UNKNOWN; // shortened, negated, grouped, or an option that edits or removes: not known, refused
        }
        if (subcommand.equals("edit") || subcommand.equals("remove-section") || subcommand.equals("rename-section")) {
            return Decision.deny("opens git settings in an editor, or removes or renames a whole section of them, protected keys included. Set single keys instead.");
        }
        if (subcommand.equals("get") || subcommand.equals("list")) reads++;
        if (subcommand.equals("set") || subcommand.equals("unset")) writes++;
        if (reads > 1 || reads > 0 && writes > 0) return CONFIG_UNKNOWN; // which of the two git would do is not for the guard to guess
        boolean read = reads == 1;
        if (read) return Decision.allow();
        if (outside) return Decision.deny("changes git settings outside this repository.");
        // a file of the command's choosing is written like any git settings file, and may be a file the guard protects
        if (file) return Decision.deny("writes git settings into a file of the command's choosing, which the guard does not check. Set the key in the repository's own settings.");
        boolean unset = subcommand.equals("unset") || rest.stream().anyMatch(a -> a.equals("--unset") || a.equals("--unset-all"));
        List<String> keys = subcommand.isEmpty() ? words : words.subList(1, words.size());
        boolean oneKeyRead = writes == 0 && keys.size() == 1; // git config <key> prints the key
        for (int i = 0; i < keys.size(); i++) {
            String a = keys.get(i);
            String k = a.toLowerCase(Locale.ROOT);
            if (runsProgram(k) && !unset && i + 1 < keys.size() && !harmlessProgram(keys.get(i + 1))) {
                return Decision.deny("`" + a + "` makes git run a program the guard cannot check.");
            }
            if (k.startsWith("core.hookspath") || k.startsWith("user.") || k.startsWith("credential") || k.startsWith("url.") || k.startsWith("remote.")
                    || k.startsWith("alias.") || k.startsWith("include.") || k.startsWith("includeif.")) {
                return oneKeyRead ? Decision.allow() : Decision.deny("changes git hooks, identity, credentials, remotes, aliases or config includes.");
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
                if (abbrev(a, "--repo", 4)) return Decision.deny("pushes to an address instead of the repository's remote. Push to `origin`.");
                if (abbrev(a, "--receive-pack", 5) || abbrev(a, "--exec", 4)) return Decision.deny("makes the push run a program of the command's choosing.");
            } else if (a.startsWith("-") && a.length() > 1) {
                if (a.contains("f")) return Decision.deny("force pushes rewrite shared history.");
                if (a.contains("d")) return Decision.deny("deletes a remote branch.");
            } else {
                positional.add(a);
            }
        }
        Decision where = Decision.allow();
        if (!positional.isEmpty()) {
            String remote = positional.get(0);
            if (remote.matches(".*(://|[@:\\\\]).*|^[/.~].*")) return Decision.deny("pushes to an address (`" + ShellRules.brief(remote) + "`) instead of the repository's remote. Push to `origin`.");
            if (!remote.equals("origin")) where = Decision.ask("pushes to the remote `" + remote + "`, not `origin`. Approve only if that is where this work belongs.");
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
        return g.gate.push().strictest(where);
    }
}
