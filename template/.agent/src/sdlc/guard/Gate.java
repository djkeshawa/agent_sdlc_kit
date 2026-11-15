package sdlc.guard;

public interface Gate {
    Decision push();

    Decision openPullRequest();

    Decision anotherFixRound();

    Decision recordReview();
}
