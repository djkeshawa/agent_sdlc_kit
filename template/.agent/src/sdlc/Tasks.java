package sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the approved LLD's work breakdown into Jira sub-tasks, and starts / finishes a task.
 * The LLD carries the breakdown as a YAML code block that starts with "# sdlc-tasks":
 *
 *   # sdlc-tasks
 *   tasks:
 *     - id: T1
 *       title: Add the refund endpoint
 *       scope: [src/main/java/com/acme/refund/**, src/test/java/com/acme/refund/**]
 *       scenarios: [TS-01, TS-02]
 *       acceptance:
 *         - Given a paid order, when POST /refunds, then 201 and the refund is stored
 */
final class Tasks {
    private static final Pattern FENCED = Pattern.compile("(?s)```ya?ml\\s*\\n(#\\s*sdlc-tasks.*?)```");
    private static final Pattern TS_LEVEL = Pattern.compile("(?im)^#{2,4}\\s*(TS-[0-9]+)\\b.*?$((?:(?!^#{2,4}\\s*TS-).)*)", Pattern.DOTALL);

    private Tasks() {}

    /** Parses the tasks block from Markdown (draft) text. */
    static List<Map<String, Object>> parse(String markdown) {
        Matcher m = FENCED.matcher(markdown);
        if (!m.find()) throw new Main.UsageException("the LLD has no work breakdown: a ```yaml code block starting with '# sdlc-tasks' (see .agent/templates/lld.md)");
        return fromYaml(m.group(1));
    }

    /** Parses the tasks block from a Confluence page (storage format). */
    static List<Map<String, Object>> parseStorage(String storage) {
        for (String[] b : Markdown.codeBlocks(storage)) if (b[1].stripLeading().startsWith("# sdlc-tasks")) return fromYaml(b[1]);
        throw new Main.UsageException("the approved LLD page has no '# sdlc-tasks' code block");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fromYaml(String yaml) {
        Object root = Yaml.parse(yaml);
        Object list = root instanceof Map<?, ?> r ? r.get("tasks") : null;
        if (!(list instanceof List<?> l) || l.isEmpty()) throw new Main.UsageException("'# sdlc-tasks' block needs a non-empty 'tasks:' list");
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> t)) throw new Main.UsageException("each task must be a mapping with id, title, scope");
            Map<String, Object> task = (Map<String, Object>) t;
            String id = Work.str(task.get("id"));
            if (id.isBlank() || !ids.add(id)) throw new Main.UsageException("every task needs a unique id (T1, T2 ...): " + task);
            if (Work.str(task.get("title")).isBlank()) throw new Main.UsageException(id + ": title is missing");
            List<String> scope = Work.strings(task.get("scope"));
            if (scope.isEmpty()) throw new Main.UsageException(id + ": scope (allowed paths) is missing");
            for (String s : scope) if (s.equals("**") || s.equals("src/**") || s.startsWith("/") || s.startsWith("..")) throw new Main.UsageException(id + ": scope '" + s + "' is too broad or outside the repository");
            if (Work.strings(task.get("acceptance")).isEmpty()) throw new Main.UsageException(id + ": acceptance criteria are missing");
            out.add(task);
        }
        return out;
    }

    /** sdlc tasks create <STORY>: needs the approved LLD; idempotent. */
    static int create(String story) {
        if (!Approvals.gate(story, "lld")) return 1;
        Docs.Doc lld = Docs.own(story, "lld");
        if (lld == null) throw new Main.UsageException("no LLD found for " + story);
        Confluence.Page page = Confluence.page(lld.id());
        List<Map<String, Object>> tasks = parseStorage(page.storage());
        Map<String, Object> state = Work.state(story);
        state.put("lld", Store.map("key", lld.key(), "id", page.id(), "version", page.version(), "hash", Approvals.hash(page.storage()), "url", page.url()));
        state.put("qaScenarios", qaScenarios(story));
        Map<String, String> existing = new LinkedHashMap<>();
        boolean subtasks = Ctx.cfg.bool("delivery.createSubtasks", true);
        if (subtasks) {
            for (Map<String, Object> i : Jira.search("parent = " + story, 200)) {
                Matcher m = Pattern.compile("^\\[(T[0-9A-Za-z_-]+)\\]").matcher(Json.str(i, "fields.summary"));
                if (m.find()) existing.put(m.group(1), Json.str(i, "key"));
            }
        }
        for (Map<String, Object> t : tasks) {
            String id = Work.str(t.get("id"));
            String key = subtasks ? existing.get(id) : story + "/" + id;
            if (key == null) {
                key = Jira.createSubtask(story, "[" + id + "] " + t.get("title"), description(story, t, page));
                System.out.println("Created " + key + " [" + id + "] " + t.get("title"));
            } else {
                System.out.println("Exists  " + key + " [" + id + "] " + t.get("title"));
            }
            Map<String, Object> ts = Work.taskState(state, key);
            ts.put("id", id);
            ts.put("title", t.get("title"));
            ts.put("scope", Work.strings(t.get("scope")));
            ts.put("scenarios", Work.strings(t.get("scenarios")));
            ts.put("acceptance", Work.strings(t.get("acceptance")));
            ts.put("dependsOn", Work.strings(t.get("dependsOn")));
            ts.putIfAbsent("status", "new");
        }
        Work.save(story, state);
        Work.setCurrent(story, "");
        System.out.println("\n" + tasks.size() + " task(s) for " + story + " from LLD v" + page.version() + ". Start one with: java .agent/Sdlc.java task start <TASK-KEY>");
        return 0;
    }

    private static String description(String story, Map<String, Object> t, Confluence.Page lld) {
        StringBuilder sb = new StringBuilder();
        sb.append("Created by sdlc from the approved LLD [").append(lld.title()).append(" v").append(lld.version()).append("|").append(lld.url()).append("].\n\n");
        sb.append("h3. Acceptance criteria\n");
        for (String a : Work.strings(t.get("acceptance"))) sb.append("* ").append(a).append('\n');
        List<String> sc = Work.strings(t.get("scenarios"));
        if (!sc.isEmpty()) sb.append("\nh3. Test scenarios\n").append(String.join(", ", sc)).append('\n');
        sb.append("\nh3. Allowed paths\n");
        for (String s : Work.strings(t.get("scope"))) sb.append("* {{").append(s).append("}}\n");
        return sb.toString();
    }

    /** TS ids of the test design whose level is integration or e2e (they need the QA loop). */
    private static List<String> qaScenarios(String story) {
        List<String> out = new ArrayList<>();
        try {
            Docs.Doc td = Docs.own(story, "testDesign");
            if (td == null) return out;
            String md = Markdown.toMarkdown(Confluence.page(td.id()).storage());
            Matcher m = TS_LEVEL.matcher(md);
            while (m.find()) if (m.group(2).toLowerCase().matches("(?s).*level\\W+(integration|e2e|end[- ]to[- ]end|contract|api).*")) out.add(m.group(1));
        } catch (RuntimeException e) {
            System.err.println("[sdlc] could not read the test design for QA scenarios: " + e.getMessage());
        }
        return out;
    }

    /** sdlc task start <TASK>: checks the LLD approval is still valid, sets the scope, moves Jira. */
    static int start(String taskKey) {
        String story = storyOf(taskKey);
        if (story == null) throw new Main.UsageException(taskKey + " is not a task of any story here. Run: java .agent/Sdlc.java tasks create <STORY>");
        String branch = Git.branch();
        if (Ctx.cfg.isProtectedBranch(branch) || !branch.contains(story)) {
            System.err.println("Switch to the story's branch first (its name must contain " + story + "):\n  git switch -c feature/" + story + "-short-name origin/" + Ctx.cfg.baseBranch());
            return 1;
        }
        Map<String, Object> state = Work.state(story);
        if (!Ctx.cfg.str("gates.lld", "required").equals("off") && state.get("lld") instanceof Map<?, ?> lld) {
            Confluence.Page now = Confluence.page(String.valueOf(lld.get("id")));
            if (!Approvals.hash(now.storage()).equals(lld.get("hash"))) {
                System.err.println("The LLD changed since the tasks were created (now v" + now.version() + "). Get it re-approved and run: java .agent/Sdlc.java tasks create " + story);
                return 1;
            }
        }
        Map<String, Object> t = Work.taskState(state, taskKey);
        for (String dep : Work.strings(t.get("dependsOn"))) {
            for (Map.Entry<String, Object> e : Work.tasks(state).entrySet()) {
                if (dep.equals(Json.obj(e.getValue()).get("id")) && !"approved".equals(Json.obj(e.getValue()).get("status"))) {
                    System.err.println(taskKey + " depends on " + dep + " (" + e.getKey() + "), which is not approved yet.");
                    return 1;
                }
            }
        }
        if (!"approved".equals(t.get("status"))) t.put("status", "started");
        state.put("branch", branch);
        Work.save(story, state);
        Work.setCurrent(story, taskKey);
        Store.log("task", Store.map("action", "start", "story", story, "task", taskKey));
        for (String key : List.of(taskKey, story)) {
            if (!Ctx.cfg.keyPattern().matcher(key).matches()) continue;
            try {
                if (Jira.forward(key, Ctx.cfg.str("jira.statuses.inProgress"))) System.out.println(key + " -> " + Ctx.cfg.str("jira.statuses.inProgress"));
            } catch (RuntimeException e) {
                System.err.println("[sdlc] could not move " + key + " in Jira: " + e.getMessage());
            }
        }
        System.out.println("Task " + taskKey + " [" + t.get("id") + "] " + t.get("title") + " started on " + branch + ".");
        System.out.println("Agents may edit only:\n  " + String.join("\n  ", Work.strings(t.get("scope"))));
        System.out.println("Acceptance criteria:\n  - " + String.join("\n  - ", Work.strings(t.get("acceptance"))));
        if (!Work.strings(t.get("scenarios")).isEmpty()) System.out.println("Test scenarios to link in " + Ctx.rel(Trace.linksFile(story)) + ": " + Work.strings(t.get("scenarios")));
        return 0;
    }

    static String storyOf(String taskKey) {
        String cur = Work.currentStory();
        if (!cur.isEmpty() && Work.tasks(Work.state(cur)).containsKey(taskKey)) return cur;
        if (taskKey.contains("/")) return taskKey.substring(0, taskKey.indexOf('/'));
        try (var dirs = java.nio.file.Files.list(Ctx.work())) {
            for (java.nio.file.Path d : dirs.toList()) {
                if (java.nio.file.Files.exists(d.resolve("state.json")) && Work.tasks(Work.state(d.getFileName().toString())).containsKey(taskKey)) return d.getFileName().toString();
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // no local state yet
        }
        return null;
    }

    static int finish() {
        Map<String, Object> c = Work.current();
        Work.setCurrent(String.valueOf(c.getOrDefault("story", "")), "");
        System.out.println("Task " + c.getOrDefault("task", "(none)") + " closed locally; code edits need a started task again.");
        return 0;
    }

    static int list(String story) {
        if (story.isBlank()) story = Work.currentStory();
        if (story.isBlank()) throw new Main.UsageException("usage: sdlc tasks list <STORY>");
        for (String line : Gates.summary(story)) System.out.println(line);
        return 0;
    }
}
