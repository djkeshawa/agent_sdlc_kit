# 9. `sdlc check`: the change in front of you

`sdlc check` needs no configuration, no account and no tracker. It works in any git repository,
with or without the rest of the kit, and it writes nothing to your working tree or index.

It answers one question about the change you (or your agent) just made: **would its own tests
notice if the change were missing?**

```bash
sdlc check
```

From a repository that has the kit installed, the same command is
`java .agent/Sdlc.java check`.

## What it prints

```text
sdlc check: 4 files changed against origin/main (86ae793)

Tests this change adds or edits
  1 fails before the change and passes after it, in 3 of 3 runs
      src/test/java/demo/RefundsTest.java  [Maven Surefire]
        partRefunded
  1 new test passes before and after, so it does not test the change
      src/test/java/demo/RefundsTest.java  [Maven Surefire]
        zeroPaid
  1 could not be judged
      src/test/java/demo/WindowTest.java  [Maven Surefire]
        insideTheWindow: before the change the file does not compile
  1 test that was already in the edited files passes before and after (not counted)

Integrity
  nothing found

Not checked
  - whether the tests execute the lines the change touches
  - whether tests that passed before the change still pass: run your full test suite
  - whether the change is correct or complete; only that its own tests would notice its absence
```

Three parts:

| Part | What it is |
| --- | --- |
| **Tests this change adds or edits** | Each such test is run after the change and before it. "Before" is the commit your branch started from, with only the change's test files laid over it. A test file the change edits is run a third time as it was, on that commit, to learn which tests it had. When a test of such a file is gone, or may be switched off, that file is run a fourth time as it was, on the code after the change |
| **Integrity** | What the diff does to the things that check the change: tests switched off or removed, secrets, changes to the build, the pipeline, dependencies and migrations |
| **Not checked** | What this run did not look at, so silence is never read as approval |

## How to read the test results

| Result | Meaning |
| --- | --- |
| Fails before, passes after | The test is sensitive to the change. It is run three times on each side, so a test that fails at random does not count |
| New test passes before and after | It passes whether or not the change is there, so it does not test the change. This fails the check |
| Fails after the change | The change's own test fails. This fails the check |
| Ran before the change, skipped now | A test that ran in the file as it was (it passed, failed or threw) and is skipped now: the change switched it off. Said by name when nothing else in the file's list of tests changed; otherwise said of the file, "at least N", when fewer of its tests run and more are skipped (see below). This fails the check |
| Removed | The edited file reports fewer tests than it had. When it reports no new name, the gone tests are named; otherwise the file is named with both lists and "at least N were removed": which ones cannot be told. This fails the check. A file whose tests were all taken out counts too |
| Passed before, fails as it was after, taken out | An old test that passed before the change, run as the file was on the code after the change, fails, in every run; the file no longer reports its name or skips it, and no test of the file could be it rewritten (see below). This fails the check |
| Could not be judged | Before the change the test did not compile, threw something other than a failed assertion, or was not found. Or the file as it was could not be run (it did not compile, timed out, or no build tool), so it cannot be told whether a test is new. Or the reports fit two stories, one of which is fine: a rename and a test deleted and another written; a test switched off and rows that changed places; a new test and an old one under a new name. Each such line says what the reports show and which two stories they cannot tell apart. It never fails the check |
| Different results from run to run | Unstable. Not counted as sensitive. A test file whose runs say different things about which tests were switched off or removed is unstable too |
| Skipped | A test skipped in a file the change adds, or in a file none of whose tests ran before the change. Listed |
| Already in the file, passes on both | A test of a name the file reported before the change. Not counted. The same for a test skipped under the same name before and after the change |

### Which tests are new, gone or switched off

A report names the tests of a file; it does not say what a test is. The runs of a
parameterized test may be named by their place (`check(int)[3]`, `rows[0]`), by their arguments
or by a display name you chose, and a name of one shape may be any of these: JUnit 4 writes
`rate[10]` for the argument 10, and a display name can be anything. So the check knows a test
by its exact name in its class or file, and by nothing else; it never reads a meaning into the
shape of a name. Where cases are named by their place, a row put in among the arguments gives
every later row the next name and the last one a name the file did not have, and a row taken
out does the opposite. What the names cannot tell, the counts of the file decide, because they
are true however tests are named. What the counts cannot prove is said as "could not be judged",
never passed in silence.

For each edited file, from the run as it was (old) and the run after the change: names on both
sides are the same tests; G names are gone and F are new; a name that ran before and is skipped
now is an off flip, the other way an on flip; R0, S0 and R1, S1 are how many tests ran and were
skipped, over the whole file, before and after.

