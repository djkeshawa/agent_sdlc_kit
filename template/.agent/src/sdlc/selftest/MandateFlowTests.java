package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.check.Check;
import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Trusted;
import sdlc.mandate.Mandate;
import sdlc.mandate.MandateCli;
import sdlc.mandate.SelfApproval;
import sdlc.util.Proc;

/**
 * Self test: approving a mandate and checking a change against it (sdlc mandate approve,
 * sdlc check --mandate), in a repository of its own with the stand-in test runner.
 */
final class MandateFlowTests {
    private MandateFlowTests() {}

    static void run() throws IOException {
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path repo = Files.createTempDirectory("sdlc-mandateflow").toRealPath();
        try {
            Trusted.env = name -> null;
            flow(repo);
        } finally {
            Trusted.env = System::getenv;
            Ctx.root = savedRoot;
            Ctx.cfg = savedCfg;
            try (Stream<Path> s = Files.walk(repo)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void flow(Path repo) throws IOException {
        write(repo, "prod.txt", "v1\n");
        write(repo, "tests/keep.t", "stays: pass\n");
        write(repo, "tests/other.t", "on: pass\ngoes: pass\n");
        write(repo, "tests/hurt.t", "guards: fails-with fixed\n");
        git(repo, "init", "-q", "-b", "main");
        commit(repo, "base");
        Ctx.init(repo);
        String keep = Proc.run(repo, "git", "hash-object", "--", "tests/keep.t").out().strip();
        String mandate = """
                {
                  "mandate": "v0",
                  "id": "fix",
                  "intent": "The fix is applied",
                  "mustStayTrue": [ { "is": "the old behaviour holds", "test": "tests/keep.t" } ],
                  "scope": { "paths": ["prod.txt", "tests/**"] },
                  "oracle": { "scenarios": ["tests/fix.t#fixed"], "locked": { "tests/keep.t": "%s" } }
                }
                """.formatted(keep);
        write(repo, "docs/mandates/fix.json", mandate);
        write(repo, "prod.txt", "v1\nfixed\n");
        write(repo, "tests/fix.t", "fixed: needs fixed\n");

        String first = verdict();
        check("mandate: a mandate nobody approved gives scope AMENDED, and the command does not fail", first.contains("Scope     AMENDED") && first.contains("not approved") && code == 0);
        check("mandate: the mandate's own file is not counted against the scope", !first.contains("docs/mandates/fix.json"));
        check("mandate: locked tests and build files are read from git", first.contains("satisfied locked tests are the files the mandate names (1)   [read from git]")
                && first.contains("satisfied build files and test configuration are unchanged   [read from git]"));
        check("mandate: scenarios, what must stay true and the change's own tests are results of the change's test run",
                first.contains("satisfied scenarios found, ran and passed: 1 of 1   [from the change's own test run]")
                && first.contains("satisfied what must stay true passes before and after: 1 of 1")
                && first.contains("1 fails before it and passes after it (0 approved with the mandate, 1 written with the change)"));
        check("mandate: every verdict says what it does not say", first.contains("that the change is correct") && first.contains("approved before the work"));

        // approval: the sentence first, then the card
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        // a draft swapped in while the person is deciding is not approved
        String draftText = Files.readString(repo.resolve("docs/mandates/fix.json"));
        Deque<String> slow = new ArrayDeque<>(List.of("it breaks", "1", "fix"));
        ByteArrayOutputStream swapped = new ByteArrayOutputStream();
        int raced = MandateCli.approve(repo, "fix", q -> {
            try {
                if (slow.size() == 1) Files.writeString(repo.resolve("docs/mandates/fix.json"), draftText.replace("\"tests/**\"", "\"tests/**\", \"**\""));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return slow.poll();
        }, new PrintStream(swapped, true, StandardCharsets.UTF_8));
        check("mandate: a mandate that changes while the person decides is not approved",
                raced == 1 && swapped.toString(StandardCharsets.UTF_8).contains("changed while you were deciding") && SelfApproval.of(repo, "fix") == null);
        Files.writeString(repo.resolve("docs/mandates/fix.json"), draftText);

        Deque<String> answers = new ArrayDeque<>(List.of("the fix is applied twice", "1", "fix"));
        StringBuilder order = new StringBuilder();
        boolean[] blind = {true};
        int approved = MandateCli.approve(repo, "fix", q -> {
            String shown = said.toString(StandardCharsets.UTF_8);
            if (order.length() == 0) blind[0] = !shown.contains("The fix is applied") && !shown.contains("May change");
            order.append(shown.contains("May change") ? "card;" : "blind;");
            return answers.poll();
        }, new PrintStream(said, true, StandardCharsets.UTF_8));
        check("mandate: the approver is asked how it fails before the card is shown", approved == 0 && order.toString().startsWith("blind;card;"));
        check("mandate: and before anything of the draft, its intent included", blind[0]);
        check("mandate: the approver's sentence is written into the mandate", Mandate.load(repo, "fix").failsIf().equals("the fix is applied twice"));
        SelfApproval.Record rec = SelfApproval.of(repo, "fix");
        check("mandate: the approval names the blob that was approved and the test that covers the sentence",
                rec != null && rec.blob().equals(Mandate.blobId(repo, "fix")) && rec.covers().equals("tests/fix.t#fixed"));
        check("mandate: the approval is kept outside the working tree", Proc.run(repo, "git", "status", "--porcelain").out().lines().noneMatch(l -> l.contains("approval")));
        String inside = verdict();
        check("mandate: an approved mandate and a change inside it give scope INSIDE", inside.contains("Scope     INSIDE") && inside.contains("their own approval, recorded on this machine") && code == 0);

        // outside the paths
        write(repo, "elsewhere.txt", "x\n");
        String outside = verdict();
        check("mandate: a file outside the paths gives scope OUTSIDE and fails", outside.contains("Scope     OUTSIDE") && outside.contains("            elsewhere.txt") && code == 1);
        Files.delete(repo.resolve("elsewhere.txt"));

        // the mandate edited after approval
        String approvedText = Files.readString(repo.resolve("docs/mandates/fix.json"));
        write(repo, "docs/mandates/fix.json", approvedText.replace("The fix is applied", "The fix is applied, and more"));
        String edited = verdict();
        check("mandate: a mandate edited after approval is AMENDED", edited.contains("Scope     AMENDED") && edited.contains("edited since it was approved"));
        write(repo, "docs/mandates/fix.json", approvedText);

        // a locked test changed
        write(repo, "tests/keep.t", "stays: pass\nextra: pass\n");
        String locked = verdict();
        check("mandate: a changed locked test violates the first obligation", locked.contains("violated  locked tests are the files the mandate names") && locked.contains("tests/keep.t is not the file the mandate names") && code == 1);
        write(repo, "tests/keep.t", "stays: pass\n");

        // the build changed: no test result counts
        write(repo, "tests/package.json", "{}\n");
        String build = verdict();
        check("mandate: a changed build file violates the second obligation and makes the test results unknown",
                build.contains("violated  build files and test configuration are unchanged") && build.contains("tests/package.json")
                && build.contains("unknown   scenarios found, ran and passed") && build.contains("the build changed, so no test result of this run can be relied on") && code == 1);
        Files.delete(repo.resolve("tests/package.json"));
        write(repo, "web/vite.config.ts", "export default {}\n");
        check("mandate: the configuration vitest reads by default is a build file too", verdict().contains("violated  build files and test configuration are unchanged"));
        Files.delete(repo.resolve("web/vite.config.ts"));
        Files.delete(repo.resolve("web"));

        // a scenario that fails, and one that is not there
        write(repo, "tests/fix.t", "fixed: fail\n");
        String failing = verdict();
        check("mandate: a failing scenario is violated", failing.contains("violated  scenarios found, ran and passed: 0 of 1") && code == 1);
        // two tests whose names end alike are two tests, wherever a bracket stands in their names
        write(repo, "tests/fix.t", "one(a) > fixed: needs fixed\none(b) > fixed: needs fixed\n");
        String twoHomes = verdict();
        check("mandate: a scenario name that fits two tests of one file names neither", twoHomes.contains("unknown   scenarios found, ran and passed: 0 of 1") && twoHomes.contains("more than one test has that name"));
        write(repo, "tests/fix.t", "fixed: needs fixed\nfixed: pass\n");
        String twins = verdict();
        check("mandate: nor does a name that two tests report alike", twins.contains("unknown   scenarios found, ran and passed: 0 of 1"));
        write(repo, "tests/fix.t", "other: pass\n");
        String missing = verdict();
        check("mandate: a scenario that is not found is unknown, not passed", missing.contains("unknown   scenarios found, ran and passed: 0 of 1") && missing.contains("was not found among the tests that ran"));
        write(repo, "tests/fix.t", "fixed: needs fixed\n");

        // a test that ran before is switched off, another is gone: the witness is violated
        write(repo, "tests/other.t", "on: skip\n");
        String weakened = verdict();
        check("mandate: a test that ran before the change and is skipped now violates the witness",
                weakened.contains("violated  the change's own tests: 1 fails before it and passes after it") && weakened.contains("tests/other.t  on: ran before the change and is skipped now") && code == 1);
        check("mandate: and so does a test that is gone", weakened.contains("tests/other.t  goes: the file had it before the change, and it is gone"));
        // one test gone and one new: a rename and a test deleted and another written look alike in the reports
        write(repo, "tests/other.t", "on: pass\nreturns: pass\n");
        String renamed = verdict();
        check("mandate: a test renamed leaves the witness unknown, not violated", renamed.contains("unknown   the change's own tests: 1 fails before it and passes after it")
                && renamed.contains("2 of the change's tests could not be judged") && !renamed.contains("it is gone") && code == 0);
        write(repo, "tests/other.t", "arrives: pass\non: pass\n");
        String replaced = verdict();
        check("mandate: and so does a test deleted and another written in its stead", replaced.contains("unknown   the change's own tests: 1 fails before it and passes after it")
                && replaced.contains("2 of the change's tests could not be judged") && !replaced.contains("does not test the change") && code == 0);
        write(repo, "tests/other.t", "on: pass\ngoes: pass\n");
        // but an old test that passed before, fails as it was on the change, and is gone: violated
        write(repo, "tests/hurt.t", "guarded: pass\n");
        String hurt = verdict();
        check("mandate: an old test that the change breaks and takes out of the way violates the witness", hurt.contains("violated  the change's own tests")
                && hurt.contains("tests/hurt.t  guards: passed before the change; as the test was, on the code after the change guards fails: expected something else") && code == 1);
        write(repo, "tests/hurt.t", "guards: fails-with fixed\n");

        // what must stay true breaks
        write(repo, "prod.txt", "v1\nfixed\nbroken\n");
        Files.writeString(repo.resolve("docs/mandates/fix.json"), approvedText);
        String noRun = report(Map.of("mandate", "fix", "no-run", "true"));
        check("mandate: --no-run reads git only and marks the rest not run", noRun.contains("not run   scenarios found, ran and passed") && noRun.contains("satisfied locked tests"));

        // approving with no covering test, and cancelling
        Deque<String> none = new ArrayDeque<>(List.of("it breaks search", "0", "fix"));
        check("mandate: approving with no covering test is allowed", MandateCli.approve(repo, "fix", q -> none.poll(), new PrintStream(new ByteArrayOutputStream())) == 0);
        check("mandate: and every verdict then says the sentence is unresolved", verdict().contains("unresolved \"this fails if it breaks search\": the approver named no test that covers it"));
        Deque<String> blank = new ArrayDeque<>(List.of(""));
        check("mandate: an empty sentence approves nothing", MandateCli.approve(repo, "fix", q -> blank.poll(), new PrintStream(new ByteArrayOutputStream())) == 1);
        Deque<String> wrongId = new ArrayDeque<>(List.of("it breaks", "1", "yes"));
        String before = Files.readString(repo.resolve("docs/mandates/fix.json"));
        check("mandate: anything but the id cancels, and the file is left as it was",
                MandateCli.approve(repo, "fix", q -> wrongId.poll(), new PrintStream(new ByteArrayOutputStream())) == 1 && Files.readString(repo.resolve("docs/mandates/fix.json")).equals(before));
        check("mandate: an id that is not a mandate is refused", Asserts.throwsUsage(() -> Check.run(Map.of("mandate", "../x"))));

        // a change to production code only: "before" must be the real base, not the change laid over it
        write(repo, "tests/reg.t", "holds: needs more\n");
        commit(repo, "everything so far, and a test that the base does not pass");
        String reg = Proc.run(repo, "git", "hash-object", "--", "tests/reg.t").out().strip();
        write(repo, "docs/mandates/prodonly.json", """
                {
                  "mandate": "v0",
                  "id": "prodonly",
                  "intent": "More is added",
                  "mustStayTrue": [ { "is": "this held before", "test": "tests/reg.t" } ],
                  "scope": { "paths": ["prod.txt"] },
                  "oracle": { "scenarios": ["tests/reg.t"], "locked": { "tests/reg.t": "%s" } }
                }
                """.formatted(reg));
        write(repo, "prod.txt", Files.readString(repo.resolve("prod.txt")) + "more\n");
        String prodOnly = report(Map.of("mandate", "prodonly", "selftest-runner", "true", "runs", "1"));
        check("mandate: with only production code changed, a test that fails on the base is seen to fail before the change",
                prodOnly.contains("violated  what must stay true passes before and after: 0 of 1") && prodOnly.contains("fails before the change"));

        // a mandate that is a link to a file elsewhere
        Path outsideFile = Files.createTempFile("sdlc-elsewhere", ".json");
        try {
            Files.writeString(outsideFile, Files.readString(repo.resolve("docs/mandates/prodonly.json")).replace("prodonly", "linked"));
            Files.createSymbolicLink(repo.resolve("docs/mandates/linked.json"), outsideFile);
            check("mandate: a mandate that is a link is refused", Asserts.throwsUsage(() -> Mandate.load(repo, "linked")));
        } catch (IOException | UnsupportedOperationException e) {
            // no links on this file system: nothing to refuse
        } finally {
            Files.deleteIfExists(repo.resolve("docs/mandates/linked.json"));
            Files.deleteIfExists(outsideFile);
        }
        // approval writes the file out again, with the sentence: it must still be a file the tool will read
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 22_000; i++) paths.append(i == 0 ? "" : ",").append("\"p/").append(i).append('"');
        write(repo, "docs/mandates/sizey.json", "{\"mandate\":\"v0\",\"id\":\"sizey\",\"intent\":\"Many paths\",\"scope\":{\"paths\":[" + paths + "]},\"oracle\":{\"scenarios\":[\"tests/fix.t#fixed\"]}}");
        String compact = Files.readString(repo.resolve("docs/mandates/sizey.json"));
        Deque<String> sizey = new ArrayDeque<>(List.of("it breaks", "0", "sizey"));
        ByteArrayOutputStream tooLarge = new ByteArrayOutputStream();
        check("mandate: a mandate that fits as written is read", Mandate.load(repo, "sizey").scopePaths().size() == 22_000);
        check("mandate: approval that would write a mandate too large to read approves nothing and leaves the file",
                MandateCli.approve(repo, "sizey", q -> sizey.poll(), new PrintStream(tooLarge, true, StandardCharsets.UTF_8)) == 1
                && tooLarge.toString(StandardCharsets.UTF_8).contains("would be larger than") && Files.readString(repo.resolve("docs/mandates/sizey.json")).equals(compact)
                && SelfApproval.of(repo, "sizey") == null);
        Files.delete(repo.resolve("docs/mandates/sizey.json"));

        // a fixture file that the change turns into a folder of fixtures
        write(repo, "tests/fx.t", "old shape: pass\n");
        commit(repo, "a fixture as one file");
        Files.delete(repo.resolve("tests/fx.t"));
        write(repo, "tests/fx.t/case.t", "new shape: pass\n");
        String reshaped = report(Map.of("mandate", "prodonly", "selftest-runner", "true", "runs", "1"));
        check("mandate: a fixture file turned into a folder does not stop the check", reshaped.contains("Evidence") && reshaped.contains("what must stay true"));
        Files.delete(repo.resolve("tests/fx.t/case.t"));
        Files.delete(repo.resolve("tests/fx.t"));
        write(repo, "tests/fx.t", "old shape: pass\n");

        // a link in the base that the change turns into a folder: what the link leads to is never touched
        Path elsewhere = Files.createTempDirectory("sdlc-elsewhere").toRealPath();
        Path sentinel = elsewhere.resolve("sub");
        try {
            Files.writeString(sentinel, "not ours\n");
            Files.createSymbolicLink(repo.resolve("tests/linked"), elsewhere);
            commit(repo, "a fixture folder that is a link");
            Files.delete(repo.resolve("tests/linked"));
            write(repo, "tests/linked/sub/case.t", "inside: pass\n");
            String overLink = report(Map.of("mandate", "prodonly", "selftest-runner", "true", "runs", "1"));
            check("mandate: preparing the checkout never removes a file behind a link", Files.isRegularFile(sentinel) && overLink.contains("Evidence"));
            Files.delete(repo.resolve("tests/linked/sub/case.t"));
            Files.delete(repo.resolve("tests/linked/sub"));
            Files.delete(repo.resolve("tests/linked"));
            Files.createSymbolicLink(repo.resolve("tests/linked"), elsewhere);
        } catch (IOException | UnsupportedOperationException e) {
            // no links on this file system: nothing to protect
        } finally {
            Files.deleteIfExists(sentinel);
            Files.deleteIfExists(elsewhere);
        }

        // text of the repository that is printed without being a valid mandate
        write(repo, "docs/mandates/odd.json", "{\"mandate\": \"v0\", \"id\": \"odd\", \"intent\": \"All is \u202ellew\u001b[2J\"}");
        PrintStream console = System.out;
        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(listed, true, StandardCharsets.UTF_8));
        try {
            MandateCli.run(new String[] {"mandate", "list"}, Map.of());
            MandateCli.run(new String[] {"mandate", "check", "odd"}, Map.of());
        } finally {
            System.setOut(console);
        }
        String shownList = listed.toString(StandardCharsets.UTF_8);
        check("mandate: the list and the diagnostics never print a direction mark or an escape of a file that is not valid",
                shownList.contains("odd") && shownList.contains("not valid") && shownList.indexOf('\u202e') < 0 && shownList.indexOf('\u001b') < 0);
        Files.delete(repo.resolve("docs/mandates/odd.json"));

        // a linked worktree keeps its git folder outside the checkout: the approval record and the hooks there are guarded all the same
        Path linked = Files.createTempDirectory("sdlc-linked").toRealPath();
        Files.delete(linked);
        git(repo, "worktree", "add", "-q", "--detach", linked.toString());
        try {
            Ctx.init(linked);
            Path store = linked.resolve(Proc.run(linked, "git", "rev-parse", "--git-path", "sdlc/mandate-approvals.json").out().strip()).normalize();
            Path hook = linked.resolve(Proc.run(linked, "git", "rev-parse", "--git-path", "hooks/pre-commit").out().strip()).normalize();
            check("guard: in a linked worktree the approval record lies outside the checkout", !store.startsWith(linked) && !hook.startsWith(linked));
            check("guard: a shell write to the approval record of a linked worktree is refused", Asserts.shell("echo '{}' > " + store).denied());
            check("guard: also by a relative path", Asserts.shell("cp forged.json " + linked.relativize(store).toString().replace('\\', '/')).denied());
            check("guard: and a write to the repository's git hooks from a linked worktree", Asserts.shell("echo 'exit 0' > " + hook).denied());
            check("guard: and to the worktree's own git configuration", Asserts.shell("echo '[core] hooksPath=/tmp/none' > " + store.getParent().getParent().resolve("config.worktree")).denied());
            check("guard: and to the files that say where the git folder is", Asserts.shell("echo 'gitdir: /tmp/other' > .git").denied()
                    && Asserts.shell("echo /tmp/other > " + store.getParent().getParent().resolve("commondir")).denied());
            Path shared = hook.getParent().getParent();
            check("guard: a copy of a tree onto the git folder itself is refused", Asserts.shell("cp -R prepared/. " + shared).denied() && Asserts.shell("mv " + shared + " " + shared + "-old").denied());
            check("guard: and onto a folder that holds it", Asserts.shell("cp -R prepared/. " + shared.getParent()).denied());
            check("guard: other files outside the checkout are judged as before", !Asserts.shell("echo x > " + linked.getParent().resolve("sdlc-elsewhere-note.txt")).denied());
        } finally {
            Ctx.init(repo);
            git(repo, "worktree", "remove", "--force", linked.toString());
        }

        write(repo, "docs/mandates/huge.json", "{\"mandate\": \"v0\", \"id\": \"huge\", \"intent\": \"" + "x".repeat(300_000) + "\"}");
        check("mandate: a mandate larger than a page is refused before it is parsed", Asserts.throwsUsage(() -> Mandate.load(repo, "huge")));
    }

    private static int code;

    private static String verdict() {
        return report(Map.of("mandate", "fix", "selftest-runner", "true", "runs", "1"));
    }

    private static String report(Map<String, String> options) {
        PrintStream out = System.out;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            code = Check.run(options);
        } finally {
            System.setOut(out);
        }
        return sink.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path repo, String rel, String text) throws IOException {
        Path f = repo.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static void commit(Path repo, String subject) {
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", subject);
    }

    private static void git(Path repo, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Proc p = Proc.run(repo, cmd);
        if (!p.ok()) throw new IllegalStateException("git " + String.join(" ", args) + ": " + p.err());
    }
}
