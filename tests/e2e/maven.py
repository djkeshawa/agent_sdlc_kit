"""`sdlc check` against real Maven, Surefire and JUnit (4 and 5).

    python3 tests/e2e/maven.py BUILD WORKDIR [--show] [--only NAME]

BUILD is a folder of the tool's compiled classes (a copy of a self-test repository's
.agent/.build). WORKDIR is emptied and used for the test repositories and for the tool's
scratch checkouts. `mvn` must be on the PATH and able to fetch from Maven Central once.

Each shape is a small Maven project in a git repository: a base commit, a change in the
working tree, then `sdlc check` in it. What the check prints is compared with what the change
really is. --show prints the whole output of each shape.
"""
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok or not detail else "\n      " + detail.strip().replace("\n", "\n      ")[:4000]), flush=True)


class Bound:
    """A text with one number of seconds in it that the check measured: the bound of a run alone before the change. The rule
    (Witness.beforeBound) makes it ten times the same run after the change, at least 60 s, at most the --timeout and the time
    left for single runs, so a slow machine prints a larger one. The number must be a whole number from low to high; every
    other character is compared exactly."""

    def __init__(self, before, after, low, high):
        self.pattern = re.compile(re.escape(before) + r"(0|[1-9][0-9]*)" + re.escape(after))
        self.low, self.high, self.text = low, high, "%s<%d to %d>%s" % (before, low, high, after)

    def found(self, body):
        seen = [int(m.group(1)) for m in self.pattern.finditer(body)]
        return bool(seen) and all(self.low <= n <= self.high for n in seen)

    def __repr__(self):
        return repr(self.text)


POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>demo</groupId><artifactId>demo</artifactId><version>1</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>
    <dependency><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>5.11.4</version><scope>test</scope></dependency>
    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>%s</plugin>
  </plugins></build>