Switched off:

| In the file | Result |
| --- | --- |
| No off flip and no test skipped under a new name, or no test of the file ran before | Nothing switched off. A test skipped under the same name on both sides is skipped as before |
| Off flips, and nothing else in its list of tests changed (no new or gone name, no on flip) | Each off flip ran before and is skipped now, by name. Fails |
| Fewer run and more are skipped (R1 < R0, S1 > S0) | At least min(R0 - R1, S1 - S0) tests that ran do not run now: switched off, or removed while skipped tests were added. Fails |
| Anything else where a test that ran may be skipped now | Could not be judged, with the counts and the names: off flips beside other changes (a switch-off, or tests that changed places), more skipped while as many run (a skipped test added, or one switched off and another added), a test skipped under a new name (a new skipped test, or one that ran, renamed and switched off) |

A skip is decided again from the whole reports of each repeat run (`--runs`); an answer that
differs from run to run is unstable.

New tests that pass on both sides. A file that reports F new names and no longer reports G has
at least F - G tests it did not have. If X of its tests do not pass on both sides (they fail
before, fail after, could not be judged, or are skipped where a skip may hide a test), at least
N = F - G - X of the new tests pass on both sides:

| In the file | Result |
| --- | --- |
| N = 0 | Each test under a new name that passes on both sides could not be judged: it may be an old test under a new name, or moved on by a row put in before it |
| N equals the tests under new names that pass on both sides | Each of them does not test the change. Fails |
| 0 < N, fewer than those | The file is named with them: at least N new tests do not test the change. Fails |

So a fix that adds one sensitive test is sensitive; one that adds a sensitive test and one that
passes on both sides fails for the second; a row put in among the arguments that is itself
sensitive does not fail the check, though the last row has a new name.

Gone tests (G gone, F new):

| In the file | Result |
| --- | --- |
| G gone, no new name | Each gone test was removed. Fails |
| More gone than new (G > F) | At least G - F were removed. Fails |
| As many new as gone or more | Could not be judged: a rename looks exactly like a test deleted and another written. When fewer tests run and more are skipped, that is said instead (above) |

A file whose counts say tests were removed is run again with the repeat runs; when the counts
differ between runs (its tests come from a source that gives another number each time) it is
unstable, not a removal.

The fourth run, for a file with a test gone or one that may be switched off: every old test of
the file that passed as it was is run as it was on the code after the change. One that fails or
throws there, in every run, failed on what the change wrote; what became of it decides:

| The old test | Result |
| --- | --- |
| Its name is gone or skipped now (B such tests), and the file has M tests that pass after the change and do not pass on both sides (any of which could be one of them rewritten for the changed behaviour) | B > M: at least B - M were taken out of the way, by name when M = 0. Fails. B <= M: could not be judged, as the old tests may have been rewritten under new names |
| Its name still runs after the change | Could not be judged: it may have been edited for the changed behaviour, or taken out and its name given to the next row. Where the file runs fewer tests than before, the check already fails for that |
| It fails in some runs only | Unstable |

**What a good result does not mean.** The tests and the code come from the same author. A test
that fails before and passes after shows the test notices the change. It is not evidence that
the change is correct.

## What the change is

Everything between the commit where your branch left its base branch and your working tree as
it is now: commits on the branch, staged and unstaged edits, and new files that are not ignored.

The base is found in this order: `--base`, the CI event's base branch, `delivery.baseBranch`
when the repository has `.agent/sdlc.yml`, the remote's default branch, then `origin/main`,
`origin/master`, `origin/develop`, `main`, `master`, `develop`. With no base branch, only
uncommitted changes are checked, and the report says so.

## Options

| Option | Effect |
| --- | --- |
| `--base REF` | Compare with this branch, tag or commit |
| `--runs N` | Runs per side for a test that looks sensitive (default 3) |
| `--timeout S` | Seconds one test run may take (default 900) |
| `--no-run` | Read the diff only; run no tests |
| `--since 30d` | Replay the check over each recent commit of the branch (`12h`, `2w`, `3m` or a date). `--max N` sets how many (default 20). A report on the past: it never fails |
| `--stop-hook` | For an agent's Stop hook; see below |

Exit code: 0 when nothing is wrong, 1 when a changed test fails, a new test passes on both sides,
a test that ran before the change is skipped now, tests were removed, a test the change broke was
taken out of the way, or the integrity scan finds a problem, 3 on a usage error. "Could not be
judged" and notes never fail.

