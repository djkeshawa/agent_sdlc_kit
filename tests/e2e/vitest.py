# `sdlc check` against real Vitest, run by flow.py in its namespace (check, WORK, PROJ, SELFTEST_OUT), or by hand:
#     python3 -I tests/e2e/vitest.py BUILD NODE_MODULES WORKDIR
# BUILD: a folder of the kit's compiled classes (a copy of a self-test copy's .agent/.build, outside any .agent folder);
# NODE_MODULES: a node_modules folder with vitest installed (linked into each repository, never changed: Vitest writes its cache there);
# WORKDIR: a folder for the throw-away repositories (emptied).
#
# In flow.py the scenarios run only where SDLC_E2E_VITEST_NODE_MODULES names such a folder; otherwise one SKIP line says so.
# The check's own reporter for Vitest is run under node by the self test (CheckTests.vitestReporterRuns), which says in one
# line when node is not on the PATH; here that line must not be in the self test's output where node is on the PATH.
import os
import shutil
import subprocess
import sys
from pathlib import Path

if "RESULTS" not in globals():  # by hand
    RESULTS = []

    def check(name, ok, detail=""):
        RESULTS.append((name, bool(ok)))
        print(("PASS " if ok else "FAIL ") + name + ("" if ok or not detail else "\n      " + detail.strip().replace("\n", "\n      ")[:3000]))


