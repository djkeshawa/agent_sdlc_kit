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

/**
 * Self test: what check-pr trusts in a pull request build. A repository of its own, with a base
 * branch and branches that try to be judged by their own rules.
 */
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

    /** main: a commit without rules (origin/bare), then one with the strict rules (origin/main). */
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
        check("trust: a base branch without rules is said out loud", first.warns().stream().anyMatch(w -> w.contains("origin/bare has no .agent/sdlc.yml")));

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
        // two branches leave main before QE locks the test
        git(repo, "switch", "-q", "-c", "feature/PROJ-7-touches-the-test", "origin/main");
        write(repo, test, "class ATest { /* TS-01, weakened */ }\n");
        commit(repo, "fix(PROJ-7): change the acceptance test");
        git(repo, "switch", "-q", "-c", "feature/PROJ-8-leaves-it", "origin/main");
        write(repo, "src/main/java/A.java", "class A { int y; }\n");
        write(repo, "src/test/java/BTest.java", "class BTest {}\n");
        commit(repo, "feat(PROJ-8): another change");
        // QE locks the test on main
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
        // QE's own pull request: the test and a lock that names its new content, nothing else
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
        env(Map.of());
    }

    /** Things a pull request can do to blind the check, each on a branch from the base. */
    private static void blinding(Path repo) throws IOException {
        env(Map.of("GITHUB_BASE_REF", "main"));
        // local state committed into the pull request names another story and its own scopes
        git(repo, "switch", "-q", "-c", "feature/PROJ-11-state", "origin/main");
        write(repo, ".sdlc/current.json", "{\"story\": \"PROJ-99\", \"task\": \"PROJ-100\"}\n");
        write(repo, "src/main/java/A.java", "class A { int s; }\n");
        write(repo, "src/test/java/STest.java", "class STest {}\n");
        commit(repo, "feat(PROJ-11): with local state");
        Ctx.init(repo);
        Checks.Result state = Checks.prPolicy(null, true);
        check("trust: local state committed into a pull request fails the check", state.fails().stream().anyMatch(f -> f.contains("No local state (.sdlc/) is committed")));
        check("trust: and the story it names is not used", state.passes().stream().noneMatch(p -> p.contains("PROJ-99")) && state.fails().stream().noneMatch(f -> f.contains("PROJ-99")));

        // .gitattributes marks the tests as binary, so a plain diff shows no lines to scan
        git(repo, "switch", "-q", "-c", "feature/PROJ-12-binary", "origin/main");
        write(repo, ".gitattributes", "*.java -diff\n");
        write(repo, "src/test/java/DTest.java", "import org.junit.jupiter.api.Disabled;\nclass DTest { @Disabled void off() {} }\n");
        write(repo, "src/main/java/A.java", "class A { int b; }\n");
        commit(repo, "feat(PROJ-12): quietly switch a test off");
        Ctx.init(repo);
        Checks.Result binary = Checks.prPolicy(null, false);
        check("trust: lines of a file the pull request marks as binary are still scanned", binary.fails().stream().anyMatch(f -> f.contains("No test was disabled or focused")));

        // a branch with no commit in common with the base: every diff would be empty or fail
        git(repo, "switch", "-q", "--orphan", "feature/PROJ-13-orphan");
        write(repo, "src/main/java/A.java", "class A { int o; }\n");
        commit(repo, "feat(PROJ-13): unrelated history");
        Ctx.init(repo);
        Checks.Result orphan = Checks.prPolicy(null, false);
        check("trust: no commit in common with the base fails, it does not pass on an empty diff", orphan.fails().stream().anyMatch(f -> f.contains("no commit in common with origin/main")));

        // a file name git would quote: the lock names it exactly
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
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