## Which tests it can run

| Tests | How | What it needs |
| --- | --- | --- |
| Java with Maven | Surefire, through `./mvnw` when the repository has it, else `mvn`. In a multi-module build it runs the module of each test with the modules it needs | A JDK and Maven, as for your normal build |
| JavaScript and TypeScript with Vitest | `vitest run` with its JUnit reporter | Node.js, and `npm install` already done in the repository |

Test files of other kinds are listed under "Not checked". Integration tests run by Maven
Failsafe are not run.

Both sides run in throw-away copies outside the repository, written by git. For Vitest the
copies link to the repository's `node_modules` folders. Running a test runs the repository's
code, with your user's rights, as your normal test run does.

## As a Stop hook for a coding agent

An agent that says "done" while its new test passes with or without its change has not tested
anything. As a Stop hook, the check keeps the agent working until that is fixed.

Claude Code, in `.claude/settings.json`:

```json
{
  "hooks": {
    "Stop": [
      { "hooks": [ { "type": "command", "command": "sdlc check --stop-hook", "timeout": 600 } ] }
    ]
  }
}
```

- It blocks (exit code 2, the reasons on standard error) only on what is certain and fixable: a
  changed test that fails, a new test that passes on both sides, a test that ran before the
  change and is skipped now, tests removed, a test switched off, a test the change broke and
  took out of the way, a secret.
- It never blocks on "could not be judged" or on a note, so work on a new feature is not held up.
- It blocks once. When the agent is already continuing because of a stop hook, the reasons are
  printed and the agent is let go, so it cannot loop.
- It says nothing when there is nothing to fix.

## Limits

- It judges tests the change adds or edits, nothing else. Run your full suite as before.
- For a new feature most tests "could not be judged": the code they call is not there before
  the change. The useful evidence for a feature is elsewhere (tests agreed before the work).
- It does not yet check that a test executes the lines the change touches. An author who wants
  to cheat can write a test that tells the two sides apart without testing the fix.
- A test that depends on the time, the network or the order of tests may be reported as
  unstable.
- Two tests in one file that report the same name cannot be told apart and are not judged.
- An edited test file is run three times instead of two: after the change, before it, and as it
  was. For Maven this is one more build of the module on the base commit (on a small project, 75
  seconds instead of 61). A change that only adds test files needs no third run.
- A fourth run costs one more checkout of the changed tree and one more run of the files
  concerned (for Maven, one more build of their modules), and with `--runs N` up to N - 1 more on
  each of the two sides when it finds a broken test. It is made only when a test is gone or may
  be switched off, and only for those files. A file whose skips may hide a test switched off, or
  whose counts say tests were removed, is also run again as it was with each repeat run.
- In a report, a renamed test looks exactly like one test deleted and another written, and a
  renamed `describe` block or nested class renames every test in it. The check does not read
  the file's text or a title inside a name to tell them apart; it judges the counts (see above).
  So a pure rename could not be judged, and a test deleted and another written in its place that
  passes on both sides could not be judged either, unless the fourth run shows the gone test
  fails on the change.
- Where cases are named by their place, two rows that exchange both their places and which of
  them is skipped, or a row switched off and moved to the end while another is put in, give
  reports exactly like no change at all. The runs cannot see that; the reading of the diff
  (Integrity) looks for the skip itself. A test skipped under the same name before and after the
  change is taken to be the same test, here and when new tests are counted.
- The comparison before and after the change runs the same test file on both sides, so its
  tests are named alike there. Where the rows of a parameterized test come from the production
  code (an enum, a list in the code), the change can give another number or order of rows on
  each side, and rows named by their place are then compared with other rows.
- In the fourth run only the test files go back to how they were. Fixtures, helpers, build files
  and the other changed test files are as the change has them. A test renamed and not otherwise
  edited therefore runs there exactly as under its new name after the change, and is never
  accused for the rename. A test the change renamed and rewrote, or whose fixture it rewrote, is
  run as it was against what the change wrote: when it fails there and the file has no test
  that could be its rewrite (one that passes after the change and fails before it, or could not
  be judged), the check fails, though the behaviour may have changed on purpose; otherwise it
  could not be judged. A test kept under its own name and edited could not be judged when its
  old version fails there. To change what a test expects on purpose, change it under its own
  name.
- Each test file is judged by its own counts. A test moved to another changed file is gone from
  its file: removed there, unless that file reports as many new names as it lost. In the other
  file it is a new test.
