package sdlc.guard;

public interface Repo {
    String branch();

    boolean inHead(String rel);

    boolean changedSinceHead(String rel);

    String alias(String name);
}
