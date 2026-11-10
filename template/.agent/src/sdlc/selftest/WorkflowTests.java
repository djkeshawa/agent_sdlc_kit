package sdlc.selftest;

import static sdlc.selftest.Asserts.check;
import static sdlc.selftest.Asserts.expect;
import static sdlc.selftest.Asserts.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.core.Config;
import sdlc.core.Console;
import sdlc.core.Ctx;
import sdlc.core.Markers;
import sdlc.core.Store;
import sdlc.guard.Decision;
import sdlc.guard.Gate;
import sdlc.guard.Guard;
import sdlc.guard.Repo;
import sdlc.util.Proc;
import sdlc.workflow.Approvals;
import sdlc.workflow.IssueTracker;
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
            FakeTracker tracker = new FakeTracker();
            FakeWiki wiki = new FakeWiki();
            Recorder said = new Recorder();
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
            expect("offline: edit inside the started task's scope", tool("create_file", "{\"filePath\":\"" + tmp + "/src/main/java/com/acme/refund/Policy.java\"}"), Decision.Kind.ALLOW);
            expect("offline: edit in the other task's scope", tool("create_file", "{\"filePath\":\"" + tmp + "/src/main/java/com/acme/api/Api.java\"}"), Decision.Kind.DENY);
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

    /** A console that keeps what the steps say, so a test can read it and the test run stays quiet. */
    private static final class Recorder implements Console {
        final List<String> out = new ArrayList<>(), err = new ArrayList<>();

        public void out(String line) { out.add(line); }

        public void err(String line) { err.add(line); }
    }

    /** An issue tracker in memory. */
    private static final class FakeTracker implements IssueTracker {
        final Map<String, List<Comment>> comments = new LinkedHashMap<>();
        final Map<String, String> status = new LinkedHashMap<>();
        final Map<String, String> parent = new LinkedHashMap<>();
        final List<Issue> created = new ArrayList<>();
        private int clock;

        void say(String key, String body, String who, boolean edited) {
            String at = "t" + (++clock);
            comments.computeIfAbsent(key, k -> new ArrayList<>()).add(new Comment(body, who, who, at, edited ? at + "-edited" : at));
        }

        public String text(String key, int maxComments) { return "# " + key; }
        public List<String> parents(String key) { return List.of(); }
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
            Issue i = new Issue("PROJ-" + (101 + created.size()), summary, "To Do", "Sub-task");
            created.add(i);
            parent.put(i.key(), p);
            return i.key();
        }
    }

    /** A wiki in memory. */
    private static final class FakeWiki implements Wiki {
        final Map<String, Page> pages = new LinkedHashMap<>();

        void edit(String id, String append, String author) {
            Page p = pages.get(id);
            pages.put(id, new Page(id, p.title(), p.space(), p.version() + 1, p.storage() + append, p.url(), author));
        }

        public Page page(String id) { return pages.get(id); }
        public void attach(String pageId, String fileName, byte[] data, String mime) { }
        public void ping() { }

        public List<Ref> titled(String words, List<String> spaces) {
            return pages.values().stream().filter(p -> p.title().contains(words) && (spaces.isEmpty() || spaces.contains(p.space()))).map(FakeWiki::ref).toList();
        }

        public Ref byTitle(String space, String title) {
            return pages.values().stream().filter(p -> p.space().equals(space) && p.title().equals(title)).map(FakeWiki::ref).findFirst().orElse(null);
        }

        public Page publish(String space, String parentId, String title, String storage) {
            Ref old = byTitle(space, title);
            String id = old == null ? String.valueOf(5000 + pages.size()) : old.id();
            Page p = new Page(id, title, space, old == null ? 1 : pages.get(id).version() + 1, storage, "https://wiki.test/" + id, "bot");
            pages.put(id, p);
            return p;
        }

        private static Ref ref(Page p) {
            return new Ref(p.id(), p.title(), p.space(), String.valueOf(p.version()), p.url());
        }
    }
}
