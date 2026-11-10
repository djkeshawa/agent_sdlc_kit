package sdlc.cli;

import java.util.Map;

import sdlc.core.Ctx;
import sdlc.core.Git;
import sdlc.core.Work;
import sdlc.util.Json;
import sdlc.workflow.Gates;
import sdlc.workflow.Verify;

/** `sdlc status`: where am I, what may I edit, what is left before the PR. */
public final class Status {
    private Status() {}

    static String text() {
        StringBuilder sb = new StringBuilder();
        String branch = Git.branch();
        sb.append("Branch: ").append(branch.isEmpty() ? "(detached)" : branch);
        if (Ctx.cfg.isProtectedBranch(branch)) sb.append(" (PROTECTED - create a feature branch before editing)");
        sb.append("\nStack: ").append(Ctx.cfg.stack());
        String story = Work.currentStory();
        if (story.isEmpty()) {
            sb.append("\nStory: none. Code edits are blocked until a task is started (java .agent/Sdlc.java task start <TASK>).");
            return sb.toString();
        }
        sb.append("\nStory: ").append(story);
        Work.Task t = Work.currentTask();
        if (t == null) sb.append("\nActive task: none (code edits blocked)");
        else sb.append("\nActive task: ").append(t.key()).append(" [").append(t.id()).append("] ").append(t.title()).append("\nScope: ").append(String.join(", ", t.scope()));
        Map<String, Object> state = Work.state(story);
        if (state.get("lld") instanceof Map<?, ?> lld) sb.append("\nLLD: v").append(Json.str(lld, "version")).append(" ").append(lld.get("url"));
        for (String line : Gates.summary(story)) sb.append("\n  ").append(line);
        return sb.toString();
    }

    public static int run() {
        System.out.println(text());
        System.out.println("Build verified for the current files: " + (Verify.current() ? "yes" : "no (java .agent/Sdlc.java verify)"));
        String story = Work.currentStory();
        if (!story.isEmpty()) {
            var problems = Gates.prProblems(story, null);
            System.out.println(problems.isEmpty() ? "PR gate: READY" : "PR gate: not ready\n  - " + String.join("\n  - ", problems));
        }
        return 0;
    }
}