</project>
"""

J5 = "package demo;\nimport org.junit.jupiter.api.*;\nimport org.junit.jupiter.params.ParameterizedTest;\nimport org.junit.jupiter.params.provider.ValueSource;\n" \
     "import static org.junit.jupiter.api.Assertions.*;\nimport static org.junit.jupiter.api.Assumptions.*;\n"
MAIN = "src/main/java/demo/Calc.java"
TEST = "src/test/java/demo/CalcTest.java"


def calc(body):
    return "package demo;\npublic class Calc {\n" + body + "}\n"


BROKEN = calc("    public static int half(int n) { return n / 3; }\n    public static String name(String s) { return s.trim(); }\n")
FIXED = calc("    public static int half(int n) { return n / 2; }\n    public static String name(String s) { return s == null ? \"\" : s.trim(); }\n")


def j5(body, cls="CalcTest"):
    return J5 + "class " + cls + " {\n" + body + "}\n"


ZERO = "    @Test void zero() { assertEquals(0, Calc.half(0)); }\n"
HALVES = "    @Test void halves() { assertEquals(2, Calc.half(4)); }\n"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    show = "--show" in sys.argv
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    if only:
        args.remove(only)
    build, work = Path(args[0]).resolve(), Path(args[1]).resolve()
    if work.exists():
        shutil.rmtree(work)
    (work / "tmp").mkdir(parents=True)
    # not the base branch of a pull request build, and no GIT_* of the caller's shell: with a GIT_DIR, git would work in that repository
    env = {k: v for k, v in os.environ.items() if k not in ("SDLC_BASE_BRANCH", "GITHUB_BASE_REF", "CHANGE_TARGET") and not k.upper().startswith("GIT_")}

    def git(d, *a):
        r = subprocess.run(["git", "-c", "user.name=probe", "-c", "user.email=probe@example.com", "-c", "commit.gpgsign=false",
                            "-c", "core.hooksPath=/dev/null", *a], cwd=d, capture_output=True, text=True, env=env)
        assert r.returncode == 0, r.stderr

    def shape(name, base, change, surefire="", runs="1", pom=None, timeout=None):
        """base and change: per file its text; None in change deletes the file. Returns None when --only names another shape."""
        if only and only != name:
            return None
        d = work / name
        d.mkdir(parents=True)
        (d / "pom.xml").write_text(pom or POM % surefire)
        (d / ".gitignore").write_text("target/\n")
        for f, t in base.items():
            (d / f).parent.mkdir(parents=True, exist_ok=True)
            (d / f).write_text(t)
        git(d, "init", "-q", "-b", "main")
        git(d, "add", ".")
        git(d, "commit", "-qm", "base")
        for f, t in change.items():
            if t is None:
                (d / f).unlink()
                continue
            (d / f).parent.mkdir(parents=True, exist_ok=True)
            (d / f).write_text(t)
        r = subprocess.run(["java", "-Djava.io.tmpdir=" + str(work / "tmp"), "-cp", str(build), "sdlc.Main", "check", "--base", "main", "--runs", runs]
                           + (["--timeout", str(timeout)] if timeout else []), cwd=d, capture_output=True, text=True, env=env, timeout=3600)
        out = r.stdout + r.stderr
        if show:
            print("=== %s (exit %d)\n%s" % (name, r.returncode, out), flush=True)
        return r.returncode, out[out.find("Tests this change adds or edits"):out.find("Not checked")].rstrip()

    def modules(name, main0, main1, test0, test1):
        """A parent project with one module, core: the test file is in the module, the build starts at the parent."""
        parent = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>'
                  '<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><modules><module>core</module></modules></project>\n')
        return shape(name, {"core/" + MAIN: main0, "core/" + TEST: test0, "core/pom.xml": POM % ""},
                     {"core/" + MAIN: main1, "core/" + TEST: test1}, pom=parent)

    NOT_RUN = ("could not be run", "did not run", "is not available", "gave no result", "timed out", "could not be resolved")

    def said(result, name, code, has=(), lacks=(), ran=True):
        """The check holds when the exit code is the one named, every text of has is there, none of lacks is,
        and (ran) nothing says that Maven did not run: a shape must not pass on a run that never happened."""
        if result is None:
            return
        got, body = result
        missing = [t for t in has if not (t.found(body) if isinstance(t, Bound) else t in body)]
        present = [t for t in list(lacks) + (list(NOT_RUN) if ran else []) if t in body]
        check("real maven: " + name, (code is None or got == code) and not missing and not present,
              "exit %d, expected %s%s%s\n%s" % (got, "either" if code is None else code, "; missing: %r" % missing if missing else "", "; must not be there: %r" % present if present else "", body))

    FILE = "      src/test/java/demo/CalcTest.java  [Maven Surefire]\n"
    QUIET = ("could not be judged", "from run to run", "not run now", "new test")

    said(shape("sensitive", {MAIN: BROKEN, TEST: j5(ZERO)}, {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, runs="2"),
         "a fix with a JUnit 5 test that fails before it and passes after it is sensitive, in 2 of 2 runs", 0,
         ["  1 fails before the change and passes after it, in 2 of 2 runs\n" + FILE + "        halves\n",
          "  1 test that was already in the edited files passes before and after (not counted)"], QUIET)

    said(shape("trivial", {MAIN: BROKEN, TEST: j5(ZERO)}, {MAIN: FIXED, TEST: j5(ZERO + "    @Test void idle() { assertTrue(true); }\n")}),
         "a new test that passes before and after does not test the change, and the check fails", 1,
         ["new test passes before and after, so it does not test the change", "idle"], ["fails before the change and passes after it", "not run now"])

    lost = "the file as it was passed 2 of its tests, and after the change it runs 1: at least 1 test that ran before the change does not run now"
    said(shape("disabled", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: j5(ZERO + "    @Disabled(\"later\")\n" + HALVES)}),
         "a test switched off with @Disabled is a test that ran and does not run now, by its name", 1,
         [lost + " (skipped now under a name that ran before: halves)", "PROBLEM  test switched off: src/test/java/demo/CalcTest.java  @Disabled(\"later\")"])

    said(shape("assumed", {MAIN: FIXED, TEST: j5(ZERO + HALVES)},
               {TEST: j5(ZERO + "    @Test void halves() {\n        assumeTrue(false);\n        assertEquals(2, Calc.half(4));\n    }\n")}),
         "a test switched off with an assumption that never holds is a test that ran and does not run now: the run says it, the lint only notes it", 1,
         [lost + " (skipped now under a name that ran before: halves)", "note     may switch a test off"], ["PROBLEM"])

    said(shape("removed", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: j5(ZERO)}),
         "a test method taken out is a test that ran and does not run now, by its name", 1, [lost + " (no longer reported: halves)"])

    said(shape("file-deleted", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: None}),
         "a test class deleted: its two tests ran and do not run now", 1,
         ["the file passed 2 of its tests before the change and was deleted: at least 2 tests that ran before the change do not run now",
          "PROBLEM  test file deleted: src/test/java/demo/CalcTest.java"])

    rows = "    @ParameterizedTest @ValueSource(ints = {%s}) void even(int n) { assertEquals(n, Calc.half(n) * 2); }\n"
    said(shape("row-added", {MAIN: BROKEN, TEST: j5(rows % "0")}, {MAIN: FIXED, TEST: j5(rows % "0, 4")}),
         "a row added to a parameterized test that fails before the fix is sensitive", 0,
         ["  1 fails before the change and passes after it\n" + FILE + "        even(int)[2]\n"], QUIET)

    said(shape("row-inserted", {MAIN: FIXED, TEST: j5(rows % "0, 4")}, {TEST: j5(rows % "0, 2, 4")}),
         "a row put in among the rows of a parameterized test, passing on both sides, is a new test that does not test the change", 1,
         ["at least 1 new test passes before and after"], ["fails before the change and passes after it", "not run now"])

    j4file = "src/test/java/demo/OldStyleTest.java"
    j4 = "package demo;\nimport org.junit.Test;\nimport static org.junit.Assert.assertEquals;\npublic class OldStyleTest {\n%s}\n"
    z4 = "    @Test public void zero() { assertEquals(0, Calc.half(0)); }\n"
    said(shape("junit4", {MAIN: BROKEN, j4file: j4 % z4}, {MAIN: FIXED, j4file: j4 % (z4 + "    @Test public void halves() { assertEquals(2, Calc.half(4)); }\n")}),
         "a JUnit 4 test that fails before the fix and passes after it is sensitive", 0,
         ["  1 fails before the change and passes after it\n      " + j4file + "  [Maven Surefire]\n        halves\n"], QUIET)

    p4 = ("package demo;\nimport org.junit.Test;\nimport org.junit.runner.RunWith;\nimport org.junit.runners.Parameterized;\nimport java.util.*;\n"
          "import static org.junit.Assert.assertEquals;\n@RunWith(Parameterized.class)\npublic class OldStyleTest {\n"
          "    @Parameterized.Parameters public static Collection<Object[]> rows() { return Arrays.asList(new Object[][] {%s}); }\n"
          "    private final int n;\n    public OldStyleTest(int n) { this.n = n; }\n    @Test public void even() { assertEquals(n, Calc.half(n) * 2); }\n}\n")
    said(shape("junit4-rows", {MAIN: BROKEN, j4file: p4 % "{0}"}, {MAIN: FIXED, j4file: p4 % "{0}, {4}"}),
         "a row added to a JUnit 4 parameterized test that fails before the fix is sensitive", 0,
         ["  1 fails before the change and passes after it\n      " + j4file + "  [Maven Surefire]\n        even[1]\n"], QUIET)

    nested = ZERO + "    @Nested class Halves {\n        @Test void four() { assertEquals(2, Calc.half(4)); }\n%s    }\n"
    said(shape("nested", {MAIN: BROKEN, TEST: j5(nested % "")}, {MAIN: FIXED, TEST: j5(nested % "        @Test void six() { assertEquals(3, Calc.half(6)); }\n")}),
         "a test added to a nested class: the two tests that fail before the fix are sensitive", 0,
         ["  2 fail before the change and pass after it\n" + FILE + "        four\n        six\n"], QUIET)

    named = ZERO + "    @Test @DisplayName(\"halves a number\") void halves() { assertEquals(2, Calc.half(4)); }\n"
    said(shape("display-name", {MAIN: BROKEN, TEST: j5(ZERO)}, {MAIN: FIXED, TEST: j5(named)}),
         "a test with a display name that fails before the fix is sensitive, under the name Surefire reports", 0,
         ["  1 fails before the change and passes after it\n" + FILE + "        halves\n"], QUIET)

    said(shape("crash-is-the-bug", {MAIN: BROKEN, TEST: j5(ZERO)},
               {MAIN: FIXED, TEST: j5(ZERO + "    @Test void noName() { assertEquals(\"\", Calc.name(null)); }\n")}, runs="2"),
         "a test that throws before the fix (the exception is the bug) and passes after it is counted apart, with the exception, in 2 of 2 runs", 0,
         ["  1 fails before the change by an exception, not by a check, and passes after it, in 2 of 2 runs\n" + FILE
          + "        noName: threw NullPointerException before the change\n",
          "  An exception before the change may come from another cause, such as a broken set-up: look at the exception each such test threw.\n"],
         QUIET + ("fails before the change and passes after it", "A test of code that did not exist"))

    by_name = ZERO + "    @Test void twice() throws Exception {\n        assertEquals(\"4\", String.valueOf(Class.forName(\"demo.Twice\").getMethod(\"of\", int.class).invoke(null, 2)));\n    }\n"
    said(shape("new-api-by-name", {MAIN: FIXED, TEST: j5(ZERO)},
               {"src/main/java/demo/Twice.java": "package demo;\npublic class Twice {\n    public static int of(int n) { return n * 2; }\n}\n", TEST: j5(by_name)}),
         "a test that reaches by name a class that did not exist before the change throws there, could not be judged, and is not counted", 0,
         ["  1 could not be judged\n" + FILE + "        twice: before the change it threw ClassNotFoundException, not an assertion\n",
          "      A test of code that did not exist before the change cannot fail there for the right reason, so it is not counted either way.\n"],
         ["by an exception", "new test passes", "fails before the change and passes after it"])

    said(shape("new-api", {MAIN: FIXED, TEST: j5(ZERO)},
               {MAIN: FIXED[:-2] + "    public static int twice(int n) { return n * 2; }\n}\n",
                TEST: j5(ZERO + "    @Test void twice() { assertEquals(4, Calc.twice(2)); }\n")}),
         "a test of code that did not exist before the change could not be judged, and the check passes", 0,
         ["could not be judged", "twice", "does not compile"], ["fails before the change and passes after it", "new test passes"], ran=False)

    setup = "    @BeforeAll static void ready() { assertEquals(2, Calc.half(4)); }\n"
    said(shape("before-all", {MAIN: BROKEN, TEST: j5(setup + ZERO)}, {MAIN: FIXED, TEST: j5(setup + ZERO + HALVES)}),
         "a class whose set-up fails before the fix: its tests could not be judged, and none is called a new test that proves nothing", 0,
         ["could not be judged"], ["new test passes before and after", "not run now"])

    moved = "package demo.moved;\nimport demo.Calc;\n" + J5.split("\n", 1)[1] + "class CalcTest {\n" + ZERO + HALVES + "}\n"
    said(shape("moved", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: None, "src/test/java/demo/moved/CalcTest.java": moved}),
         "a test class moved to another package with its imports changed is a moved file with the tests it had, and the check passes", 0,
         ["  1 test file moved (not counted)", "moved from src/test/java/demo/CalcTest.java (its lines are the same but for its package and import lines)",
          "it reports as many tests as it had", "  2 tests that were already in the edited or moved files pass before and after (not counted)"],
         ["could not be judged", "new test", "not run now", "PROBLEM"])

    base_class = J5 + "abstract class CalcContract {\n" + ZERO + "%s}\n"
    said(shape("abstract-base", {MAIN: BROKEN, "src/test/java/demo/CalcContract.java": base_class % "", TEST: J5 + "class CalcTest extends CalcContract {}\n"},
               {MAIN: FIXED, "src/test/java/demo/CalcContract.java": base_class % HALVES}),
         "a test added to an abstract base class: Maven reports it under the subclass, so the check says it has no test it can run (it is not a pass)", 0,
         ["Tests this change adds or edits\n  none that this version can run.\n"], ["fails before the change and passes after it", "new test passes before and after"], ran=False)

    rerun = "<configuration><rerunFailingTestsCount>2</rerunFailingTestsCount></configuration>"
    flaky = "    static int calls;\n    @Test void sometimes() { assertTrue(calls++ > 0); }\n"
    said(shape("rerun", {MAIN: BROKEN, TEST: j5(ZERO)}, {MAIN: FIXED, TEST: j5(ZERO + flaky)}, surefire=rerun),
         "a new test that passes only when Surefire runs it again is not a pass: it gave different results", 0,
         ["sometimes"], ["new test passes before and after", "  1 fails before the change and passes after it"])

    wrapper = "#!/bin/sh\nexec mvn \"$@\"\n"
    said(shape("wrapper", {MAIN: BROKEN, TEST: j5(ZERO), "mvnw": wrapper}, {MAIN: FIXED, TEST: j5(ZERO + HALVES)}),
         "a project with a Maven wrapper: the wrapper is used, and the fix's test is sensitive", 0,
         ["  1 fails before the change and passes after it\n" + FILE + "        halves\n"], QUIET)

    said(modules("modules", BROKEN, FIXED, j5(ZERO), j5(ZERO + HALVES)),
         "a test in a module of a multi-module build is run through the build's root, and is sensitive", 0,
         ["  1 fails before the change and passes after it\n      core/src/test/java/demo/CalcTest.java  [Maven Surefire]\n        halves\n"], QUIET)

    hangs(shape, said)
    label = os.environ.get("SDLC_VERIFY_LABEL", "")
    if label and (not only or only.startswith("hang")):
        alive = containers(label)
        check("real maven: no container of this run is alive after the runs the check stopped at their time bound", label and not alive,
              "no label of this run's containers was given" if not label else "still running: " + " ".join(alive))

    passed = sum(1 for _, ok in RESULTS if ok)
    print("\n%d/%d real-Maven checks passed" % (passed, len(RESULTS)))
    sys.exit(0 if passed == len(RESULTS) else 1)


def containers(label):
    """The containers of this run that are still running, after a grace of up to 20 s for a watcher to end them."""
    for _ in range(20):
        r = subprocess.run(["docker", "ps", "-q", "--filter", "label=" + label], capture_output=True, text=True)
        if r.returncode == 0 and not r.stdout.split():
            return []
        time.sleep(1)
    return r.stdout.split() or ["(docker ps failed: %s)" % r.stderr.strip()]


# Before the change, locked() deadlocks on two monitors (an interrupt does not end it) and once() deadlocks when it is called a second
# time in a test JVM; items() is empty. After the change none of them hangs.
HANG_MAIN = """package demo;
public class Calc {
    private static int calls;
    public static int half(int n) { return n / %s; }
    public static int locked() {
%s    }
    public static int once() { return ++calls > 1 ? locked() : 1; }
    public static java.util.List<Integer> items() { return java.util.List.of(%s); }
    static void pause() { try { Thread.sleep(300); } catch (InterruptedException e) { } }
}
"""
DEADLOCK = """        Object a = new Object(), b = new Object();
        Thread t = new Thread(() -> { synchronized (b) { pause(); synchronized (a) { } } });
        t.setDaemon(true);
        synchronized (a) { t.start(); pause(); synchronized (b) { } }
        return 1;
