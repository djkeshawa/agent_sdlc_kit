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
  at least 1 new test passes before and after, so it does not test the change
      src/test/java/demo/RefundsTest.java  [Maven Surefire]
        the file reports 2 tests it did not have, and 1 of its tests does not pass on both sides: at least 1 new test passes before and after. Under names the file did not have, 1 passes before and after (zeroPaid); the names do not say which test is new, as where tests are named by their place a row put in before the others moves an old one to a name the file did not have
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
| **Tests this change adds or edits** | Each such test is run after the change and before it. "Before" is the commit your branch started from, with only the change's test files laid over it. A test file the change edits is run a third time as it was, on that commit, to learn which tests it had. When the list of tests such a file reports changed (a name it did not report, one it no longer reports, or a name with more or fewer of its tests skipped), that file is run a fourth time as it was, on the code after the change |
| **Integrity** | What the diff does to the things that check the change: tests switched off (linted from the lines the change adds to test files), test files deleted, secrets, changes to the build, the pipeline, dependencies and migrations. Nothing is run for it, so it is the early warning where no test runs |
| **Not checked** | What this run did not look at, so silence is never read as approval |

## How to read the test results

| Result | Meaning |
| --- | --- |
| Fails before, passes after | The test is sensitive to the change. It is run three times on each side, so a test that fails at random does not count |
| New test passes before and after | It passes whether or not the change is there, so it does not test the change. In a file the change adds, each such test is named. In an edited file the file is named, "at least N", with the names the file reports for the first time; those names need not be the new tests (see below). Said of an edited file only where the run of the file as it was lists every test (see below), and only where the counts are the same in every run. This fails the check |
| Fails after the change | The change's own test fails. This fails the check |
| Tests that ran before the change do not run now | The edited file runs fewer tests after the change than passed in it as it was: at least N tests that ran do not run now, whatever became of them (deleted, skipped, hidden in a skipped class or an empty report, renamed while switched off). Said of the file, "at least N", with what the reports show: the names it no longer reports, the names skipped now, the names it reports for the first time; the names do not say which tests. Must hold in every run. Not said where the file as it was reported a test that failed or threw (see below). This fails the check |
| Passed before, fails as it was after, taken out | An old test that passed before the change, run as the file was on the code after the change, fails, in every run; in every run of the file after the change that the check made, the first and every repeat, the file does not report its name or skips it, and no test of the file could be it rewritten in any of them (see below). This fails the check |
| Could not be judged | Before the change the test did not compile, threw something other than a failed assertion, or was not found. Or the file as it was could not be run (it did not compile, timed out, or no build tool), so it cannot be told whether a test is new. Or the reports fit two stories, one of which is fine: a rename and a test deleted and another written; a test switched off and rows that changed places; a new test and an old one under a new name; an old test edited, or taken out and its name given to another. Or a report of the file may not list every test (see below). Or the file as it was could not be run on the code after the change when the fourth run asked for it, or did not report an old test there as passed or failed: the file gets a line of its own saying so, whatever else is said of it. Each such line says what the reports show and which stories they cannot tell apart, or why nothing is counted. It never fails the check |
| Different results from run to run | Unstable. Not counted as sensitive. A test file whose runs say different things about how many tests that ran do not run now, or which tests are new or switched off, is unstable too, and so is an old test the change breaks that is gone or skipped in one run after the change and runs in another, or that fails as it was on the change in one run and not in another |
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
never passed in silence; so is a run the check asked for that gave no answer.

Every decision that must hold in every run is made from every run the check made, each repeat
of each way (`--runs`), after the last of them.

Tests that ran and do not run now. For each edited file, R0 is how many of its tests passed in
the run of the file as it was, and R1 how many run after the change: every test the report lists
that is not skipped, whatever its result. Each test that passed is one test that ran (a failing
set-up, container or class is never reported as passed), and a test hidden in a skipped case or
behind an empty report does not run. So at least R0 - R1 tests that ran before the change do not
run now, whatever is skipped, hidden, renamed or emptied, under any naming, and without a list of
the tests after the change. Whether they were deleted or switched off does not matter: they ran,
and they do not run now. A test that fails after the change counts in R1: it is said as failing,
not again as a test that does not run.