def _vitest_checks(build, mods, work):
    """Each shape is a git repository: a base commit, then a change in the working tree, then `sdlc check --runs 2` in it."""
    config = 'export default {test:{maxWorkers:1,minWorkers:1,fileParallelism:false%s}};\n'
    env = {k: v for k, v in os.environ.items() if k not in ("SDLC_BASE_BRANCH", "GITHUB_BASE_REF", "CHANGE_TARGET")}

    def git(d, *a):
        r = subprocess.run(["git", "-c", "user.name=probe", "-c", "user.email=probe@example.com", "-c", "commit.gpgsign=false",
                            "-c", "core.hooksPath=/dev/null", *a], cwd=d, capture_output=True, text=True, env=env)
        assert r.returncode == 0, r.stderr

    def shape(name, base, change, cfg=""):
        d = work / name
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
        (d / "package.json").write_text('{"type":"module","devDependencies":{"vitest":"3.2.4"}}\n')
        (d / ".gitignore").write_text("node_modules/\n")
        os.symlink(mods, d / "node_modules")
        (d / "vitest.config.js").write_text(config % cfg)
        for f, t in base.items():
            (d / f).write_text(t, encoding="utf-8", errors="surrogatepass")
        git(d, "init", "-q", "-b", "main")
        git(d, "add", ".")
        git(d, "commit", "-qm", "base")
        for f, t in change.items():
            (d / f).write_text(t, encoding="utf-8", errors="surrogatepass")
        r = subprocess.run(["java", "-cp", str(build), "sdlc.Main", "check", "--base", "main", "--runs", "2"], cwd=d, capture_output=True, text=True,
                           env=env, timeout=900)
        out = r.stdout + r.stderr
        body = out[out.find("Tests this change adds or edits"):out.find("Integrity")].rstrip()
        return r.returncode, body

    def quiet(code, body):
        return code == 0 and "could not be judged" not in body and "from run to run" not in body and "fails before the change and passes after it" in body

    imp = "import {test,describe,expect} from 'vitest'; import {compute,feature,value} from './prod.js';\n"
    prod0 = "export const feature=false; export const value=0; export function compute(){return 1;}\n"
    prod1 = "export const feature=true; export const value=1; export function compute(){return 1;}\n"
    added = "test('feature', () => expect(feature).toBe(true));\n"

    # 1, 2. the guard replaced: the base guard has retry 1; the changed code returns 0 at its first call; the change makes the guard vacuous and adds a
    # sensitive test. The old guard on the new code fails at the first try and passes when run again: never a clean pass
    for where, opt, cfg in (("per test", "{retry:1}, ", ""), ("in the configuration", "", ",retry:1")):
        code, body = shape("replaced-" + where.replace(" ", "-"),
                           {"prod.js": prod0, "app.test.js": imp + "test('guard', %s() => expect(compute()).toBe(1));\n" % opt},
                           {"prod.js": "export const feature=true; let calls=0; export function compute(){return calls++===0 ? 0 : 1;}\n",
                            "app.test.js": imp + "test('guard', %s() => expect(true).toBe(true));\n" % opt + added}, cfg)
        check("real vitest: a guard replaced, retry %s: the old guard could not be judged" % where,
              "could not be judged" in body and "guard" in body.split("could not be judged")[1], body)

    # 3, 4. an honest new test that passes before the change only when run again (the old code returns 0 at its first call): not accused
    for where, opt, cfg in (("per test", "{retry:1}, ", ""), ("in the configuration", "", ",retry:1")):
        code, body = shape("honest-" + where.replace(" ", "-"),
                           {"prod.js": "export const feature=false; let calls=0; export function compute(){return calls++===0 ? 0 : 1;}\n",
                            "other.test.js": imp + "test('kept', () => expect(1).toBe(1));\n"},
                           {"prod.js": prod1, "fix.test.js": imp + "test('fix', %s() => expect(compute()).toBe(1));\n" % opt}, cfg)
        check("real vitest: an honest new test that passes before only when run again, retry %s: not accused, exit 0" % where,
              code == 0 and "does not test the change" not in body and "before the change the build ran it again" in body, body)

    # 5. ordinary work: a sensitive test added to an edited file, a test edited in place, no retries anywhere: quiet
    code, body = shape("ordinary",
                       {"prod.js": prod0, "app.test.js": imp + "test('kept', () => expect(compute()).toBe(1));\ntest('edited', () => expect(2).toBe(2));\n"},
                       {"prod.js": prod1, "app.test.js": imp + "test('kept', () => expect(compute()).toBe(1));\ntest('edited', () => expect(2).toBeGreaterThan(1));\n" + added})
    check("real vitest: ordinary work stays quiet (sensitive, nothing could not be judged, nothing from run to run, exit 0)", quiet(code, body), body)

    # 6. a new test that passes on both sides, no retries anywhere: still accused
    code, body = shape("idle", {"prod.js": prod0, "other.test.js": imp + "test('kept', () => expect(1).toBe(1));\n"},
                       {"prod.js": prod1, "idle.test.js": imp + "test('idle', () => expect(compute()).toBe(1));\n"})
    check("real vitest: a new test that passes on both sides without retries fails the check", code == 1 and "does not test the change" in body, body)

    # 7. CR6-1, the audit's case: an unchanged parameterized test whose title holds a line break, beside a new sensitive test
    multiline = "test.each([`a\nb`])('handles %s', s => expect(value).toBeGreaterThanOrEqual(0));\n"
    code, body = shape("multiline", {"prod.js": prod0, "app.test.js": imp + multiline}, {"prod.js": prod1, "app.test.js": imp + multiline + added})
    check("real vitest: a title with a line break (test.each) leaves its file judged: the new test is sensitive, nothing could not be judged", quiet(code, body), body)

    # 8. every title shape the JUnit report writes otherwise than vitest names it, in old tests of an edited file, beside a new sensitive test
    titles = ["nl a\\nb", "tab a\\tb", "cr a\\rb", "crlf a\\r\\nb", "spaces a   b", "  lead and trail  ", "lt <x>", "gt a > b", "amp a & b", 'dq \\"q\\"', "sq \\'q\\'",
              "bs a\\\\b", "ansi \\x1b[31mred\\x1b[0m", "nul a\\0b", "ctl a\\x01\\x7f\\x85\\x9fb", "emoji \\u{1F600}", "lone high \\uD800 x", "lone low a\\uDC00b",
              "pair low \\u{1F600}\\uDC00 x", "repl \\uFFFD x", "nonchar \\uFFFE x", "fdd0 \\uFDD0 x", "vt \\v ff \\f", "ls \\u2028 ps \\u2029", "entity &amp; &#10;",
              "plane1 \\u{1FFFE} x", "plane16 \\u{10FFFE}y", "combining e\\u0301"]
    shapes = "const titles = [" + ", ".join('"%s"' % t for t in titles) + "];\n" + \
             "for (const t of titles) test(t, () => expect(value).toBeGreaterThanOrEqual(0));\ntest('L'.repeat(100000), () => expect(1).toBe(1));\n" + \
             "describe('outer\\nnested\\t<&>', () => { describe('  inner  \"x\"', () => { test(\"leaf 'y'\\r\\n\", () => expect(1).toBe(1)); }); });\n" + \
             "describe.each([{ name: 'n\\tm' }, { name: 'o<p' }])('each $name', ({ name }) => { test('t', () => expect(name).toBeTruthy()); });\n" + \
             "test.each([[{ a: 'b\\nc' }, [1, 'x\\ty']]])('obj %o arr %s', () => expect(1).toBe(1));\ntest.each([{ k: 'v\\nw' }])('dollar $k', () => expect(1).toBe(1));\n"
    code, body = shape("titles", {"prod.js": prod0, "app.test.js": imp + shapes}, {"prod.js": prod1, "app.test.js": imp + shapes + added})
    check("real vitest: titles with line ends, tabs, spaces, < > & quotes, a backslash, ANSI, control characters, emoji, lone surrogates, a long title, "
          "nested groups and each with %s, %o and $name leave their file judged", quiet(code, body), body)

    # 9. two titles the report writes alike: that name could not be judged, the rest of the file is judged
    code, body = shape("alike", {"prod.js": prod0, "app.test.js": imp + "test.each(['x\\ny', 'x y'])('dup %s', () => expect(1).toBe(1));\n"},
                       {"prod.js": prod1, "app.test.js": imp + "test.each(['x\\ny', 'x y'])('dup %s', () => expect(1).toBe(1));\n" + added})
    check("real vitest: two titles the report writes alike are not told apart, and the new test of the file is still judged",
          code == 0 and "fails before the change and passes after it" in body and "dup x y: more than one test reports this name" in body
          and "does not name a test" not in body and "names more tests" not in body, body)

    # 10. the replaced guard again, its title with a line break and a tab: the retry is still learnt
    code, body = shape("replaced-multiline",
                       {"prod.js": prod0, "app.test.js": imp + "test('guard\\nof\\tthe fix', {retry:1}, () => expect(compute()).toBe(1));\n"},
                       {"prod.js": "export const feature=true; let calls=0; export function compute(){return calls++===0 ? 0 : 1;}\n",
                        "app.test.js": imp + "test('guard\\nof\\tthe fix', {retry:1}, () => expect(true).toBe(true));\n" + added})
    check("real vitest: a guard replaced whose title holds a line break and a tab: the old guard could not be judged",
          "could not be judged" in body and "guard of the fix" in body.split("could not be judged")[1], body)

    # 11. a title vitest writes so that its report is not XML ('&' before a lone low surrogate loses its ';'): unavoidable, and said, never silent
    code, body = shape("unreadable", {"prod.js": prod0, "app.test.js": imp + "test('kept', () => expect(1).toBe(1));\n"},
                       {"prod.js": prod1, "app.test.js": imp + "test('kept', () => expect(1).toBe(1));\ntest('amp &\\uDC00x', () => expect(1).toBe(1));\n" + added})
    check("real vitest: a title that makes vitest's report not XML is said, not passed in silence",
          "could not be judged" in body and "cannot be read" in body, body)


