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

        // a scenario that fails, and one that is not there
        write(repo, "tests/fix.t", "fixed: fail\n");
        String failing = verdict();
        check("mandate: a failing scenario is violated", failing.contains("violated  scenarios found, ran and passed: 0 of 1") && code == 1);
        write(repo, "tests/fix.t", "other: pass\n");
        String missing = verdict();
        check("mandate: a scenario that is not found is unknown, not passed", missing.contains("unknown   scenarios found, ran and passed: 0 of 1") && missing.contains("was not found among the tests that ran"));
        write(repo, "tests/fix.t", "fixed: needs fixed\n");

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
