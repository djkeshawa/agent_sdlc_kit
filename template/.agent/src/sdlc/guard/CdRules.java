package sdlc.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import sdlc.util.Glob;

/**
 * Shell rules about the folder the shell is in: reads cd / pushd / Set-Location so that the
 * relative paths of the commands that follow are read from the right place, and refuses a cd
 * into a guardrail folder.
 */
final class CdRules {
    private static final Set<String> CD = Set.of("cd", "pushd", "chdir", "set-location", "sl");
    private static final Set<String> BEFORE_COMMAND = Set.of("{", "then", "do", "else", "elif", "if", "while", "until", "!", "builtin", "command", "time");

    private final Guard g;

    CdRules(Guard g) {
        this.g = g;
    }

    /**
     * Called after each command of a line: when it was a cd, the next commands start in its
     * folder (cd svc && sed -i ... src/A.java). `cd -` goes back to where the line started.
     */
    void follow(List<String> words, Path lineStart) {
        if (back(words)) {
            g.folders.returnTo(lineStart);
            return;
        }
        Path to = target(words);
        if (to != null) g.folders.enter(to);
    }

    private static int index(List<String> words) {
        int i = 0;
        while (i < words.size() && BEFORE_COMMAND.contains(words.get(i))) i++;
        return i < words.size() && CD.contains(ShellRules.baseName(words.get(i))) ? i : -1;
    }

    private static boolean back(List<String> words) {
        int i = index(words);
        return i >= 0 && i + 1 < words.size() && words.get(i + 1).equals("-");
    }

    /** The folder a cd / pushd / Set-Location leads to, or null when the words are no cd or the folder cannot be told. */
    Path target(List<String> words) {
        int i = index(words);
        if (i < 0) return null;
        String arg = null;
        for (int k = i + 1; k < words.size() && arg == null; k++) {
            if (!words.get(k).startsWith("-") && !words.get(k).equalsIgnoreCase("/d")) arg = words.get(k);
        }
        if (arg == null) arg = "~"; // a bare cd goes home
        if (arg.contains("$") || arg.contains("`")) return null;
        try {
            Path p = Path.of(ShellRules.expandHome(arg).replace('\\', '/'));
            return (p.isAbsolute() ? p : g.folders.base().resolve(p)).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** From inside a guardrail folder, relative paths would name guardrail files without saying so: such a cd is refused. */
    Decision check(ShellCommand c) {
        if (!CD.contains(c.name())) return null;
        Path to = target(c.words());
        List<String> readings = new ArrayList<>();
        if (to != null && to.startsWith(g.root)) readings.add(g.root.relativize(to).toString().replace('\\', '/'));
        for (String a : c.args()) if (!a.startsWith("-")) readings.addAll(g.files.otherReadings(a));
        for (String rel : readings) {
            if (guardFolder(rel)) return Decision.deny("`" + rel + "` holds guardrail or state files and is not a working folder. Run commands from the repository root and name the file's full path.");
        }
        return Decision.allow();
    }

    /** A folder that is, lies inside, or holds only guardrail files (.agent, .github, .githooks, .git, .sdlc except drafts and inbox). */
    private boolean guardFolder(String rel) {
        if (rel.isEmpty() || rel.startsWith("..")) return false;
        if (rel.equals(".git") || rel.startsWith(".git/") || g.files.isProtected(rel)) return true;
        return g.cfg.list("paths.protected").stream().anyMatch(glob -> Glob.normalize(glob).startsWith(rel + "/"));
    }
}
