package sdlc.workflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.UsageException;
import sdlc.core.Work;

/**
 * sdlc feature status <EPIC>: one view of a cross-component feature, read from what already
 * exists: the solution design and its approval (Confluence + Jira), the component stories (Jira),
 * each story's LLD and its approval, and whether that LLD was written from the current slice.
 */
public final class Feature {
    private static final Pattern PREFIX = Pattern.compile("^\\[([a-z0-9][a-z0-9._-]*)\\]");

    private final Workflow w;

    Feature(Workflow w) {
        this.w = w;
    }

    public int status(String epic) {
        Docs.Doc d = w.docs.own(epic, "solution");
        if (d == null) throw new UsageException("no solution design for " + epic + " (the architect runs /solution " + epic + ")");
        Wiki.Page page = w.wiki.page(d.id());
        Solution s = Solution.parseStorage(page.storage());
        Approvals.Result approval = w.approvals.check(epic, "solution");
        w.console.out("Feature " + epic + ": " + page.title() + " v" + page.version() + " - " + (approval.approved() ? "APPROVED by " + approval.by() : "NOT APPROVED (" + String.join("; ", approval.reasons()) + ")"));
        w.console.out("Merge order: " + (s.order.isEmpty() ? "(none given)" : String.join(" -> ", s.order)));
        Map<String, IssueTracker.Issue> stories = new LinkedHashMap<>();
        for (IssueTracker.Issue i : w.issues.children(epic)) {
            Matcher m = PREFIX.matcher(i.summary());
            if (m.find()) stories.put(m.group(1), i);
        }
        Set<String> names = new LinkedHashSet<>(s.order);
        names.addAll(s.components.keySet());
        int ready = 0;
        w.console.out(String.format("%n%-22s %-10s %-12s %-44s %s", "Component", "Story", "Jira", "LLD", "Slice"));
        for (String name : names) {
            IssueTracker.Issue story = stories.get(name);
            if (story == null) {
                w.console.out(String.format("%-22s %-10s %-12s %-44s %s", name, "-", "-", "no story yet (stories create " + epic + ")", s.sliceHash(name)));
                continue;
            }
            String lld = "no LLD yet", slice = s.sliceHash(name);
            for (Docs.Doc doc : w.docs.find(story.key())) {
                if (!doc.type().equals("lld") || !doc.key().equals(story.key())) continue;
                Approvals.Result r = w.approvals.checkDoc(doc, "lld");
                Wiki.Page p = w.wiki.page(doc.id());
                String have = "";
                try {
                    have = Work.str(Tasks.blockStorage(p.storage()).get("solutionSlice")).strip();
                } catch (UsageException e) {
                    have = "no tasks block";
                }
                boolean current = have.equals(slice);
                lld = "v" + p.version() + (r.approved() ? " approved" : " NOT approved");
                slice = current ? slice + " current" : slice + " (LLD has " + (have.isBlank() ? "none" : have) + ": OUTDATED)";
                if (r.approved() && current) ready++;
                break;
            }
            w.console.out(String.format("%-22s %-10s %-12s %-44s %s", name, story.key(), story.status(), lld, slice));
        }
        for (String extra : stories.keySet()) if (!s.components.containsKey(extra)) w.console.out(String.format("%-22s %-10s %-12s %s", extra, stories.get(extra).key(), stories.get(extra).status(), "NOT in the solution any more"));
        w.console.out("\n" + ready + " of " + names.size() + " component LLDs approved and current.");
        return approval.approved() && ready == names.size() ? 0 : 1;
    }
}
