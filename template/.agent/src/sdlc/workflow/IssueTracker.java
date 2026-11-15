package sdlc.workflow;

import java.util.List;

public interface IssueTracker {
    record Comment(String body, String authorId, String authorName, String created, String updated) {
        boolean edited() {
            return !created.equals(updated);
        }
    }

    record Issue(String key, String summary, String status, String type) {}

    record Transition(String id, String name, String to) {}

    String text(String key, int maxComments);

    List<String> parents(String key);

    List<Comment> comments(String key);

    void comment(String key, String body, boolean fromAgent);

    List<Transition> transitions(String key);

    boolean transition(String key, String status, boolean fromAgent);

    boolean forward(String key, String target);

    List<Issue> children(String parent);

    String createSubtask(String parent, String summary, String description);

    String createChild(String parent, String type, String summary, String description);

    List<Issue> search(String query, int max);

    void link(String key, String url, String title);

    String login();
}
