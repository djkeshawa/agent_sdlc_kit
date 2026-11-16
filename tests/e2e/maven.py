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
import shutil
import subprocess
import sys
from pathlib import Path

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok or not detail else "\n      " + detail.strip().replace("\n", "\n      ")[:4000]), flush=True)


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
    env = {k: v for k, v in os.environ.items() if k not in ("SDLC_BASE_BRANCH", "GITHUB_BASE_REF", "CHANGE_TARGET")}

    def git(d, *a):
        r = subprocess.run(["git", "-c", "user.name=probe", "-c", "user.email=probe@example.com", "-c", "commit.gpgsign=false",
                            "-c", "core.hooksPath=/dev/null", *a], cwd=d, capture_output=True, text=True, env=env)
        assert r.returncode == 0, r.stderr

    def shape(name, base, change, surefire="", runs="1", pom=None):
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
        r = subprocess.run(["java", "-Djava.io.tmpdir=" + str(work / "tmp"), "-cp", str(build), "sdlc.Main", "check", "--base", "main", "--runs", runs],
                           cwd=d, capture_output=True, text=True, env=env, timeout=3600)
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
        missing = [t for t in has if t not in body]
        present = [t for t in list(lacks) + (list(NOT_RUN) if ran else []) if t in body]
        check("real maven: " + name, got == code and not missing and not present,
              "exit %d, expected %d%s%s\n%s" % (got, code, "; missing: %r" % missing if missing else "", "; must not be there: %r" % present if present else "", body))

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

    passed = sum(1 for _, ok in RESULTS if ok)
    print("\n%d/%d real-Maven checks passed" % (passed, len(RESULTS)))
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