What the change of the code took, not the change of the tests. Where the rows of a
parameterized test come from the production code (an enum, a list in the code), a change of the
code can take rows away. So the file as it was is also run on the code after the change (the
fourth run, below), and R4 is how many of its tests run there. At least N = min(R0, R4) - R1 of the
tests that do not run now were taken by the change of the test file; the rest, R0 - R4, the change
of the code may have taken, and that is said apart, as could not be judged (the old tests the
fourth run does not report). Where the fourth run gives no usable report (the old file does not
compile or load on the code after the change, or a failure stands in place of its tests), N =
R0 - R1, still a failure, and the line says that the file as it was could not be run on the code
after the change, so whether the change of the tests or of the code took them is not known. This
is a choice: that tests that ran do not run now is true either way, and a person must look.

| In the file | Result |
| --- | --- |
| N > 0 in every run (each run as it was against each run after the change) | At least N tests that ran before the change do not run now. Said of the file, with the counts and what the reports show. Fails |
| Fewer run than passed as it was in some runs only | Unstable |
| Fewer run in every run, but only by what the code took (R4 < R0) | Could not be judged, said once, by the fourth run's line |
| The file as it was reported a test that failed or threw | Could not be judged, with the counts and the reason: a file that did not pass as it was is not said to have lost tests. This is a choice, not a proof |
| As many run as passed as it was, in every run | Nothing is lost. What the names cannot tell is said below |

Two tests merged into one is one test that ran and does not run now, and is said so: the counts
cannot tell a merge from a test deleted, and the check takes that stance. A skipped test deleted
loses nothing that ran: as many tests run as passed, and the case gone could not be judged (it may
have stood for any number of tests or none). A row put in a parameterized test, at the end or
before the others, or tests that change places, lose nothing.

What the names cannot tell, where nothing that ran is lost. A name the file reports as many
times on both sides is lined up with itself. That is a way of counting, not a proof that it
stands for the same test on both sides: named by place, it may be another row on each side (see
Limits). G tests are under names gone, or under a name reported another number of times, and F
under names that are new; a name that ran before and is skipped now is an off flip, the other way
an on flip.

| In the file | Result |
| --- | --- |
| No off flip and no test skipped under a new name, or no test of the file ran before | Nothing switched off. A test skipped under the same name on both sides is skipped as before |
| Anything else where a test that ran may be skipped now | Could not be judged, with the counts and the names: off flips beside other changes (a switch-off, or tests that changed places), more skipped while as many run (a skipped test added, or one switched off and another added), a test skipped under a new name (a new skipped test, or one that ran, renamed and switched off) |
| More gone than new (G > F) | Could not be judged: the cases gone did not pass as it was (skipped, or a case of a failure) |
| As many new as gone or more (G <= F) | Could not be judged: a rename looks exactly like a test deleted and another written |

Even when only off flips changed, the name of one does not say which test was switched off:
named by place, two rows that change places while the one moved to the front is switched off,
`[a, b]` to `[b skipped, a]`, give one off flip, at the first name, while `a` still runs under
the second.

A skip is decided again from the whole reports of every run, each report of the file as it was
against each report after the change, down to which names (with their class) ran before and are
skipped now and the words of the answer; an answer that differs from run to run is unstable,
also where the first run's skips said nothing.

New tests that pass on both sides. A file that reports F new names and no longer reports G has
at least F - G tests it did not have. If X of its tests do not pass on both sides (they fail
before, fail after, could not be judged, or are skipped where a skip may hide a test), at least
N = F - G - X of the new tests pass on both sides:

| In the file | Result |
| --- | --- |
| N = 0, or the run of the file as it was may not list every test (see below) | Each test under a new name that passes on both sides could not be judged: it may be an old test under a new name, moved on by a row put in before it, or one the old report did not list |
| N > 0 | The file is named: at least N new tests do not test the change. The names under which it reports tests for the first time and that pass on both sides are listed, but they need not be the new tests: a row put in before the others, `[a, b]` to `[c, a, b]`, puts `c` under a name the file had and `b` under a new one. Fails |

