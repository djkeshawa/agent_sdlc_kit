package sdlc.selftest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.core.Console;
import sdlc.workflow.IssueTracker;

/** An issue tracker, a wiki and a console in memory, for the workflow self tests. */
final class Fakes {
    private Fakes() {}

    /** A console that keeps what the steps say, so a test can read it and the test run stays quiet. */
    static final class Recorder implements Console {
        final List<String> out = new ArrayList<>(), err = new ArrayList<>();

        public void out(String line) { out.add(line); }

        public void err(String line) { err.add(line); }
    }

    /** An issue tracker in memory. */
    static final class Tracker implements IssueTracker {
        final Map<String, List<Comment>> comments = new LinkedHashMap<>();
        final Map<String, String> status = new LinkedHashMap<>();
        final Map<String, String> parent = new LinkedHashMap<>();
        final List<Issue> created = new ArrayList<>();
        final Map<String, String> descriptions = new LinkedHashMap<>();
        private int clock;

        void say(String key, String body, String who, boolean edited) {
            String at = "t" + (++clock);
            comments.computeIfAbsent(key, k -> new ArrayList<>()).add(new Comment(body, who, who, at, edited ? at + "-edited" : at));
        }

        public String text(String key, int maxComments) { return "# " + key; }
        public List<String> parents(String key) {
            List<String> out = new ArrayList<>();
            for (String p = parent.get(key); p != null && out.size() < 5; p = parent.get(p)) out.add(p);
            return out;
        }
        public List<Comment> comments(String key) { return comments.getOrDefault(key, List.of()); }
        public void comment(String key, String body, boolean fromAgent) { say(key, body, "bot", false); }
        public List<Transition> transitions(String key) { return List.of(); }
        public boolean transition(String key, String to, boolean fromAgent) { return forward(key, to); }
        public boolean forward(String key, String target) { return !target.equals(status.put(key, target)); }
        public List<Issue> children(String p) { return created.stream().filter(i -> p.equals(parent.get(i.key()))).toList(); }
        public List<Issue> search(String query, int max) { return List.of(); }
        public void link(String key, String url, String title) { }
        public String login() { return "as a fake"; }

        public String createSubtask(String p, String summary, String description) {
            return createChild(p, "Sub-task", summary, description);
        }

        public String createChild(String p, String type, String summary, String description) {
            Issue i = new Issue("PROJ-" + (101 + created.size()), summary, "To Do", type);
            created.add(i);
            parent.put(i.key(), p);
            descriptions.put(i.key(), description);
            return i.key();
        }
    }

    /** A wiki in memory. */
    static final class Wiki implements sdlc.workflow.Wiki {
        final Map<String, sdlc.workflow.Wiki.Page> pages = new LinkedHashMap<>();

        void edit(String id, String append, String author) {
            sdlc.workflow.Wiki.Page p = pages.get(id);
            pages.put(id, new sdlc.workflow.Wiki.Page(id, p.title(), p.space(), p.version() + 1, p.storage() + append, p.url(), author));
        }

        public sdlc.workflow.Wiki.Page page(String id) { return pages.get(id); }
        public void attach(String pageId, String fileName, byte[] data, String mime) { }
        public void ping() { }

        public List<sdlc.workflow.Wiki.Ref> titled(String words, List<String> spaces) {
            return pages.values().stream().filter(p -> p.title().contains(words) && (spaces.isEmpty() || spaces.contains(p.space()))).map(Wiki::ref).toList();
        }

        public sdlc.workflow.Wiki.Ref byTitle(String space, String title) {
            return pages.values().stream().filter(p -> p.space().equals(space) && p.title().equals(title)).map(Wiki::ref).findFirst().orElse(null);
        }

        public sdlc.workflow.Wiki.Page publish(String space, String parentId, String title, String storage) {
            sdlc.workflow.Wiki.Ref old = byTitle(space, title);
            String id = old == null ? String.valueOf(5000 + pages.size()) : old.id();
            sdlc.workflow.Wiki.Page p = new sdlc.workflow.Wiki.Page(id, title, space, old == null ? 1 : pages.get(id).version() + 1, storage, "https://wiki.test/" + id, "bot");
            pages.put(id, p);
            return p;
        }

        private static sdlc.workflow.Wiki.Ref ref(sdlc.workflow.Wiki.Page p) {
            return new sdlc.workflow.Wiki.Ref(p.id(), p.title(), p.space(), String.valueOf(p.version()), p.url());
        }
    }
}
