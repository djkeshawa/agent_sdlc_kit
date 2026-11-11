package sdlc.selftest;

import static sdlc.selftest.Asserts.check;
import static sdlc.selftest.Asserts.expect;
import static sdlc.selftest.Asserts.json;
import static sdlc.selftest.Asserts.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Markers;
import sdlc.core.Store;
import sdlc.guard.Decision;
import sdlc.guard.Gate;
import sdlc.guard.Guard;
import sdlc.guard.Repo;
import sdlc.util.Proc;
import sdlc.workflow.Approvals;
import sdlc.workflow.Wiki;
import sdlc.workflow.Workflow;

/** Self test: the workflow against an issue tracker and a wiki in memory: no network, no mock server. */
final class WorkflowTests {
    private WorkflowTests() {}

    static void run(Path tmp) throws IOException {
        Config saved = Ctx.cfg;
        Ctx.cfg = Config.parse("stack: java-maven\nproject:\n  team: pay\njira:\n  projectKeys: [PROJ]\nconfluence:\n  publish:\n    lld: {space: DEV}\n    testDesign: {space: QE}\n"
                + "gates:\n  approvers:\n    lld: [lead]\n  botAccounts: [bot]\n");
        try {
            Fakes.Tracker tracker = new Fakes.Tracker();
            Fakes.Wiki wiki = new Fakes.Wiki();
            Fakes.Recorder said = new Fakes.Recorder();
            Workflow w = new Workflow(tracker, wiki, said);
            Proc.run(tmp, "git", "switch", "-q", "-c", "feature/PROJ-7-refunds");
            Path draft = tmp.resolve(".sdlc/PROJ-7/drafts/lld.md");
            Files.createDirectories(draft.getParent());
            Files.writeString(draft, "# PROJ-7 - LLD - pay\n\n## Work breakdown\n\n```yaml\n# sdlc-tasks\ntasks:\n  - id: T1\n    title: Refund policy\n"
                    + "    scope: [src/main/java/com/acme/refund/**, src/test/java/com/acme/refund/**]\n    acceptance:\n      - Given day 31, then refused\n"
                    + "  - id: T2\n    title: Refund API\n    dependsOn: [T1]\n    scope: [src/main/java/com/acme/api/**]\n    acceptance:\n      - Given a paid order, then 201\n```\n");
            check("offline: LLD published to the wiki", w.docs.publish("PROJ-7", "lld", draft) == 0 && wiki.pages.size() == 1
                    && wiki.pages.values().iterator().next().title().equals("PROJ-7 - LLD - pay"));
            String page = wiki.pages.keySet().iterator().next();
            check("offline: publishing says where the page is", String.join("\n", said.out).contains("Published LLD v1: " + wiki.page(page).url()));
            check("offline: the tool asks for approval on the issue", tracker.comments.get("PROJ-7").get(0).body().contains(Approvals.BOT_MARK)
                    && tracker.comments.get("PROJ-7").get(0).body().contains(Markers.APPROVAL_WORD));
            check("offline: the tool's own comment is no approval", !w.approvals.check("PROJ-7", "lld").approved());
            check("offline: tasks refused before approval", w.tasks.create("PROJ-7") == 1 && tracker.created.isEmpty());
            check("offline: the refusal says what is missing", String.join("\n", said.err).contains("is NOT approved"));
            String line = Approvals.line("PROJ-7", "lld", wiki.page(page));
            tracker.say("PROJ-7", line, "dev", false);
            check("offline: a non-approver does not count", String.join(" ", w.approvals.check("PROJ-7", "lld").reasons()).contains("not in gates.approvers"));
            tracker.say("PROJ-7", line, "lead", true);
            check("offline: an edited comment does not count", String.join(" ", w.approvals.check("PROJ-7", "lld").reasons()).contains("edited"));
            tracker.say("PROJ-7", "{noformat}" + line + "{noformat}", "lead", false);
            check("offline: the tech lead's approval counts", w.approvals.check("PROJ-7", "lld").approved());
            wiki.edit(page, "<p>late edit</p>", "lead");
            check("offline: an edit voids the approval", !w.approvals.check("PROJ-7", "lld").approved());
            tracker.say("PROJ-7", Approvals.line("PROJ-7", "lld", wiki.page(page)), "lead", false);
            check("offline: four eyes, the editor cannot approve", String.join(" ", w.approvals.check("PROJ-7", "lld").reasons()).contains("cannot approve"));
            wiki.edit(page, "<p>edit by someone else</p>", "dev");
            tracker.say("PROJ-7", Approvals.line("PROJ-7", "lld", wiki.page(page)), "lead", false);
            check("offline: approval of someone else's version counts", w.approvals.check("PROJ-7", "lld").approved());
            check("offline: sub-tasks created from the approved LLD", w.tasks.create("PROJ-7") == 0 && tracker.created.size() == 2
                    && tracker.created.get(0).summary().equals("[T1] Refund policy"));
            check("offline: creating tasks again adds nothing", w.tasks.create("PROJ-7") == 0 && tracker.created.size() == 2);
            String t1 = tracker.created.get(0).key(), t2 = tracker.created.get(1).key();
            check("offline: a task waits for the one it depends on", w.tasks.start(t2) == 1);
            check("offline: task start moves the task and the story", w.tasks.start(t1) == 0
                    && "In Progress".equals(tracker.status.get(t1)) && "In Progress".equals(tracker.status.get("PROJ-7")));
            expect("offline: edit inside the started task's scope", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/src/main/java/com/acme/refund/Policy.java\"}"), Decision.Kind.ALLOW);
            expect("offline: edit in the other task's scope", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/src/main/java/com/acme/api/Api.java\"}"), Decision.Kind.DENY);
            wiki.edit(page, "<p>changed after the tasks were created</p>", "dev");
            check("offline: task start refused once the LLD changed", w.tasks.start(t1) == 1);
            Store.write(Ctx.work("current.json"), Store.map());

            Repo onMain = new Repo() { // the guard rules with git answered from memory
                public String branch() { return "main"; }
                public boolean inHead(String rel) { return !rel.equals("new.py"); }
                public boolean changedSinceHead(String rel) { return false; }
                public String alias(String name) { return name.equals("pf") ? "push --force" : ""; }
            };
            Gate closed = new Gate() { // and the workflow's gates too
                public Decision push() { return Decision.deny("closed"); }
                public Decision openPullRequest() { return Decision.deny("closed"); }
                public Decision anotherFixRound() { return Decision.allow(); }
                public Decision recordReview() { return Decision.allow(); }
            };
            Guard guard = new Guard(tmp, Ctx.cfg, null, onMain, () -> null, closed);
            check("fake repo: commit on a protected branch", guard.shell.check("git commit -m 'feat(PROJ-7): x'").denied());
            check("fake repo: alias expands to a force push", guard.shell.check("git pf origin x").denied());
            check("fake repo: committed script runs, new script asks", guard.shell.check("python3 tools/gen.py").kind() == Decision.Kind.ALLOW
                    && guard.shell.check("python3 new.py").kind() == Decision.Kind.ASK);
        } finally {
            Ctx.cfg = saved;
        }
    }
}
