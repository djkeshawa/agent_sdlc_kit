package sdlc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local workflow state, in .sdlc/ (git-ignored, protected from agent edits):
 *   current.json            the story and task being worked on
 *   <STORY>/state.json      tasks, review/QA/CI rounds, the pull request
 *   <STORY>/sources/        pulled one pager, HLD, test design, LLDs (read by agents)
 *   <STORY>/drafts/         agent drafts of the test design and LLD (agent-writable)
 *   <STORY>/inbox/          reports agents hand in (agent-writable)
 *   <STORY>/rounds/         reports filed by the tool (read-only for agents)
 */
final class Work {
    record Task(String key, String story, String id, String title, List<String> scope, List<String> scenarios, List<String> acceptance) {}

    private Work() {}

    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]*-[0-9]+$");

    /** .sdlc/<KEY>: only a real Jira key, so a key from the command line can never point outside .sdlc. */
    static Path storyDir(String story) {
        if (story == null || !KEY.matcher(story).matches()) throw new Main.UsageException("'" + story + "' is not a Jira issue key (like PAY-123)");
        return Ctx.work(story);
    }

    static boolean isKey(String s) {
        return s != null && KEY.matcher(s).matches();
    }

    static Map<String, Object> current() {
        return Store.read(Ctx.work("current.json"));
    }

    static String currentStory() {
        return String.valueOf(current().getOrDefault("story", ""));
    }

    static void setCurrent(String story, String task) {
        Map<String, Object> c = Store.map("story", story, "task", task, "branch", Git.branch(), "since", java.time.Instant.now().toString());
        Store.write(Ctx.work("current.json"), c);
    }

    static Map<String, Object> state(String story) {
        Map<String, Object> s = Store.read(storyDir(story).resolve("state.json"));
        s.putIfAbsent("story", story);
        s.putIfAbsent("tasks", new LinkedHashMap<String, Object>());
        s.putIfAbsent("qa", new ArrayList<Object>());
        s.putIfAbsent("ci", new ArrayList<Object>());
        return s;
    }

    static void save(String story, Map<String, Object> state) {
        Store.write(storyDir(story).resolve("state.json"), state);
    }

    static Map<String, Object> tasks(Map<String, Object> state) {
        return Json.obj(state.get("tasks"));
    }

    static Map<String, Object> taskState(Map<String, Object> state, String key) {
        Map<String, Object> t = tasks(state);
        Object v = t.get(key);
        if (v instanceof Map<?, ?>) return Json.obj(v);
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("reviews", new ArrayList<Object>());
        t.put(key, fresh);
        return fresh;
    }

    static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?>) return Json.list(v);
        List<Object> l = new ArrayList<>();
        m.put(key, l);
        return l;
    }

    /** The task the agent may edit code for, or null. */
    static Task currentTask() {
        Map<String, Object> c = current();
        String story = String.valueOf(c.getOrDefault("story", ""));
        String key = String.valueOf(c.getOrDefault("task", ""));
        if (story.isEmpty() || key.isEmpty()) return null;
        Map<String, Object> t = Json.obj(tasks(state(story)).getOrDefault(key, new LinkedHashMap<>()));
        if (t.isEmpty() || !"started".equals(t.get("status")) && !"approved".equals(t.get("status"))) return null;
        return toTask(story, key, t);
    }

    static Task toTask(String story, String key, Map<String, Object> t) {
        return new Task(key, story, str(t.get("id")), str(t.get("title")), strings(t.get("scope")), strings(t.get("scenarios")), strings(t.get("acceptance")));
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null && !x.toString().isBlank()) out.add(x.toString().trim());
        return out;
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
