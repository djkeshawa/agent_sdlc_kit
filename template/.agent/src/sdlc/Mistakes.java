package sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The mistakes log, .sdlc/logs/mistakes.jsonl: every blocking review finding, QA failure,
 * CI failure, SonarQube issue and hook refusal as a "found" event, and a "fixed" event with
 * the commit that fixed it. `sdlc retro <STORY>` summarises it.
 */
final class Mistakes {
    private Mistakes() {}

    static void found(String story, String task, String source, int round, String category, String location, String summary) {
        String id = source + ":" + Verify.sha256(story + task + source + summary.trim().toLowerCase()).substring(0, 10);
        Store.log("mistakes", Store.map("event", "found", "id", id, "story", story, "task", task, "source", source, "round", round,
                "category", category, "location", location, "summary", summary, "commit", Git.shortHead()));
    }

    /** Marks every open mistake of the story (optionally one task) from these sources as fixed at HEAD. */
    static int fixAll(String story, String task, List<String> sources) {
        int n = 0;
        for (Map<String, Object> m : open(story)) {
            if (!sources.contains(String.valueOf(m.get("source")))) continue;
            if (task != null && !task.equals(m.get("task"))) continue;
            Store.log("mistakes", Store.map("event", "fixed", "id", m.get("id"), "story", story, "task", m.get("task"), "source", m.get("source"), "fixedIn", Git.shortHead()));
            n++;
        }
        return n;
    }

    static List<Map<String, Object>> open(String story) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> e : Store.readLog("mistakes")) {
            if (!story.equals(e.get("story"))) continue;
            String id = String.valueOf(e.get("id"));
            if ("found".equals(e.get("event"))) byId.put(id, e);
            if ("fixed".equals(e.get("event"))) byId.remove(id);
        }
        return new ArrayList<>(byId.values());
    }

    static int retro(String story, boolean comment) {
        List<Map<String, Object>> all = Store.readLog("mistakes").stream().filter(e -> story.isEmpty() || story.equals(e.get("story"))).toList();
        Map<String, Integer> bySource = new TreeMap<>();
        Map<String, Integer> byCategory = new TreeMap<>();
        Map<String, String> fixedIn = new LinkedHashMap<>();
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> e : all) {
            if ("found".equals(e.get("event"))) {
                found.add(e);
                bySource.merge(String.valueOf(e.get("source")), 1, Integer::sum);
                String cat = String.valueOf(e.getOrDefault("category", ""));
                if (!cat.isBlank() && !cat.equals("null")) byCategory.merge(cat, 1, Integer::sum);
            } else if ("fixed".equals(e.get("event"))) {
                fixedIn.put(String.valueOf(e.get("id")), String.valueOf(e.get("fixedIn")));
            }
        }
        List<Map<String, Object>> refusals = Store.readLog("guard-decisions").stream()
                .filter(e -> story.isEmpty() || story.equals(e.get("story"))).filter(e -> "DENY".equals(e.get("decision"))).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Retro for ").append(story.isEmpty() ? "all stories" : story).append('\n');
        sb.append("Mistakes found: ").append(found.size()).append(", fixed: ").append(found.stream().filter(f -> fixedIn.containsKey(String.valueOf(f.get("id")))).count())
                .append(", hook refusals: ").append(refusals.size()).append('\n');
        sb.append("By source: ").append(bySource).append('\n');
        if (!byCategory.isEmpty()) sb.append("By rule/category: ").append(byCategory).append('\n');
        if (!story.isEmpty()) {
            Map<String, Object> state = Work.state(story);
            sb.append("Loops:\n");
            for (String line : Gates.summary(story)) sb.append("  ").append(line).append('\n');
            if (state.isEmpty()) sb.append("  (no local state)\n");
        }
        sb.append("\nDetails:\n");
        for (Map<String, Object> f : found) {
            String fix = fixedIn.get(String.valueOf(f.get("id")));
            sb.append(String.format("- [%s r%s] %s %s %s -> %s%n", f.get("source"), Json.str(f, "round"), f.getOrDefault("task", ""), loc(f), f.get("summary"),
                    fix == null ? "OPEN" : "fixed in " + fix));
        }
        Map<String, Integer> refusalReasons = new TreeMap<>();
        for (Map<String, Object> r : refusals) refusalReasons.merge(Http.abbreviate(String.valueOf(r.get("reason")), 80), 1, Integer::sum);
        if (!refusalReasons.isEmpty()) {
            sb.append("\nHook refusals (what agents tried):\n");
            refusalReasons.forEach((k, v) -> sb.append("- ").append(v).append("x ").append(k).append('\n'));
        }
        System.out.println(sb);
        if (comment && !story.isEmpty()) Jira.comment(story, Approvals.BOT_MARK + " Retro\n{noformat}\n" + sb + "{noformat}", false);
        return 0;
    }

    private static String loc(Map<String, Object> f) {
        Object l = f.get("location");
        return l == null || l.toString().isBlank() || l.toString().equals("null") ? "" : "(" + l + ")";
    }
}
