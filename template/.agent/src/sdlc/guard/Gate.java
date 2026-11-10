package sdlc.guard;

/**
 * The workflow's say on the few commands whose answer depends on where the story stands. The
 * guard rules ask; the workflow (Gates) answers. A test can answer with a constant.
 */
public interface Gate {
    /** git push of a feature branch. */
    Decision push();

    /** gh pr create. */
    Decision openPullRequest();

    /** Starting another implementer or ci-fixer run. */
    Decision anotherFixRound();

    /** sdlc review record. */
    Decision recordReview();
}
