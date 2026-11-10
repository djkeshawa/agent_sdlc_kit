package sdlc.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import sdlc.util.ShellWords;

/**
 * Shell rule about commands that read every file below a folder (grep -r, tar, zip, cp -r,
 * find -exec ...): refused when the folder holds a credential file, because the secret's own
 * name never appears in the command.
 */
final class ReadRules {
    private static final Set<String> GREPS = Set.of("grep", "egrep", "fgrep", "zgrep", "rgrep");
    private static final Set<String> ARCHIVERS = Set.of("tar", "zip", "7z", "7za", "jar", "cpio", "rsync");
    private static final Set<String> SEARCHERS = Set.of("rg", "ag", "ack");

    private final Guard g;

    ReadRules(Guard g) {
        this.g = g;
    }

    Decision wholeFolder(ShellCommand c) {
        String name = c.name();
        List<String> args = c.args();
        boolean grep = GREPS.contains(name) && (name.equals("rgrep") || args.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[rR][a-zA-Z]*$")
                || a.startsWith("--recursive") || a.startsWith("--dereference-recursive") || a.equals("--directories=recurse")));
        // rg/ag skip hidden and git-ignored files (local.env) unless told otherwise; ack does not
        boolean search = name.equals("ack") || SEARCHERS.contains(name) && args.stream().anyMatch(a -> a.matches("^-u+$|^-[a-zA-Z]*\\.[a-zA-Z]*$")
                || a.equals("--hidden") || a.startsWith("--no-ignore") || a.equals("--unrestricted") || a.equals("--skip-vcs-ignores") || a.equals("--all-types"));
        boolean find = name.equals("find") && args.stream().anyMatch(a -> a.matches("^-(exec|execdir|ok|okdir)$"));
        boolean copy = ShellRules.COPIERS.contains(name) && args.stream().anyMatch(a -> a.matches("^-[a-zA-Z]*[rRa][a-zA-Z]*$") || a.equals("--recursive")
                || a.equalsIgnoreCase("/s") || a.equalsIgnoreCase("/e") || a.equalsIgnoreCase("-recurse"));
        if (!(grep || search || find || copy || ARCHIVERS.contains(name))) return null;
        boolean namedFolder = false;
        for (String a : args.stream().flatMap(x -> ShellWords.braces(x).stream()).toList()) {
            if (a.startsWith("-") || a.isBlank()) continue;
            String home = a.replace("~", System.getProperty("user.home"));
            List<Path> candidates;
            if (FileRules.isGlob(home)) {
                candidates = g.files.expandGlob(home);
                if (candidates == null) return denyTree(name, a);
            } else {
                try {
                    Path raw = Path.of(home);
                    candidates = raw.isAbsolute() ? List.of(raw) : g.folders.bases().stream().map(b -> b.resolve(raw).normalize()).toList();
                } catch (RuntimeException e) {
                    continue;
                }
            }
            for (Path p : candidates) {
                if (!Files.isDirectory(p)) continue;
                namedFolder = true;
                if (g.files.dirHoldsSecret(p)) return denyTree(name, a);
            }
        }
        // grep -r PATTERN, rg -uu PATTERN, find -exec ... with no path at all search the current folder
        if (!namedFolder && defaultsToCurrentFolder(name, find, args) && g.folders.bases().stream().anyMatch(g.files::dirHoldsSecret)) return denyTree(name, ".");
        return null;
    }

    /** True when the command names no path, so it reads the current folder. */
    private static boolean defaultsToCurrentFolder(String name, boolean find, List<String> args) {
        if (find) return args.isEmpty() || args.get(0).startsWith("-") || args.get(0).equals("(") || args.get(0).equals("!");
        if (!GREPS.contains(name) && !SEARCHERS.contains(name)) return false;
        int positional = 0;
        boolean patternAsOption = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-e") || a.equals("-f") || a.equals("--regexp") || a.equals("--file")) {
                patternAsOption = true;
                i++; // the pattern (or pattern file) that follows is not a path
            } else if (a.startsWith("--regexp=") || a.startsWith("--file=")) {
                patternAsOption = true;
            } else if (!a.startsWith("-")) {
                positional++;
            }
        }
        return (patternAsOption ? positional : positional - 1) <= 0;
    }

    private static Decision denyTree(String name, String folder) {
        return Decision.deny("`" + name + "` would read every file below `" + folder + "`, including a credential file. "
                + "Search tracked files with `git grep`, or name the code folders (src/, docs/) instead.");
    }
}
