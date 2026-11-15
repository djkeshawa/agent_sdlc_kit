package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Locks;
import sdlc.core.Trusted;
import sdlc.util.Proc;
import sdlc.workflow.Checks;

final class TrustTests {
    private TrustTests() {}

    private static final String STRICT = "stack: java-maven\njira:\n  projectKeys: [PROJ]\ndelivery:\n  baseBranch: main\n";
    private static final String WEAK = STRICT + "git:\n  branchPattern: \".*\"\n  commitPattern: \".*\"\ntests:\n  requireTestsForMainChanges: false\n";

    static void run() throws IOException {
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path repo = Files.createTempDirectory("sdlc-trust").toRealPath();
        try {
            eventBase();
            repository(repo);
            rulesFromTheBase(repo);
            locksAddedOnTheBase(repo);
            blinding(repo);
            declaredStory(repo);
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

    private static void env(Map<String, String> values) {
        Trusted.env = values::get;
    }

    private static void eventBase() {
        env(Map.of());
        check("trust: outside a pull request build no base branch is named", Trusted.eventBase().isEmpty() && !Trusted.inCi());
        env(Map.of("GITHUB_BASE_REF", "main"));
        check("trust: GitHub Actions names the base branch", Trusted.eventBase().equals("main") && Trusted.inCi());
        env(Map.of("CHANGE_TARGET", "release/1.2"));
        check("trust: Jenkins names the base branch", Trusted.eventBase().equals("release/1.2"));
        env(Map.of("SDLC_BASE_BRANCH", "develop", "GITHUB_BASE_REF", "main"));
        check("trust: the explicit variable comes first", Trusted.eventBase().equals("develop"));
        for (String bad : List.of("--upload-pack=x", "a..b", "main ; rm", "main:path", "heads/", "x.lock")) {
            env(Map.of("GITHUB_BASE_REF", bad));
            check("trust: '" + bad + "' is refused as a base branch", Asserts.throwsUsage(Trusted::eventBase));
        }
        for (String good : List.of("main", "release/1.2+hotfix", "r\u00e9lease/1", "feature/a.b-c_d")) {
            env(Map.of("GITHUB_BASE_REF", good));
            check("trust: '" + good + "' is accepted as a base branch", Trusted.eventBase().equals(good));
        }
        env(Map.of("GITHUB_BASE_REF", "main"));
        check("trust: the base is a full remote ref, so a local branch named origin/main cannot stand in for it", Trusted.baseRef().equals("refs/remotes/origin/main"));
        env(Map.of());
    }

    private static void repository(Path repo) throws IOException {
        write(repo, "src/main/java/A.java", "class A {}\n");
        write(repo, "src/test/java/ATest.java", "class ATest { /* TS-01 */ }\n");
        git(repo, "init", "-q", "-b", "main");
        commit(repo, "chore(PROJ-1): code without the kit");
        git(repo, "update-ref", "refs/remotes/origin/bare", "HEAD");
        write(repo, ".agent/sdlc.yml", STRICT);
        commit(repo, "chore(PROJ-1): add the kit");
        git(repo, "update-ref", "refs/remotes/origin/main", "HEAD");
    }

    private static void rulesFromTheBase(Path repo) throws IOException {
        git(repo, "switch", "-q", "-c", "hack");
        write(repo, ".agent/sdlc.yml", WEAK);
        write(repo, "src/main/java/A.java", "class A { int x; }\n");
        commit(repo, "wip");
        Ctx.init(repo);
        Config own = Ctx.cfg;
        check("trust: the checkout's rules are the weakened ones", own.str("git.branchPattern").equals(".*"));
        check("trust: the base branch's rules are read from git, not from the checkout",
                !Trusted.config("origin/main").str("git.branchPattern").equals(".*") && Trusted.config("origin/bare") == null);

        env(Map.of());
        Checks.Result local = Checks.prPolicy(null, false);
        check("trust: on a developer's machine the checkout's rules apply", local.fails().isEmpty());

        env(Map.of("GITHUB_BASE_REF", "main"));
        Checks.Result ci = Checks.prPolicy(null, false);
        check("trust: in a pull request build the rules come from the base branch", ci.passes().stream().anyMatch(p -> p.contains("rules are read from origin/main")));
        check("trust: a pull request cannot relax the branch rule it is judged by", ci.fails().stream().anyMatch(f -> f.contains("naming rule")));
        check("trust: nor the commit rule", ci.fails().stream().anyMatch(f -> f.contains("commit subjects")));
        check("trust: nor the tests-with-code rule", ci.fails().stream().anyMatch(f -> f.contains("Main code changes come with test changes")));
        check("trust: the checkout's configuration is back after the check", Ctx.cfg == own);

        env(Map.of("CHANGE_TARGET", "bare"));
        Checks.Result first = Checks.prPolicy(null, false);
        check("trust: a base branch without rules fails: the pull request's own rules prove nothing",
                first.fails().stream().anyMatch(f -> f.contains("origin/bare has no .agent/sdlc.yml")) && first.passes().isEmpty() && Ctx.cfg == own);
        env(Map.of("CHANGE_TARGET", "bare", "SDLC_FIRST_INSTALLATION", "true"));
        Checks.Result install = Checks.prPolicy(null, false);
        check("trust: only the pipeline can say that a pull request is the first installation",
                install.fails().stream().noneMatch(f -> f.contains("has no .agent/sdlc.yml")) && install.warns().stream().anyMatch(w -> w.contains("first installation")));
        env(Map.of("CHANGE_TARGET", "bare", "SDLC_FIRST_INSTALLATION", "no"));
        check("trust: and only with a plain yes", Checks.prPolicy(null, false).fails().stream().anyMatch(f -> f.contains("has no .agent/sdlc.yml")));

        env(Map.of("GITHUB_BASE_REF", "main"));
        git(repo, "update-ref", "refs/remotes/origin/broken", "HEAD");
        write(repo, ".agent/sdlc.yml", "jira: [not, a, map\n");
        commit(repo, "wip: broken rules");
        git(repo, "update-ref", "refs/remotes/origin/broken", "HEAD");
        env(Map.of("GITHUB_BASE_REF", "broken"));
        Checks.Result broken = Checks.prPolicy(null, false);
        check("trust: rules that cannot be read on the base branch fail the check", broken.fails().stream().anyMatch(f -> f.contains("cannot be read")) && Ctx.cfg == own);
        env(Map.of());
    }

    private static void locksAddedOnTheBase(Path repo) throws IOException {
        String test = "src/test/java/ATest.java";
        git(repo, "switch", "-q", "-c", "feature/PROJ-7-touches-the-test", "origin/main");
        write(repo, test, "class ATest { /* TS-01, weakened */ }\n");
        commit(repo, "fix(PROJ-7): change the acceptance test");
        git(repo, "switch", "-q", "-c", "feature/PROJ-8-leaves-it", "origin/main");
        write(repo, "src/main/java/A.java", "class A { int y; }\n");
        write(repo, "src/test/java/BTest.java", "class BTest {}\n");
        commit(repo, "feat(PROJ-8): another change");
        git(repo, "switch", "-q", "-c", "qe", "origin/main");
        String hash = Locks.hash(Files.readAllBytes(repo.resolve(test)));
        write(repo, Locks.DIR + "/PROJ-9.json", "{\"story\": \"PROJ-9\", \"files\": {\"" + test + "\": \"" + hash + "\"}}\n");
        commit(repo, "test(PROJ-9): lock the acceptance test");
        git(repo, "update-ref", "refs/remotes/origin/main", "HEAD");

        env(Map.of("GITHUB_BASE_REF", "main"));
        git(repo, "switch", "-q", "feature/PROJ-7-touches-the-test");
        Ctx.init(repo);
        Checks.Result touched = Checks.prPolicy(null, false);
        check("trust: a test locked on the base after the branch was made cannot be changed",
                touched.fails().stream().anyMatch(f -> f.contains("has locked since the branch was made") && f.contains(test + " (PROJ-9)")));
        git(repo, "switch", "-q", "feature/PROJ-8-leaves-it");
        Ctx.init(repo);
        Checks.Result untouched = Checks.prPolicy(null, false);
        check("trust: a branch that is only behind the base is not blamed for the new lock", untouched.fails().isEmpty());
        git(repo, "switch", "-q", "-c", "chore/PROJ-9-relock", "feature/PROJ-7-touches-the-test");
        String relocked = Locks.hash(Files.readAllBytes(repo.resolve(test)));
        write(repo, Locks.DIR + "/PROJ-9.json", "{\"story\": \"PROJ-9\", \"files\": {\"" + test + "\": \"" + relocked + "\"}}\n");
        commit(repo, "test(PROJ-9): lock the changed test");
        Ctx.init(repo);
        Checks.Result qe = Checks.prPolicy(null, false);
        check("trust: QE's lock-only pull request that locks the test again is a warning, not a failure",
                qe.fails().stream().noneMatch(f -> f.contains("has locked since")) && qe.warns().stream().anyMatch(w -> w.contains("locked again in a lock-only pull request")));
        write(repo, "src/main/java/A.java", "class A { int z; }\n");
        commit(repo, "feat(PROJ-9): and some code");
        Checks.Result mixed = Checks.prPolicy(null, false);
        check("trust: the same beside production code fails", mixed.fails().stream().anyMatch(f -> f.contains("has locked since the branch was made")));
        git(repo, "switch", "-q", "-c", "chore/PROJ-9-unlock", "origin/main");
        Files.delete(repo.resolve(Locks.DIR + "/PROJ-9.json"));
        Files.delete(repo.resolve(test));
        commit(repo, "test(PROJ-9): drop the lock and the test");
        Ctx.init(repo);
        Checks.Result unlock = Checks.prPolicy(null, false);
        check("trust: a lock-only pull request cannot remove a lock and delete its test",
                unlock.fails().stream().anyMatch(f -> f.contains("is not removed by a pull request") && f.contains(test + " (PROJ-9): lock removed")));
        env(Map.of("GITHUB_BASE_REF", "main", "SDLC_LOCKS_REMOVABLE", "PROJ-8, PROJ-9"));
        Checks.Result retired = Checks.prPolicy(null, false);
        check("trust: unless the pipeline names the story whose locks may go",
                retired.fails().stream().noneMatch(f -> f.contains("is not removed")) && retired.warns().stream().anyMatch(w -> w.contains("locks removed, as the pipeline allows")));
        env(Map.of("GITHUB_BASE_REF", "main", "SDLC_LOCKS_REMOVABLE", "PROJ-8"));
        check("trust: another story's permission does not cover it", Checks.prPolicy(null, false).fails().stream().anyMatch(f -> f.contains("is not removed by a pull request")));
        env(Map.of());
    }

    private static void blinding(Path repo) throws IOException {
        env(Map.of("GITHUB_BASE_REF", "main"));
        git(repo, "switch", "-q", "-c", "feature/PROJ-11-state", "origin/main");
        write(repo, ".sdlc/current.json", "{\"story\": \"PROJ-99\", \"task\": \"PROJ-100\"}\n");
        write(repo, "src/main/java/A.java", "class A { int s; }\n");
        write(repo, "src/test/java/STest.java", "class STest {}\n");
        commit(repo, "feat(PROJ-11): with local state");
        Ctx.init(repo);
        Checks.Result state = Checks.prPolicy(null, true);
        check("trust: local state committed into a pull request fails the check", state.fails().stream().anyMatch(f -> f.contains("No local state (.sdlc/) is committed")));
        check("trust: and the story it names is not used", state.passes().stream().noneMatch(p -> p.contains("PROJ-99")) && state.fails().stream().noneMatch(f -> f.contains("PROJ-99")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-12-binary", "origin/main");
        write(repo, ".gitattributes", "*.java -diff\n");
        write(repo, "src/test/java/DTest.java", "import org.junit.jupiter.api.Disabled;\nclass DTest { @Disabled void off() {} }\n");
        write(repo, "src/main/java/A.java", "class A { int b; }\n");
        commit(repo, "feat(PROJ-12): quietly switch a test off");
        Ctx.init(repo);
        Checks.Result binary = Checks.prPolicy(null, false);
        check("trust: lines of a file the pull request marks as binary are still scanned", binary.fails().stream().anyMatch(f -> f.contains("No switch that turns a test off or focuses one was found")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-15-spread", "origin/main");
        write(repo, "src/test/java/WebTest.java", "class WebTest {\n  @\n  Disabled\n  @Test void later() {}\n}\n");
        write(repo, "src/main/java/A.java", "class A { int c; }\n");
        commit(repo, "feat(PROJ-15): switch a test off over two lines");
        Ctx.init(repo);
        check("trust: a test switched off by a call written over two lines is still found",
                Checks.prPolicy(null, false).fails().stream().anyMatch(f -> f.contains("No switch that turns a test off or focuses one was found") && f.contains("WebTest.java")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-17-script", "origin/main");
        write(repo, "src/test/java/web.test.ts", "it.skip('later', () => {})\nit\n  .skip('sooner', () => {})\nconst r = /'/; it.skip('a', () => { expect(false).toBe(true); }); const x = 'ok';\n");
        write(repo, "src/main/java/A.java", "class A { int e; }\n");
        commit(repo, "feat(PROJ-17): switch script tests off");
        Ctx.init(repo);
        check("trust: a script test switched off on an added line fails, and so does one written over two lines",
                Checks.prPolicy(null, false).fails().stream().anyMatch(f -> f.contains("No switch that turns a test off or focuses one was found") && f.contains("web.test.ts: it.skip('later'") && f.contains("web.test.ts: it .skip('sooner'")
                        && f.contains("web.test.ts: const r = /'/; it.skip('a'")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-19-deep", "origin/main");
        write(repo, "src/test/java/DeepTest.java", "class DeepTest {\n  @" + "a.".repeat(10000) + "Disabled\n  @Test void t() {}\n}\n");
        write(repo, "src/main/java/A.java", "class A { int g; }\n");
        commit(repo, "feat(PROJ-19): a very long annotation line");
        Ctx.init(repo);
        Checks.Result deep = Checks.prPolicy(null, false);
        check("trust: a very long line that would overflow a configured pattern is not read, and check-pr says so instead of crashing",
                deep.warns().stream().anyMatch(w -> w.contains("DeepTest.java") && w.contains("were not read for switches")));
        check("trust: a configured pattern that overflows on a text is taken as a hit, not as clean",
                Checks.containsDisabled("@" + "a.".repeat(200000) + "Disabled"));

        git(repo, "switch", "-q", "-c", "feature/PROJ-18-java", "origin/main");
        write(repo, "src/test/java/OffTest.java", "class OffTest {\n  @Disabled(\"later\")\n  @Test void later() {}\n  @Test void sooner() {\n    assumeTrue(false);\n  }\n  @Test void never() { Assumptions.<Void>abort(\"later\"); }\n}\n");
        write(repo, "src/main/java/A.java", "class A { int f; }\n");
        commit(repo, "feat(PROJ-18): switch Java tests off");
        Ctx.init(repo);
        Checks.Result offJava = Checks.prPolicy(null, false);
        check("trust: a Java test switched off on an added line fails, by the line",
                offJava.fails().stream().anyMatch(f -> f.contains("No switch that turns a test off or focuses one was found") && f.contains("OffTest.java: @Disabled(\"later\")")
                        && !f.contains("assumeTrue(false)") && !f.contains("Assumptions.<Void>abort(")));
        check("trust: Assumptions.abort, which a condition on another line may hold, and an assumption on a line of its own in a test method are warnings, not failures",
                offJava.warns().stream().anyMatch(w -> w.contains("OffTest.java: may switch a test off") && w.contains("Assumptions.<Void>abort(\"later\")")
                        && w.contains("assumeTrue(false);")));
        check("trust: the commit hook's check, the configured patterns on the added lines, still finds assumeTrue(false); with the Java defaults",
                Checks.disabledTests(Map.of("src/test/java/OffTest.java", List.of("    assumeTrue(false);"))).equals(List.of("src/test/java/OffTest.java: assumeTrue(false);")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-16-example", "origin/main");
        write(repo, "src/test/java/DocsTest.java", "class DocsTest {\n  String example = \"\"\"\n      @Disabled\n      \"\"\";\n  @Test void real() { assertNotNull(example); }\n}\n");
        write(repo, "src/main/java/A.java", "class A { int d; }\n");
        commit(repo, "feat(PROJ-16): a test about source text");
        Ctx.init(repo);
        Checks.Result example = Checks.prPolicy(null, false);
        check("trust: a skip inside a text block of a test file fails the pull request, by its line: the lint does not know a text over several lines",
                example.fails().stream().anyMatch(f -> f.contains("No switch that turns a test off or focuses one was found") && f.contains("DocsTest.java: @Disabled")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-19-conditional", "origin/main");
        write(repo, "src/test/java/browser.spec.ts", "test.skip(browserName === 'webkit', 'not on Safari');\ntest('x', async () => {})\nconst data = '" + "x".repeat(3000) + "';\n");
        write(repo, "src/main/java/A.java", "class A { int g; }\n");
        commit(repo, "feat(PROJ-19): a test for some browsers");
        Ctx.init(repo);
        Checks.Result conditional = Checks.prPolicy(null, false);
        check("trust: a conditional skip is not a switch, and a line too long for the lint is a warning, not a failure",
                conditional.passes().stream().anyMatch(p -> p.startsWith("No switch that turns a test off or focuses one was found on the added test lines (a text check; a test run is the proof)"))
                && conditional.warns().stream().anyMatch(w -> w.contains("browser.spec.ts: 1 added line(s) longer than 2000 characters were not read for switches")));
        check("trust: a Playwright skip with a computed condition is said as a warning, with its line",
                conditional.warns().stream().anyMatch(w -> w.contains("browser.spec.ts: may switch a test off") && w.contains("sdlc check shows whether a test stopped running")
                        && w.contains("test.skip(browserName === 'webkit', 'not on Safari');")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-20-honest", "origin/main");
        write(repo, "src/test/java/ready.spec.ts", "test.skip(\n  false,\n  'supported');\ntest('a', async function () {\n  if (!ready) {\n    log.info('not ready');\n    this.skip();\n  }\n});\n"
                + "test.fixme(isMobile, ISSUE_REASON);\n");
        write(repo, "src/test/java/test_marks.py", "@pytest.mark.skipif(reason=\"later\", condition=True)\ndef test_later():\n    pass\n");
        write(repo, "src/main/java/A.java", "class A { int h; }\n");
        commit(repo, "feat(PROJ-20): conditional skips");
        Ctx.init(repo);
        Checks.Result honest = Checks.prPolicy(null, false);
        check("trust: a skip under a condition with a statement between, and a computed condition, are warnings; a plain condition by keyword fails",
                honest.fails().stream().anyMatch(f -> f.contains("No switch that turns a test off or focuses one was found") && f.contains("test_marks.py: @pytest.mark.skipif(")
                        && !f.contains("ready.spec.ts"))
                && honest.warns().stream().anyMatch(w -> w.contains("ready.spec.ts: may switch a test off") && w.contains("this.skip();") && w.contains("test.fixme(isMobile, ISSUE_REASON);")
                        && !w.contains("false,")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-21-gate", "origin/main");
        write(repo, "src/test/java/gate.spec.ts", "test.skip('webkit'\n  === browserName, 'webkit only');\ntest('x', { skip: true, ...options }, fn);\n"
                + "const testEachIf = isWindows ? test.skip.each : test.each;\n");
        write(repo, "src/test/java/GateTest.java", "class GateTest {\n  @Test(enabled = false || RUN_TESTS)\n  void a() {}\n}\n");
        write(repo, "src/test/java/test_gate.py", "@(pytest.mark.skip if IS_CI else identity)\ndef test_a():\n    pass\n\n@pytest.mark.skipif(False, True, reason='off')\ndef test_b():\n    pass\n");
        write(repo, "src/main/java/A.java", "class A { int i; }\n");
        commit(repo, "feat(PROJ-21): conditional switches and one that is not");
        Ctx.init(repo);
        Checks.Result gate = Checks.prPolicy(null, false);
        String failed = gate.fails().stream().filter(f -> f.contains("No switch that turns a test off or focuses one was found")).findFirst().orElse("");
        check("trust: check-pr fails on a True among skipif's conditions, and only on it",
                failed.contains("test_gate.py: @pytest.mark.skipif(False, True, reason='off')") && !failed.contains("gate.spec.ts") && !failed.contains("GateTest.java")
                        && !failed.contains("if IS_CI"));
        check("trust: and warns, with their lines, on what the lint cannot prove whole",
                gate.warns().stream().anyMatch(w -> w.contains("gate.spec.ts: may switch a test off") && w.contains("test.skip('webkit' === browserName, 'webkit only');")
                        && w.contains("test('x', { skip: true, ...options }, fn);") && w.contains("const testEachIf = isWindows ? test.skip.each : test.each;"))
                && gate.warns().stream().anyMatch(w -> w.contains("GateTest.java: may switch a test off") && w.contains("@Test(enabled = false || RUN_TESTS)"))
                && gate.warns().stream().anyMatch(w -> w.contains("test_gate.py: may switch a test off") && w.contains("@(pytest.mark.skip if IS_CI else identity)")));

        git(repo, "switch", "-q", "-c", "feature/PROJ-22-outside", "origin/main");
        write(repo, "web/disabled.test.ts", "it.skip('x', () => {});\n");
        write(repo, "web/__tests__/cases.ts", "describe.only('y', () => {});\n");
        write(repo, "web/helpers.ts", "export const later = 'it.skip';\n");
        write(repo, "src/test/java/OutsideTest.java", "class OutsideTest {}\n");
        write(repo, "src/main/java/A.java", "class A { int j; }\n");
        commit(repo, "feat(PROJ-22): script tests outside the configured test paths");
        Ctx.init(repo);
        String outside = Checks.prPolicy(null, false).fails().stream().filter(f -> f.contains("No switch that turns a test off or focuses one was found")).findFirst().orElse("");
        check("trust: a script test file outside the configured test paths, named or placed like one, fails check-pr as it fails sdlc check",
                outside.contains("web/disabled.test.ts: it.skip('x', () => {});") && outside.contains("web/__tests__/cases.ts: describe.only('y', () => {});")
                        && !outside.contains("web/helpers.ts"));

        git(repo, "switch", "-q", "-c", "feature/PROJ-23-round6", "origin/main");
        write(repo, "src/test/java/round6.test.ts", "test('x', { skip: true, 'skip': false }, fn);\nif (isCI) {\n  describe.skip(\n    'database', () => {});\n}\n");
        write(repo, "src/test/java/Round6Test.java", "class Round6Test {\n    @Test @Disabled void off() {}\n    @DisabledIf(\"false\") @Test void on() {}\n"
                + "    @Test void caught() { assertThrows(TestAbortedException.class, () -> assumeTrue(false)); }\n}\n");
        write(repo, "src/main/java/A.java", "class A { int k; }\n");
        commit(repo, "feat(PROJ-23): the round 6 shapes");
        Ctx.init(repo);
        Checks.Result round6 = Checks.prPolicy(null, false);
        String failed6 = round6.fails().stream().filter(f -> f.contains("No switch that turns a test off or focuses one was found")).findFirst().orElse("");
        check("trust: check-pr fails on @Test @Disabled, and only on it",
                failed6.contains("Round6Test.java: @Test @Disabled void off() {}") && !failed6.contains("round6.test.ts") && !failed6.contains("caught") && !failed6.contains("DisabledIf"));
        check("trust: and warns on the quoted override, the declaration under a condition on the line before and the caught assumption; @DisabledIf(\"false\") is not said",
                round6.warns().stream().anyMatch(w -> w.contains("round6.test.ts: may switch a test off") && w.contains("test('x', { skip: true, 'skip': false }, fn);")
                        && w.contains("if (isCI) { describe.skip( 'database', () => {});"))
                && round6.warns().stream().anyMatch(w -> w.contains("Round6Test.java: may switch a test off") && w.contains("assumeTrue(false)") && !w.contains("DisabledIf")));

        git(repo, "switch", "-q", "--orphan", "feature/PROJ-13-orphan");
        write(repo, "src/main/java/A.java", "class A { int o; }\n");
        commit(repo, "feat(PROJ-13): unrelated history");
        Ctx.init(repo);
        Checks.Result orphan = Checks.prPolicy(null, false);
        check("trust: no commit in common with the base fails, it does not pass on an empty diff", orphan.fails().stream().anyMatch(f -> f.contains("no commit in common with origin/main")));

        boolean utf8Names = "UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding", ""));
        if (utf8Names && System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            String odd = "src/test/java/\u00c9Test.java";
            git(repo, "switch", "-q", "-c", "feature/PROJ-14-odd-name", "origin/main");
            write(repo, odd, "class \u00c9Test { /* weakened */ }\n");
            commit(repo, "fix(PROJ-14): change a test with an unusual name");
            git(repo, "switch", "-q", "-c", "qe2", "origin/main");
            write(repo, odd, "class \u00c9Test { /* TS-02 */ }\n");
            String hash = Locks.hash(Files.readAllBytes(repo.resolve(odd)));
            write(repo, Locks.DIR + "/PROJ-15.json", "{\"story\": \"PROJ-15\", \"files\": {\"" + odd + "\": \"" + hash + "\"}}\n");
            commit(repo, "test(PROJ-15): lock a test with an unusual name");
            git(repo, "update-ref", "refs/remotes/origin/main", "HEAD");
            git(repo, "switch", "-q", "feature/PROJ-14-odd-name");
            Ctx.init(repo);
            Checks.Result quoted = Checks.prPolicy(null, false);
            check("trust: a locked test whose name git would quote is still protected", quoted.fails().stream().anyMatch(f -> f.contains("has locked since the branch was made") && f.contains("(PROJ-15)")));
        }
        env(Map.of());
    }

    private static void declaredStory(Path repo) throws IOException {
        Ctx.cfg = Config.parse(STRICT);
        List<String> fails = new ArrayList<>(), warns = new ArrayList<>();
        check("trust: the declared story is used", Checks.declaredStory("PROJ-7", "feature/PROJ-7-x", fails, warns).equals("PROJ-7") && fails.isEmpty() && warns.isEmpty());
        Checks.declaredStory("PROJ-5", "feature/PROJ-7-x", fails, warns);
        check("trust: a declared story that differs from the branch's fails", fails.stream().anyMatch(f -> f.contains("declares PROJ-5 but its branch names PROJ-7")));
        fails.clear();
        check("trust: a declared story must be an issue key", Checks.declaredStory("../other", "hack", fails, warns).isEmpty() && fails.stream().anyMatch(f -> f.contains("not an issue key")));
        fails.clear();
        env(Map.of("GITHUB_BASE_REF", "main"));
        check("trust: in a pull request build a story from the branch name is a warning",
                Checks.declaredStory("", "feature/PROJ-7-x", fails, warns).equals("PROJ-7") && warns.stream().anyMatch(w -> w.contains("taken from the branch name")));
        warns.clear();
        env(Map.of());
        check("trust: on a developer's machine it is not", Checks.declaredStory("", "feature/PROJ-7-x", fails, warns).equals("PROJ-7") && warns.isEmpty() && fails.isEmpty());
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
