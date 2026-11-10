package sdlc.guard;

/**
 * What the guard rules need to know about the git repository. GitCli asks git; a test can
 * answer from memory.
 */
public interface Repo {
    /** The current branch, or "" when HEAD is detached and CI does not name one. */
    String branch();

    /** True when the file is part of the last commit. */
    boolean inHead(String rel);

    /** True when the working copy of a committed file differs from the last commit. */
    boolean changedSinceHead(String rel);

    /** What `git <name>` expands to when it is an alias, or "" when it is not one. */
    String alias(String name);
}
