package sdlc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keeps Jira statuses in step with the work: started -> In Progress, PR open -> In Review, merged -> Done. */
final class Sync {
    private Sync() {}

    /** The pull request of the current branch from gh, or null. */
    static Map<String, Object> pr() {
        Proc p = Proc.run(Ctx.root, "gh", "pr", "view", "--json", "number,url,state,headRefOid,title,isDraft");
        if (!p.ok() || p.out().isBlank()) return null;
        try {
            return Json.obj(Json.parse(p.out()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int run(String story, boolean quiet) {
        if (story.isBlank()) story = Work.currentStory();
        if (story.isBlank()) throw new Main.UsageException("usage: sdlc sync <STORY>");
        Map<String, Object> state = Work.state(story);
        String inProgress = Ctx.cfg.str("jira.statuses.inProgress");
        String inReview = Ctx.cfg.str("jira.statuses.inReview");
        String done = Ctx.cfg.str("jira.statuses.done");
        List<String> moved = new ArrayList<>();
        Map<String, Object> pr = pr();
        String prState = pr == null ? "" : String.valueOf(pr.get("state"));
        if (pr != null) state.put("pr", Store.map("number", Json.str(pr, "number"), "url", pr.get("url"), "state", prState, "linked", Json.str(state, "pr.linked").equals("true")));
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Object> e : Work.tasks(state).entrySet()) {
            String status = String.valueOf(Json.obj(e.getValue()).get("status"));
            if (!Ctx.cfg.keyPattern().matcher(e.getKey()).matches() || status.equals("new") || status.equals("null")) continue;
            keys.add(e.getKey());
        }
        if (Ctx.cfg.keyPattern().matcher(story).matches() && (!keys.isEmpty() || pr != null)) keys.add(story);
        String target = prState.equals("MERGED") && Ctx.cfg.bool("jira.agentMayClose", false) ? done : prState.equals("OPEN") || prState.equals("MERGED") ? inReview : inProgress;
        for (String k : keys) if (Jira.forward(k, target)) moved.add(k + " -> " + target);
        if (pr != null && prState.equals("OPEN") && !Json.str(state, "pr.linked").equals("true")) {
            Jira.link(story, String.valueOf(pr.get("url")), "Pull request #" + Json.str(pr, "number"));
            Jira.comment(story, Approvals.BOT_MARK + " Pull request opened: " + pr.get("url") + "\nCI results will follow. Human review and merge required.", false);
            Json.obj(state.get("pr")).put("linked", "true");
            moved.add("linked PR #" + Json.str(pr, "number"));
        }
        if (prState.equals("MERGED") && !Ctx.cfg.bool("jira.agentMayClose", false) && !Json.str(state, "mergedNoted").equals("true")) {
            Jira.comment(story, Approvals.BOT_MARK + " Pull request merged: " + pr.get("url") + ". Please move the tickets to " + done + ".", false);
            state.put("mergedNoted", "true");
        }
        Work.save(story, state);
        Store.log("sync", Store.map("story", story, "moved", moved));
        if (!quiet) System.out.println(moved.isEmpty() ? "Jira is up to date for " + story + "." : "Jira: " + String.join(", ", moved)); // quiet = called from a hook, whose stdout must be JSON only
        return 0;
    }

    /** Called by the PostToolUse hook after `gh pr create`. */
    static void prOpened() {
        String story = Work.currentStory();
        if (!story.isEmpty()) run(story, true);
    }
}