"""
HUNG = HANG_MAIN % ("3", DEADLOCK, "")
MENDED = HANG_MAIN % ("2", "        return 1;\n", "1, 2")
J4H = "package demo;\nimport org.junit.*;\nimport static org.junit.Assert.*;\n"


def hangs(shape, said):
    """A run before the change that does not end: its tests are run alone, one method at a time, on both sides."""
    hang, side = "src/test/java/demo/HangTest.java", "src/test/java/demo/SideTest.java"
    files = "      %s  [Maven Surefire]\n" % hang, "      %s  [Maven Surefire]\n" % side

    def alone(s):
        return ": ran alone on both sides, as before the change its file did not end within %d s\n" % s

    def was(s):
        return ": before the change the file timed out after %d s; " % s

    def unended(before, timeout, after="\n", low=60):
        return Bound(before + "it did not end before the change within ", " s" + after, low, timeout)

    j5h = J5 + "class HangTest {\n%s}\n"
    j5s = J5 + "class SideTest {\n%s}\n"
    zero5 = "    @Test void zero() { assertEquals(0, Calc.half(0)); }\n"
    more5 = zero5 + ("    @Test void halves() { assertEquals(2, Calc.half(4)); }\n    @Test void idle() { assertTrue(true); }\n"
                     "    @Test void locks() { assertEquals(1, Calc.locked()); }\n")
    side5 = "    @Test void sideZero() { assertEquals(0, Calc.half(0)); }\n"
    j4h = J4H + "public class HangTest {\n%s}\n"
    j4s = J4H + "public class SideTest {\n%s}\n"
    zero4 = "    @Test public void zero() { assertEquals(0, Calc.half(0)); }\n"
    more4 = zero4 + ("    @Test public void halves() { assertEquals(2, Calc.half(4)); }\n    @Test public void idle() { assertTrue(true); }\n"
                     "    @Test public void locks() { assertEquals(1, Calc.locked()); }\n")
    side4 = "    @Test public void sideZero() { assertEquals(0, Calc.half(0)); }\n"
    for name, h, s, zero, more, sidez, sideh in (
            ("hang5", j5h, j5s, zero5, more5, side5, "    @Test void sideHalves() { assertEquals(3, Calc.half(6)); }\n"),
            ("hang4", j4h, j4s, zero4, more4, side4, "    @Test public void sideHalves() { assertEquals(3, Calc.half(6)); }\n")):
        said(shape(name, {MAIN: HUNG, hang: h % zero, side: s % sidez}, {MAIN: MENDED, hang: h % more, side: s % (sidez + sideh)}, timeout=150),
             name + ": the run before the change does not end; each test of the file, and of the file run beside it, is run alone on both sides: the fix's tests "
             "are sensitive, a new test that passes on both sides fails the check, the test that hangs could not be judged with its bound", 1,
             ["  2 fail before the change and pass after it\n" + files[0] + "        halves" + alone(150) + files[1] + "        sideHalves" + alone(150),
              "the file reports 3 tests it did not have, and 1 of its tests does not pass on both sides, and 1 of its tests has no result before the change: at least 1 new test passes before and after. "
              "Under names the file did not have, 1 passes before and after (idle)",
              unended("  1 could not be judged\n" + files[0] + "        locks" + was(150) + "run alone (demo.HangTest#locks), ", 150),
              "  2 tests that were already in the edited files pass before and after (not counted); of them, 2 ran alone on both sides, as before the change "
              "their test files did not end within 150 s\n"],
             ["fails after", "not run now", "from run to run", "used up"], ran=False)

    kinds = J5 + "class KindsTest {\n%s}\n"
    rows = "    @ParameterizedTest @ValueSource(ints = {0, 4, 9}) void rows(int n) { assertEquals(n == 9 ? 1 : n, n == 9 ? Calc.locked() : Calc.half(n) * 2); }\n"
    nested = "    @Nested class Inner {\n        @Test void four() { assertEquals(2, Calc.half(4)); }\n    }\n"
    made = ("    @TestFactory java.util.List<DynamicTest> made() {\n        return Calc.items().stream().map(i -> DynamicTest.dynamicTest(\"item \" + i, () -> assertTrue(i > 0))).toList();\n"
            "    }\n")
    order = (J5 + "@TestMethodOrder(MethodOrderer.OrderAnnotation.class)\nclass OrderTest {\n    static boolean firstRan;\n"
             "    @Test @Order(1) void first() { firstRan = true; assertEquals(2, Calc.half(4)); }\n"
             "    @Test @Order(2) void second() { assertTrue(firstRan, \"first must run before\"); }\n}\n")
    kfile, ofile = "      src/test/java/demo/KindsTest.java  [Maven Surefire]\n", "      src/test/java/demo/OrderTest.java  [Maven Surefire]\n"
    said(shape("hang-kinds", {MAIN: HUNG}, {MAIN: MENDED, "src/test/java/demo/KindsTest.java": kinds % (rows + nested + made), "src/test/java/demo/OrderTest.java": order},
               timeout=150),
         "hang-kinds: rows of one method run together, so one row that hangs leaves every row of it unjudged; a nested class is run as a class of its own; "
         "a test that needs another test of its file before it could not be judged, never said to fail before; a single run that reports no test could not be judged", 0,
         ["  2 fail before the change and pass after it\n" + kfile + "        four" + alone(150) + ofile + "        first" + alone(150),
          unended("        rows(int)[3]" + was(150) + "run alone (demo.KindsTest#rows), ", 150),
          unended("        rows(int)[1]" + was(150) + "run alone (demo.KindsTest#rows), ", 150),
          "        second" + was(150) + "run alone after the change it failed, and with its file it passed\n",
          "        made()[1]" + was(150) + "run alone before the change, it reported no test"],
         ["second: fails", "fails after", "not run now", "used up", "new test passes"], ran=False)

    setup = J5 + "class SetupTest {\n    @BeforeAll static void ready() { Calc.locked(); }\n    @Test void a() { assertEquals(2, Calc.half(4)); }\n    @Test void b() { assertTrue(true); }\n}\n"
    sfile = "      src/test/java/demo/SetupTest.java  [Maven Surefire]\n"
    # all single runs share the 70 s of --timeout: the run of a before the change gets what its run after the change left, under 60 s when that took more than 10 s
    said(shape("hang-before-all", {MAIN: HUNG}, {MAIN: MENDED, "src/test/java/demo/SetupTest.java": setup}, timeout=70),
         "hang-before-all: a set-up that hangs makes every method of its class hang alone too: none is judged", 0,
         [unended("  2 could not be judged\n" + sfile + "        a" + was(70) + "run alone (demo.SetupTest#a), ", 70, low=1), "        b" + was(70)],
         ["fails before", "new test passes", "ran alone on both sides"], ran=False)

    said(shape("hang-used-up", {MAIN: HUNG, hang: j5h % zero5, side: j5s % side5}, {MAIN: MENDED, hang: j5h % more5, side: j5s % (side5 + "    @Test void sideHalves() { assertEquals(3, Calc.half(6)); }\n")},
               timeout=15),
         "hang-used-up: what the shared time of the single runs does not reach stays as without them, with the reason", None,
         ["        sideZero" + was(15) + "it was not run alone, as the time for single runs was used up\n",
          "        sideHalves" + was(15) + "it was not run alone, as the time for single runs was used up\n"],
         ["sideHalves" + alone(15), "fails after", "not run now"], ran=False)

    once = J5 + "class OnceTest {\n%s}\n"
    twice = ("    @Test void halves() { Calc.once(); assertEquals(2, Calc.half(4)); }\n"
             "    @Test void sixes() { Calc.once(); assertEquals(3, Calc.half(6)); }\n")
    said(shape("hang-runs-2", {MAIN: HUNG}, {MAIN: MENDED, "src/test/java/demo/OnceTest.java": once % twice}, runs="2", timeout=40),
         "hang-runs-2: with --runs 2, the single runs are made again, not the run that did not end, and the tests are sensitive in 2 of 2 runs", 0,
         ["  2 fail before the change and pass after it, in 2 of 2 runs\n      src/test/java/demo/OnceTest.java  [Maven Surefire]\n        halves" + alone(40) + "        sixes" + alone(40)],
         ["could not be judged", "from run to run"], ran=False)


if __name__ == "__main__":
    main()