So a fix that adds one sensitive test is sensitive; one that adds a sensitive test and one that
passes on both sides fails for the second; a row put in among the arguments that is itself
sensitive does not fail the check, though the last row has a new name.

A file whose counts say tests that ran do not run now, or that new tests pass on both sides, is
run again with the repeat runs; what is said must hold in every run.

The fourth run, for each edited file whose list of tests changed in any way (which every file
that runs fewer tests than passed as it was is): every old test of the file that passed as it was
is run as it was on the code after the change. How many of them run there is R4 (above). One that fails or
throws there, in every run, failed on what the change wrote; what became of it decides:

| The old test | Result |
| --- | --- |
| Its name is gone or skipped now, in every run of the file after the change that the check made (B such tests), and the file has M tests that pass after the change and do not pass on both sides, in the run that has the most (any of which could be one of them rewritten for the changed behaviour) | B > M: at least B - M were taken out of the way, by name when M = 0 (the name is the old file's own, run on both codes). Fails. B <= M: could not be judged, as the old tests may have been rewritten under new names |
| Its name still runs after the change | Could not be judged: it may have been edited for the changed behaviour, or taken out and its name given to the next row. Where the file runs fewer tests than passed before, the check already fails for that |
| Its name is gone or skipped in some runs after the change only, a run after the change gave no result, or it fails as it was in some runs only | Unstable |

An old test said to be taken out of the way is one of the tests that ran and do not run now, so
it is not counted again there: that count is said only beyond the old tests already said.

A report that may not list every test. Some counts take a report for the whole list of the
file's tests, one case for each. A case that passed is one test that ran; the others need not
be. A case that failed or threw may stand for a fault outside a test's body: JUnit 4 reports a
class it cannot set up as one case, `initializationError`, in place of its tests, and Vitest
reports a failed `afterAll` as a case of its own. A skipped case may stand for any number of
tests: JUnit 4 reports an ignored class as one skipped case, and Surefire a disabled JUnit 5
factory or template as one, whose tests it lists one by one once they run. And a report with
no case at all may come from a JUnit 5 class that stops in its set-up on an assumption. Which
case stands for what a report does not say, and its name is not read to guess it. So a report
is taken for the whole list only where it reports at least one test, holds no test that failed
or threw, and each case it skips is skipped under its name, as many times, in the other report
(whatever such a case hides is then hidden on both sides alike). Each count needs only what it
rests on:

| Count | Needs |
| --- | --- |
| Tests that ran and do not run now | Nothing of this: it counts tests that passed as it was and tests that run now, and a hidden test does not run. So a class, factory or template switched off as one skipped case, and a file emptied of its tests, are said, as every test that passed in them. Only a failure in the report as it was keeps it from being said, by choice (above) |
| New tests that pass on both sides | The report as it was lists every test (a test hidden after the change does not pass, so it is never counted as one) |
| Old tests taken out of the way | No report after the change holds a failure (a failure may stand in place of the old test, which then fails and is not gone). An old test inside a case skipped after the change does not run: it was taken out of the way |

What a count cannot rest on could not be judged, with the reason. So switching on an ignored
class is not said to add tests, and a case that failed before and is skipped now under its name
(a failing set-up turned into an assumption) is not said to be a test lost. A failure after the
change fails the check as always; where the file also runs fewer tests than passed as it was,
that is said too, with the remark that a build that stops after a failure skips the tests after
it (Surefire's `skipAfterFailureCount`). The run before the change (the new file on the old code)
is not counted so: there a failure is what a sensitive test does, a test it does not list could
not be judged, and no count is taken from it.

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
tests that ran before the change do not run now, a test the change broke was taken out of the way,
or the integrity scan finds a problem, 3 on a usage error. "Could not be
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
  changed test that fails, a new test that passes on both sides, tests that ran before the
  change and do not run now (deleted, skipped or hidden), a test the change broke and took out of
  the way, a problem of the integrity scan (a test switched off on an added line, a secret).
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
- A fourth run costs one more checkout of the changed tree for each runner that needs one, and
  one more run of the files concerned (for Maven, one more build of their modules), and with
  `--runs N` up to N - 1 more on each of three sides when it finds a broken test: the file as it
  was on the code before and after the change, and as the change has it. It is made for each
  edited file whose list of tests changed, and only for those files. Adding a test to an
  existing test file changes its list, so a fix that does so costs one more build. A file whose
  skips may hide a test switched off, or whose counts say tests that ran do not run now or new
  tests pass on both sides, is also run again as it was with each repeat run, and one that runs
  fewer tests than passed as it was also as it was on the code after the change.
- In a report, a renamed test looks exactly like one test deleted and another written, and a
  renamed `describe` block or nested class renames every test in it. The check does not read
  the file's text or a title inside a name to tell them apart; it judges the counts (see above).
  So a pure rename could not be judged, and a test deleted and another written in its place that
  passes on both sides could not be judged either, unless the fourth run shows the gone test
  fails on the change. Two tests merged into one are one test fewer: said as a test that ran and
  does not run now.
- When an edited file reports the very same list of tests as before (the same names, as many
  tests under each, as many of them skipped), the check takes each name for the same test,
  edited perhaps, and does not run the old file on the change. Any test replaced by another
  under its name is then not seen: a test the change breaks taken out while another is put in
  under its name (named by place, a row taken out of the middle while one is added at the end),
  rows that change places, two rows that exchange both their places and which of them is
  skipped, a test rewritten to expect something else. Its reports are exactly those of tests
  edited in place, so this is a limit of reports, not of the runs: running the old file there
  would only add "could not be judged" to every test whose expectation is changed under its own
  name, the ordinary way to change one. Integrity looks for a skip itself, on the lines the
  change adds.
- A test skipped under the same name before and after the change is taken to be the same test,
  here and when new tests are counted. The reports cannot tell; this is a choice, so that a
  test skipped all along does not keep its file from being judged.
- The comparison before and after the change runs the same test file on both sides, and the
  check assumes that the file names its tests alike there, as it does the file as it was on the
  code before and after the change. The reports cannot check that assumption. Where the rows of
  a parameterized test come from the production code (an enum, a list in the code), the change
  can give another number or order of rows on each side, and rows named by their place are then
  compared with other rows. A row the change of the code takes away is not counted against the
  change of the tests where the file as it was runs on the code after the change (above); where
  it does not, it is (the line says why), and with the test file untouched the file is not run at
  all.
- In the fourth run only the edited test files that it needs go back to how they were, those of
  one runner together (as they ran together as they were), in a checkout of that runner's own.
  Fixtures, helpers, build files, other runners' test files and the other changed test files
  are as the change has them. A test renamed and not otherwise edited therefore runs there
  exactly as under its new name after the change, and is never accused for the rename. A test the change renamed and rewrote, or whose fixture it rewrote, is
  run as it was against what the change wrote: when it fails there and the file has no test
  that could be its rewrite (one that passes after the change and fails before it, or could not
  be judged), the check fails, though the behaviour may have changed on purpose; otherwise it
  could not be judged. A test kept under its own name and edited could not be judged when its
  old version fails there. To change what a test expects on purpose, change it under its own
  name.
- Each test file is judged by its own counts. A test moved to another changed file is gone from
  its file: a test that ran and does not run there, unless that file runs as many tests as passed
  before. In the other file it is a new test. This comes from judging files one by one, not from the reports.
- When the test file as it was does not compile or load on the change (the change renamed what
  it used), or gives no result, or does not report an old test as passed or failed, the fourth
  run tells nothing of those old tests: the file is said to be one that could not be judged,
  with why, and whether the change broke a test it took out or replaced is not known.
- A file whose tests were all taken out. For Maven the file runs and reports none (a build that
  passes with no report for the class; a build that fails, or no build tool, is not read as "no
  tests"). A JUnit 5 class that stops in its set-up on an assumption reports none too; either
  way every test that passed in it as it was does not run now, and that is said. Vitest reports
  such a file, and a `describe` block without tests, as a failed case ("No test found in suite
  ..."), which is listed as failing after the change: a report writes it exactly as a test's
  own failure, so it is not left out.
- A file whose report as it was skipped a case it does not skip as often after the change is not
  said to have new tests that pass on both sides: the skipped case may stand for any number of
  tests. A test that passed before and is skipped now, alone or with its class, is still counted
  as a test that ran and does not run now.
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
  file. Integrity finds it without running anything, which is what `check-pr`, `--no-run` and
  files no runner owns rely on. Where a runner ran the file, the run (above) is the judge; a
  test switched off is then said twice, as a test that ran and does not run now under the test
  results and as switched off under Integrity.
- Integrity reads no test file for its tests, in any language. It lints the lines the change
  adds to test files, each line read twice: with its comments dropped and the insides of its
  strings emptied, and as it is written, unless the whole line is a comment (it starts with
  `//`, or with a `#` that no name follows). One line cannot tell its quotes for certain (a
  quote inside a regular expression, a string begun on a line above), so what a line shows as
  written is never hidden. The cost: a switch's words inside a string, a text block, a trailing
  comment or a block comment's line are reported too, and `check-pr` shows the line. A switch
  that the change writes over two or three lines (`it`, then `.skip(` on the next; `@`, then
  `Disabled`) is found too, each line read both ways.
- A switch on an added line is reported as that line, whatever the change did with the test: a
  skipped test that is moved, given another title or edited on the line of its switch is
  reported as switched off. When overloads of a method change places, git shows one of them
  moved: if that is the one switched off, its switch is on an added line and is reported; if it
  is the other, nothing is.
- Not seen by the lint: a switch of which the change wrote only a part (`.skip(` under an `it`
  that was there) or one written over more than three lines; a switch reached through another
  name (`const later = it.skip`, then `later('x', ...)`; a custom annotation that carries
  `@Disabled`; an assumption whose condition is computed, `assumeTrue(ready())`); a test moved
  into a class or a `describe` block that is already switched off; a test skipped from inside its
  body. For a file a runner owns (Maven Surefire, Vitest), the run says how many tests that ran
  before the change do not run now, whatever the source says; for a file no runner owns, and in
  `check-pr`, which runs no test, nothing does.
- Reported as switched off, on the lines added to any test file: `@Disabled`, `@DisabledIf...`,
  `@Ignore` (also qualified, `@org.junit.jupiter.api.Disabled`, and with white space after the
  `@` or around a dot), an assumption that always fails (`assumeTrue` with a plain `false` among
  its arguments, `assumeFalse` with a plain `true`), `Assumptions.abort` (also with type
  arguments, `Assumptions.<Void>abort`), the line that imports it statically, and `abort(` called
  alone (another object's `controller.abort()` is not); `skip`, `todo`, `only`, `fixme`,
  `xit`/`xdescribe`, `fit`/`fdescribe` (also as a member of a longer call: `it.concurrent.skip`,
  `test.describe.skip`), `@pytest.mark.skip` and its forms, `@unittest.skip` and its forms,
  `t.Skip`. Not reported: `it.skipIf`, `it.runIf`, `@DisabledOnOs`, `@EnabledIf...` and other
  conditions.
- Which tests a file holds is not read from its source. A test removed, renamed or moved is
  said by the run when a runner owns the file, and by nothing otherwise (nor by `check-pr`). A
  deleted test file is read by the lines the change removes: it is a problem when it is named
  like a test file, or one of its lines calls `it`, `test` or `describe`, defines a test function
  or carries a JUnit test annotation (`@Test`, `@ParameterizedTest`, ...); otherwise it is a note
  (a fixture or a helper). A test file deleted and added elsewhere with exactly the same lines is
  a note; one edited on the way, if only in its package line, is a problem.
- "Fewer assertions" is a note and a plain count: the calls that look like assertions on the
  lines an edit of a Java or script test file removes, against those on the lines it adds.
- Test files are never read whole, so their size does not matter. Only build and configuration
  files are read whole (a pom, `package.json`, a coverage configuration); one larger than 2 MB
  is not read, and is a problem ("too large to scan").
- In a pull request build the base branch must be fetched, with enough history to reach the
  commit the branch started from. Otherwise the check stops with an error.
