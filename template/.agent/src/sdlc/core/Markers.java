package sdlc.core;

/** Words that mean something to both the guard and the workflow. */
public final class Markers {
    private Markers() {}

    /** The first word of the line a human posts in the issue tracker to approve a document. Agents never write it. */
    public static final String APPROVAL_WORD = "SDLC-APPROVED";
}
