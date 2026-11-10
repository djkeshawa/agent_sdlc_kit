package sdlc.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The folders the agent's shell may be in while one tool call is judged. Relative paths are
 * read from here. One instance per tool call: nothing is shared between calls.
 */
public final class Folders {
    private final Path root;
    /** Where the shell is when the call starts: the hook's cwd or the call's workdir. Null = the repository root. */
    private final Path start;
    /** Where the `cd`s read so far in the command line lead; relative paths of the next command start here. Null = start. */
    private Path cd;
    /** Every folder a `cd` of the line named. The shell may be in any of them (a subshell, a function, a cd that failed). */
    private final List<Path> visited = new ArrayList<>();

    Folders(Path root, Path start) {
        this.root = root;
        this.start = start;
    }

    /** The folder relative paths are read from. */
    Path base() {
        return cd != null ? cd : start != null ? start : root;
    }

    /** Every folder the shell may be in: the one the guard believes, the repository root, and the others named by a cd. */
    public List<Path> bases() {
        Set<Path> out = new LinkedHashSet<>();
        out.add(base());
        out.add(root);
        if (start != null) out.add(start);
        out.addAll(visited);
        return new ArrayList<>(out);
    }

    /** Where the line's `cd`s have led so far (null = nowhere yet). Hand it back to {@link #returnTo} to undo later ones. */
    public Path cd() {
        return cd;
    }

    /** A `cd` was read: the next commands start in this folder. */
    void enter(Path to) {
        cd = to;
        if (!visited.contains(to)) visited.add(to);
    }

    /** Back to an earlier folder (`cd -`, the end of a command list). The folders seen stay possible places. */
    void returnTo(Path earlier) {
        cd = earlier;
    }

    /** The command line is done: the folders it cd'ed into are forgotten with it. */
    void forgetLine() {
        cd = null;
        visited.clear();
    }
}
