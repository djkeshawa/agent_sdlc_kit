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

    def shape(name, base, change, surefire="", runs="1"):
        """base and change: per file its text; None in change deletes the file. Returns None when --only names another shape."""
        if only and only != name:
            return None
        d = work / name
        d.mkdir(parents=True)
        (d / "pom.xml").write_text(POM % surefire)
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

    def said(result, name, ok, *needs):
        if result is None:
            return
        code, body = result
        check("real maven: " + name, ok(code, body) and all(n in body for n in needs), "exit %d\n%s" % (code, body))

    def clean(code, body):
        return code == 0 and "could not be judged" not in body and "from run to run" not in body

    def fails(code, body):
        return code == 1

    def undecided(code, body):
        return code == 0 and "could not be judged" in body

    said(shape("sensitive", {MAIN: BROKEN, TEST: j5(ZERO)}, {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, runs="2"),
         "a fix with a JUnit 5 test that fails before it and passes after it is sensitive, in 2 of 2 runs", clean,
         "1 fails before the change and passes after it", "halves", "in 2 of 2 runs")

    said(shape("trivial", {MAIN: BROKEN, TEST: j5(ZERO)}, {MAIN: FIXED, TEST: j5(ZERO + "    @Test void idle() { assertTrue(true); }\n")}),
         "a new test that passes before and after does not test the change, and the check fails", fails,
         "new test passes before and after", "idle")

    said(shape("disabled", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: j5(ZERO + "    @Disabled(\"later\")\n" + HALVES)}),
         "a test switched off with @Disabled is a test that ran and does not run now", fails, "not run now")

    said(shape("assumed", {MAIN: FIXED, TEST: j5(ZERO + HALVES)},
               {TEST: j5(ZERO + "    @Test void halves() {\n        assumeTrue(false);\n        assertEquals(2, Calc.half(4));\n    }\n")}),
         "a test switched off with an assumption that never holds is a test that ran and does not run now", fails, "not run now")

    said(shape("removed", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: j5(ZERO)}),
         "a test method taken out is a test that ran and does not run now", fails, "not run now")

    said(shape("file-deleted", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: None}),
         "a test class deleted: its tests ran and do not run now", fails, "was deleted", "not run now")

    rows = "    @ParameterizedTest @ValueSource(ints = {%s}) void even(int n) { assertEquals(n, Calc.half(n) * 2); }\n"
    said(shape("row-added", {MAIN: BROKEN, TEST: j5(rows % "0")}, {MAIN: FIXED, TEST: j5(rows % "0, 4")}),
         "a row added to a parameterized test that fails before the fix is sensitive", clean, "fails before the change and passes after it")

    said(shape("row-inserted", {MAIN: FIXED, TEST: j5(rows % "0, 4")}, {TEST: j5(rows % "0, 2, 4")}),
         "a row put in among the rows of a parameterized test, passing on both sides, is a new test that does not test the change", fails,
         "new test passes before and after")

    j4 = "package demo;\nimport org.junit.Test;\nimport static org.junit.Assert.assertEquals;\npublic class OldStyleTest {\n%s}\n"
    z4 = "    @Test public void zero() { assertEquals(0, Calc.half(0)); }\n"
    said(shape("junit4", {MAIN: BROKEN, "src/test/java/demo/OldStyleTest.java": j4 % z4},
               {MAIN: FIXED, "src/test/java/demo/OldStyleTest.java": j4 % (z4 + "    @Test public void halves() { assertEquals(2, Calc.half(4)); }\n")}),
         "a JUnit 4 test that fails before the fix and passes after it is sensitive", clean, "1 fails before the change and passes after it", "halves")

    nested = ZERO + "    @Nested class Halves {\n        @Test void four() { assertEquals(2, Calc.half(4)); }\n%s    }\n"
    said(shape("nested", {MAIN: BROKEN, TEST: j5(nested % "")}, {MAIN: FIXED, TEST: j5(nested % "        @Test void six() { assertEquals(3, Calc.half(6)); }\n")}),
         "a test added to a nested class: the two tests that fail before the fix are sensitive", clean,
         "2 fail before the change and pass after it", "four", "six")

    said(shape("crash-is-the-bug", {MAIN: BROKEN, TEST: j5(ZERO)},
               {MAIN: FIXED, TEST: j5(ZERO + "    @Test void noName() { assertEquals(\"\", Calc.name(null)); }\n")}),
         "a test that throws before the fix (the exception is the bug) could not be judged today", undecided, "NullPointerException")

    said(shape("new-api", {MAIN: FIXED, TEST: j5(ZERO)},
               {MAIN: FIXED[:-2] + "    public static int twice(int n) { return n * 2; }\n}\n",
                TEST: j5(ZERO + "    @Test void twice() { assertEquals(4, Calc.twice(2)); }\n")}),
         "a test of code that did not exist before the change could not be judged, and the check passes", undecided, "twice")

    moved = "package demo.moved;\nimport demo.Calc;\n" + J5.split("\n", 1)[1] + "class CalcTest {\n" + ZERO + HALVES + "}\n"
    said(shape("moved", {MAIN: FIXED, TEST: j5(ZERO + HALVES)}, {TEST: None, "src/test/java/demo/moved/CalcTest.java": moved}),
         "a test class moved to another package with its imports changed is a moved file, and the check passes", lambda code, body: code == 0,
         "moved from src/test/java/demo/CalcTest.java")

    rerun = "<configuration><rerunFailingTestsCount>2</rerunFailingTestsCount></configuration>"
    flaky = "    static int calls;\n    @Test void sometimes() { assertTrue(calls++ > 0); }\n"
    said(shape("rerun", {MAIN: BROKEN, TEST: j5(ZERO)}, {MAIN: FIXED, TEST: j5(ZERO + flaky)}, surefire=rerun),
         "a new test that passes only when Surefire runs it again is not a clean pass", lambda code, body: "sometimes" in body and ("ran it again" in body or "could not be judged" in body),
         "sometimes")

    passed = sum(1 for _, ok in RESULTS if ok)
    print("\n%d/%d real-Maven checks passed" % (passed, len(RESULTS)))
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
