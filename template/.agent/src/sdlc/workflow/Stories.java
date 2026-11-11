package sdlc.workflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Json;

/**
 * sdlc stories create <EPIC>: one Jira story per component of the approved solution design,
 * each carrying its slice and the slice's hash. Run again after a new solution version: stories
 * whose slice changed get a comment that their LLD must be updated and approved again; the others
 * are left alone.
 */
public final class Stories {
    private static final Pattern PREFIX = Pattern.compile("^\\[([a-z0-9][a-z0-9._-]*)\\]");

    private final Workflow w;

    Stories(Workflow w) {
        this.w = w;
    }

    public int create(String epic) {
        if (!w.approvals.gate(epic, "solution")) return 1;
        Docs.Doc d = w.docs.own(epic, "solution");
        if (d == null) throw new UsageException("no solution design found for " + epic);
        if (!d.key().equals(epic)) throw new UsageException("the solution design belongs to " + d.key() + ", not " + epic + ": run stories create " + d.key());
        Wiki.Page page = w.wiki.page(d.id());
        Solution s = Solution.parseStorage(page.storage());
        List<String> problems = s.problems(Catalog.load());
        if (!problems.isEmpty()) {
            w.console.err("The approved solution design has problems; fix the page, get it approved again:\n- " + String.join("\n- ", problems));
            return 1;
        }
        Map<String, String> existing = new LinkedHashMap<>();
        for (IssueTracker.Issue i : w.issues.children(epic)) {
            Matcher m = PREFIX.matcher(i.summary());
            if (m.find()) existing.put(m.group(1), i.key());
        }
        Map<String, Object> state = Work.state(epic);
        Map<String, Object> stories = Json.obj(state.computeIfAbsent("stories", k -> new LinkedHashMap<String, Object>()));
        String type = Ctx.cfg.str("jira.issueTypes.story", "Story");
        Set<String> names = new LinkedHashSet<>(s.order);
        names.addAll(s.components.keySet());
        int created = 0, changed = 0;
        for (String name : names) {
            Solution.Component c = s.components.get(name);
            String hash = s.sliceHash(name);
            String key = existing.get(name);
            Map<String, Object> rec = Json.obj(stories.computeIfAbsent(name, k -> new LinkedHashMap<String, Object>()));
            if (key == null) {
                key = w.issues.createChild(epic, type, summary(c), description(s, name, hash, page));
                w.console.out("Created " + key + " [" + name + "] slice " + hash);
                created++;
            } else if (!rec.isEmpty() && !hash.equals(rec.get("slice"))) {
                w.issues.comment(key, Approvals.BOT_MARK + " Solution design v" + page.version() + " changed this story's slice (" + rec.get("slice") + " -> " + hash + "). "
                        + "Its LLD must be updated (solutionSlice: " + hash + ") and approved again before more tasks start.\n{noformat}" + s.slice(name) + "{noformat}", false);
                w.console.out("Changed " + key + " [" + name + "] slice " + rec.get("slice") + " -> " + hash + " (commented: LLD must be re-approved)");
                changed++;
            } else {
                w.console.out("Exists  " + key + " [" + name + "] slice " + hash);
            }
            rec.put("key", key);
            rec.put("slice", hash);
            rec.put("role", c.role());
        }
        for (Map.Entry<String, String> e : existing.entrySet()) {
            if (s.components.containsKey(e.getKey())) continue;
            w.console.err("Note: " + e.getValue() + " [" + e.getKey() + "] is no longer in the solution design. A human decides whether to close it.");
        }
        state.put("solution", Store.map("id", page.id(), "version", page.version(), "hash", Approvals.hash(page.storage()), "url", page.url()));
        Work.save(epic, state);
        w.console.out("\n" + names.size() + " component stor" + (names.size() == 1 ? "y" : "ies") + " for " + epic + " (" + created + " created, " + changed + " changed) from solution design v" + page.version() + ".");
        w.console.out("Each team: java .agent/Sdlc.java docs pull <STORY>, then /design <STORY>. Progress: java .agent/Sdlc.java feature status " + epic);
        return 0;
    }

    private static String summary(Solution.Component c) {
        String s = "[" + c.name() + "] " + (c.change().isBlank() ? "change" : c.change());
        return s.length() > 240 ? s.substring(0, 237) + "..." : s;
    }

    private static String description(Solution s, String name, String hash, Wiki.Page page) {
        return "Created by sdlc from the approved solution design [" + page.title() + " v" + page.version() + "|" + page.url() + "].\n\n"
                + "h3. This component's slice\n{noformat}" + s.slice(name) + "{noformat}\n\n"
                + "The LLD of this story carries {{solutionSlice: " + hash + "}} and {{lane: cross-component}} in its sdlc-tasks block. "
                + "Contracts: add or remove only what the slice provides; anything else is a solution change request.";
    }

    /** Component -> story key, from the epic's local state (after stories create). */
    static Map<String, String> storyKeys(String epic) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(Work.state(epic).get("stories")).entrySet()) out.put(e.getKey(), Work.str(Json.obj(e.getValue()).get("key")));
        return out;
    }
}