def _main_in_flow():
    mods = os.environ.get("SDLC_E2E_VITEST_NODE_MODULES", "")
    if shutil.which("node"):
        check("the self test ran the check's reporter for vitest under node", "reporter for vitest was not run" not in SELFTEST_OUT, SELFTEST_OUT[-600:])
    else:
        print("SKIP the self test's run of the check's reporter for vitest: node is not on the PATH")
    if not mods:
        print("SKIP real-Vitest checks (tests/e2e/vitest.py): set SDLC_E2E_VITEST_NODE_MODULES to a node_modules folder with vitest 3 installed to run them")
        return
    mods = Path(mods).resolve()
    if not shutil.which("node") or not (mods / "vitest" / "vitest.mjs").is_file():
        check("real vitest: SDLC_E2E_VITEST_NODE_MODULES names a node_modules folder with vitest, and node is on the PATH", False, str(mods))
        return
    work = WORK / "vitest"
    build = work / "build"  # outside any .agent folder, so the tool takes the repository it runs in for its own
    if work.exists():
        shutil.rmtree(work)
    shutil.copytree(PROJ / ".agent" / ".build", build)
    _vitest_checks(build, mods, work / "repos")


if __name__ == "__main__" and "SELFTEST_OUT" not in globals():
    b, m, w = map(lambda a: Path(a).resolve(), sys.argv[1:4])
    if w.exists():
        shutil.rmtree(w)
    _vitest_checks(b, m, w)
    passed = sum(1 for _, ok in RESULTS if ok)
    print("\n%d/%d real-Vitest checks passed" % (passed, len(RESULTS)))
    sys.exit(0 if passed == len(RESULTS) else 1)
else:
    _main_in_flow()