- When the test file as it was does not compile or load on the change (the change renamed what
  it used), or gives no result, the fourth run tells nothing: its gone tests could not be judged,
  and the report says why.
- A file whose tests were all taken out: its tests were removed. For Maven the file runs and
  reports none (a build that passes with no report for the class; a build that fails, or no
  build tool, is not read as "no tests"). Vitest reports such a file, and a `describe` block
  without tests, as a failed case ("No test found in suite ..."), which is listed as failing
  after the change: a report writes it exactly as a test's own failure, so it is not left out.
- A test file that is moved or renamed (git sees one file deleted and one added) has no earlier
  version under its new name: all its tests count as new.
- A test whose name changes from run to run (a time or a random value in it) looks new each time.
- When one new test stops the others from compiling before the change, each changed test file
  is run on its own. A test then counts as sensitive only if it also passes on its own after
  the change: otherwise its failure before the change may come from another changed test file.
  For Maven the compiled tests of the module are removed before each such run, so a test file
  that was taken away is not still there as a class.
- A test file that cannot be named safely on a command line is not run. For Maven: a class
  name or module folder with a character outside letters, digits, `_`, `$`, `.`, `/` and `-`
  (a comma or a space would be read as a list or an option). For Vitest: a file name that
  starts with a dash.
- For Vitest both sides run with the packages installed now. When the change touches what the
  test's project has installed, a test that passes on both sides is reported as "could not be
  judged", not as a test that proves nothing. That is: a lock file or a package manager's
  settings in the project's folder or above it, or a `package.json` there (or of a package the
  project depends on) whose lists of packages changed. A new script in `package.json`, or a
  package file elsewhere in the repository, does not count.
- The output of a build is kept only at its start and its end (2 MB each) for the messages of
  the report. Whether the code compiled or loaded is read from every line as it arrives, so it
  does not depend on what is kept. An answer of git larger than 64 MB stops the check with an
  error.
- The change is the working tree as it is on disk. Flags that tell git not to look at a file
  (`assume-unchanged`, `skip-worktree`) are not honoured. A flagged file that is missing counts
  as deleted, unless a sparse checkout is on and its rules leave that file out; telling the two
  apart needs git 2.41 or newer, and an older git with such files stops the check with an error.
- A test is "switched off" when the change puts a skip, a to-do mark or a focus into a test
  file. The change itself says where to look: a switch is new when it stands on a line the
  change wrote, and the lines it replaced did not hold the same switch on the same thing. So a
  skip taken off one test and put on another is found, however the tests are grouped, and a
  line that is only written differently (other quotes, another indent) is not a new skip.
  Neither is a skipped test that was moved as a whole to another place of the file.
- The switch word itself is looked for in the file's code, whatever calls it (`it.skip`,
  `test.describe.skip`, `vitest.it.skip`, a helper of your own with `.skip`). A call written
  over several lines is found; text in a comment, a string or a regular expression is not a
  switch.
- The reading of a file knows the tokens of the language, not its grammar. Where it cannot
  follow a file (its brackets do not pair up for the reader: text of a JSX element with a
  bracket or a quote in it does this), the report says so in a note. Switches in such a file
  are still found.
- When a change adds something that looks like a skipped test inside a comment or a string,
  the report says so as a note (a warning in `check-pr`), and a person looks at it: a file
  can be written so that code is taken for text.
- What it cannot see: a switch reached through another name (`const later = it.skip`, then
  `later('x', ...)`), a test skipped from inside its body by the test framework's context, and
  two tests of one title whose bodies are exchanged while the skip stays where it is. When
  the tests are run, a skipped test of a changed file is listed as skipped whatever the
  source says.
- Reported as switched off: `skip`, `todo`, `only`, `fixme`, `xit`/`xdescribe`, `fit`/`fdescribe`,
  `@Disabled`, `@DisabledIf...`, `@Ignore`, an assumption that always fails (`assumeTrue` with a
  plain `false` among its arguments, `assumeFalse` with a plain `true`, `Assumptions.abort`,
  also as a statically imported `abort`), `@pytest.mark.skip`
  and its forms, `@unittest.skip` and its forms, `t.Skip`. Not reported: `it.skipIf`,
  `it.runIf`, `@DisabledOnOs`, `@EnabledIf...` and other JUnit conditions.
- In a pull request build the base branch must be fetched, with enough history to reach the
  commit the branch started from. Otherwise the check stops with an error.
- Until the reading of the diff for removed and renamed tests is replaced by the runs, both say
  what they find: a removed test may be reported twice, once under Integrity.
