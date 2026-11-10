package sdlc.workflow;

import java.util.List;

/**
 * What the workflow needs from an issue tracker. Jira is the implementation; the workflow
 * classes (Approvals, Docs, Tasks, Sync ...) know only this interface, so they can be tested
 * with an in-memory tracker and another tracker can be added without touching them.
 */
public interface IssueTracker {
    /** One comment on an issue. authorId is what gates.approvers lists (Cloud account id, Data Center username). */
    record Comment(String body, String authorId, String authorName, String created, String updated) {
        /** An edited comment never counts as an approval. */
        boolean edited() {
            return !created.equals(updated);
        }
    }

    record Issue(String key, String summary, String status, String type) {}

    record Transition(String id, String name, String to) {}

    /** The issue as text for agents: fields, parents, sub-tasks, links, description and the last comments. */
    String text(String key, int maxComments);

    /** Parent keys, nearest first: sub-task -> story -> epic. */
    List<String> parents(String key);

    List<Comment> comments(String key);

    /** Adds a comment. fromAgent = asked for by an agent: an approval line is refused. */
    void comment(String key, String body, boolean fromAgent);

    List<Transition> transitions(String key);

    /** Moves the issue to a status by name; false when the workflow has no such move from where it is. Agents may only use the configured statuses. */
    boolean transition(String key, String status, boolean fromAgent);

    /** Moves forward only (To Do -> In Progress -> In Review -> Done); false when the issue is already there or further. */
    boolean forward(String key, String target);

    /** The sub-tasks (and other children) of an issue. */
    List<Issue> children(String parent);

    /** Creates a sub-task and returns its key. */
    String createSubtask(String parent, String summary, String description);

    /** Creates an issue of the given type under a parent (a story under an epic) and returns its key. */
    String createChild(String parent, String type, String summary, String description);

    /** A search in the tracker's own query language (JQL). */
    List<Issue> search(String query, int max);

    /** Links a web page (the pull request) to the issue. */
    void link(String key, String url, String title);

    /** Who the tool is logged in as, in words, for `doctor`. Throws when the tracker cannot be reached. */
    String login();
}
