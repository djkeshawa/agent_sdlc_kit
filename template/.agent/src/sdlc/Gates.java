package sdlc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workflow gates enforced by the hooks: the inner loop (implement -> verify -> review, min..max
 * rounds), the QA loop and the CI loop. They decide when a pull request may be opened, when a
 * branch may be pushed again, and when to stop and hand back to the human.
 */
final class Gates {
    private Gates() {}

    /** gh pr create */
    static Decision prCreate() {
        String story = Work.currentStory();
        if (story.isEmpty()) return Decision.deny("no story in progress. Pull requests are opened by /feature after the task loops: java .agent/Sdlc.java status");
        List<String> missing = prProblems(story, null);
        return missing.isEmpty() ? Decision.allow() : Decision.deny("the pull request gate is not met yet:\n- " + String.join("\n- ", missing));
    }

    static List<String> prProblems(String story, String title) {
        List<String> out = new ArrayList<>();
        Map<String, Object> state = Work.state(story);
        Map<String, Object> tasks = Work.tasks(state);
        int min = Ctx.cfg.num("loops.review.min", 2);
        if (tasks.isEmpty()) out.add("the story has no tasks (java .agent/Sdlc.java tasks create " + story + ")");
        String head = Git.head();
        for (Map.Entry<String, Object> e : tasks.entrySet()) {
            Map<String, Object> t = Json.obj(e.getValue());
            List<Object> reviews = Work.list(t, "reviews");
            if (reviews.size() < min) out.add(e.getKey() + ": " + reviews.size() + " review round(s), at least " + min + " needed");
            if (reviews.isEmpty() || !"APPROVE".equals(Json.obj(reviews.get(reviews.size() - 1)).get("verdict"))) out.add(e.getKey() + ": the last review did not approve");
        }
        Map<String, Object> lastReview = lastReview(state);
        if (lastReview != null && !head.startsWith(String.valueOf(lastReview.get("commit")))) {
            out.add("commits were added after the last review (" + lastReview.get("commit") + "); review the current HEAD");
        }
        if (qaRequired(state)) {
            List<Object> qa = Work.list(state, "qa");
            Map<String, Object> last = qa.isEmpty() ? null : Json.obj(qa.get(qa.size() - 1));
            if (last == null || !"PASS".equals(last.get("verdict")) || !head.startsWith(String.valueOf(last.get("commit")))) {
                out.add("QA has not passed at the current HEAD (qa-verifier, then java .agent/Sdlc.java qa record " + story + " --file ...)");
            }
        }
        if (!Git.dirtyFiles().isEmpty()) out.add("uncommitted changes: commit or discard them");
        if (!Verify.current()) out.add("the build was not verified for the current files (java .agent/Sdlc.java verify)");
        Checks.Result policy = Checks.prPolicy(title, true);
        out.addAll(policy.fails());
        return out;
    }

    static Map<String, Object> lastReview(Map<String, Object> state) {
        Map<String, Object> last = null;
        String lastTs = "";
        for (Object t : Work.tasks(state).values()) {
            for (Object r : Work.list(Json.obj(t), "reviews")) {
                Map<String, Object> m = Json.obj(r);
                String ts = String.valueOf(m.get("at"));
                if (ts.compareTo(lastTs) > 0) {
                    lastTs = ts;
                    last = m;
                }
            }
        }
        return last;
    }

    static boolean qaRequired(Map<String, Object> state) {
        String mode = Ctx.cfg.str("delivery.qa", "auto");
        if (mode.equals("always")) return true;
        if (mode.equals("never")) return false;
        return !Work.strings(state.get("qaScenarios")).isEmpty();
    }

    /** git push of a feature branch */
    static Decision push() {
        String story = Work.currentStory();
        if (story.isEmpty()) return Decision.allow();
        Map<String, Object> state = Work.state(story);
        List<Object> ci = Work.list(state, "ci");
        int max = Ctx.cfg.num("loops.ci.max", 3);
        long failed = ci.stream().filter(r -> "failed".equals(Json.obj(r).get("result"))).count();
        if (failed >= max) return Decision.deny("the CI fix loop reached its limit (" + max + " failed rounds). Stop and hand over to the human with: java .agent/Sdlc.java retro " + story);
        if (state.get("pr") instanceof Map<?, ?>) {
            Map<String, Object> last = lastReview(state);
            if (last == null || !"APPROVE".equals(last.get("verdict")) || !Git.head().startsWith(String.valueOf(last.get("commit")))) {
                return Decision.deny("this branch has an open pull request: every fix is reviewed before it is pushed. Run the reviewer on the current HEAD first.");
            }
            if (!Verify.current()) return Decision.deny("run java .agent/Sdlc.java verify before pushing a fix.");
        }
        return Decision.allow();
    }

    /** Starting another implementer or ci-fixer run. */
    static Decision moreFixRounds() {
        String story = Work.currentStory();
        if (story.isEmpty()) return Decision.allow();
        Map<String, Object> state = Work.state(story);
        String task = String.valueOf(Work.current().getOrDefault("task", ""));
        int max = Ctx.cfg.num("loops.review.max", 5);
        if (!task.isEmpty()) {
            List<Object> reviews = Work.list(Work.taskState(state, task), "reviews");
            boolean approved = !reviews.isEmpty() && "APPROVE".equals(Json.obj(reviews.get(reviews.size() - 1)).get("verdict"));
            if (reviews.size() >= max && !approved) return Decision.deny("task " + task + " used all " + max + " review rounds. Stop and hand over to the human.");
        }
        long failed = Work.list(state, "ci").stream().filter(r -> "failed".equals(Json.obj(r).get("result"))).count();
        if (failed >= Ctx.cfg.num("loops.ci.max", 3) && state.get("pr") instanceof Map<?, ?>) return Decision.deny("the CI fix loop reached its limit. Stop and hand over to the human.");
        return Decision.allow();
    }

    static Decision reviewRecordAllowed() {
        return Decision.allow(); // the record command enforces its own limits and preconditions
    }

    /** Human-readable gate status for `sdlc status`. */
    static List<String> summary(String story) {
        List<String> out = new ArrayList<>();
        Map<String, Object> state = Work.state(story);
        int min = Ctx.cfg.num("loops.review.min", 2), max = Ctx.cfg.num("loops.review.max", 5);
        for (Map.Entry<String, Object> e : Work.tasks(state).entrySet()) {
            Map<String, Object> t = Json.obj(e.getValue());
            List<Object> reviews = Work.list(t, "reviews");
            String last = reviews.isEmpty() ? "-" : String.valueOf(Json.obj(reviews.get(reviews.size() - 1)).get("verdict"));
            out.add(String.format("%-10s %-9s reviews %d (min %d, max %d), last: %s  %s", e.getKey(), t.getOrDefault("status", "new"), reviews.size(), min, max, last, t.getOrDefault("title", "")));
        }
        List<Object> qa = Work.list(state, "qa");
        out.add("QA: " + (qaRequired(state) ? "required" : "not required") + ", rounds " + qa.size() + (qa.isEmpty() ? "" : ", last " + Json.obj(qa.get(qa.size() - 1)).get("verdict")));
        List<Object> ci = Work.list(state, "ci");
        out.add("CI: rounds " + ci.size() + " (max failed " + Ctx.cfg.num("loops.ci.max", 3) + ")" + (ci.isEmpty() ? "" : ", last " + Json.obj(ci.get(ci.size() - 1)).get("result")));
        if (state.get("pr") instanceof Map<?, ?> pr) out.add("PR: " + pr.get("url"));
        return out;
    }
}
