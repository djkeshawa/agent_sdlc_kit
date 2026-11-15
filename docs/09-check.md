# 9. `sdlc check`: the change in front of you

`sdlc check` needs no configuration, no account and no tracker. It works in any git repository,
with or without the rest of the kit, and it writes nothing to your working tree or index (but for
Vitest's own cache in the ignored `node_modules`: see [Which tests it can run](#which-tests-it-can-run)).

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
| **Tests this change adds or edits** | Each such test is run after the change and before it. "Before" is the commit your branch started from, with only the change's test files laid over it. A test file the change edits is run a third time as it was, on that commit, to learn which tests it had; so is a test file it only moved, at the path it had (see [Moved test files](#moved-test-files)). When the list of tests such a file reports changed (a name it did not report, one it no longer reports, or a name with more or fewer of its tests skipped), that file is run a fourth time as it was, on the code after the change |
| **Integrity** | What the diff does to the things that check the change: tests switched off (the usual ways of writing a switch, linted from the lines the change adds to test files: a helper, not a guard, see Limits), test files deleted, secrets, changes to the build, the pipeline, dependencies and migrations. Nothing is run for it, so it is the early warning where no test runs |
| **Not checked** | What this run did not look at, so silence is never read as approval |

## How to read the test results

| Result | Meaning |
| --- | --- |
| Fails before, passes after | The test is sensitive to the change. It is run three times on each side, so a test that fails at random does not count |
| New test passes before and after | It passes whether or not the change is there, so it does not test the change. In a file the change adds, each such test is named, where it passes on both sides in every run. In an edited file the file is named, "at least N", with the names the file reports for the first time; those names need not be the new tests (see below). Said of an edited file only where the run of the file as it was lists every test (see below), and only where every combination of the runs made counts at least one: N is then the fewest. This fails the check |
| Fails after the change | The change's own test fails, in any run after the change; the line says in how many where it is not all of them. A test the build ran again that failed every time fails (Surefire's `rerunFailingTestsCount`, a test's or the configuration's `retry` in Vitest). So does a test file whose code does not compile or load after the change, in the first run or any repeat, said of the file with in how many runs; a run that gives no result for a reason outside the code (no build tool, a timeout) is not a failure, and is said as differing from run to run. This fails the check |
| Tests that ran before the change do not run now | The edited file runs fewer tests after the change than passed in it as it was: at least N tests that ran do not run now, whatever became of them (deleted, skipped, hidden in a skipped class or an empty report, renamed while switched off). Said of the file, "at least N", with what the reports show: the names it no longer reports, the names skipped now, the names it reports for the first time; the names do not say which tests, and the line does not say whether the change of the tests or of the code took them. Must hold in every run. Not said where the file as it was reported a test that failed or threw, nor where the old file runs no more tests on the code after the change than the new one (the change of the code may have taken them), nor where only tests that passed when the build ran them again make the count (see below). This fails the check |
| Passed before, fails as it was after, taken out | An old test that passed before the change, run as the file was on the code after the change, fails, in every run; in every run of the file after the change that the check made, the first and every repeat, the file does not report its name or skips it, and no test of the file could be it rewritten in any of them (see below). This fails the check |
| Could not be judged | Before the change the test did not compile, threw something other than a failed assertion, or was not found. Or the file as it was could not be run (it did not compile, timed out, or no build tool), so it cannot be told whether a test is new. Or the reports fit two stories, one of which is fine: a rename and a test deleted and another written; a test switched off and rows that changed places; a new test and an old one under a new name; an old test edited, or taken out and its name given to another. Or a report of the file may not list every test (see below). Or the file as it was could not be run on the code after the change when the fourth run asked for it, or did not report an old test there as passed or failed: the file gets a line of its own saying so, whatever else is said of it. Or the reports of the file as it was and after the change differ in a way no rule judged (see below). Or a test file had to be run alone before the change and the test passes after it only beside the other changed test files, or an old test fails on the change as it was only beside the other edited test files as they were (see the fourth run). Or a run did not say which tests passed at the first try (see [Which tests it can run](#which-tests-it-can-run)): the file is then judged by its failures alone. Each such line says what the reports show and which stories they cannot tell apart, or why nothing is counted. It never fails the check |
| Different results from run to run | Unstable. Not counted as sensitive. A test that passed only when the build ran it again (Surefire's `rerunFailingTestsCount`, Vitest's `retry`) is unstable. A test file whose runs say different things about how many tests that ran do not run now, or which tests are new or switched off, is unstable too, and so is an old test the change breaks that is gone or skipped in one run after the change and runs in another, or that fails as it was on the change in one run and not in another, and so is any test a run reported otherwise than another where no rule judged it (see below) |
| Skipped | A test skipped in a file the change adds, or in a file none of whose tests ran before the change. Listed |
| Already in the file, passes on both | A test of a name the file reported before the change, passing on both sides in every run. Not counted. The same for a test skipped under the same name before and after the change, in every run |
| Test file moved | A test file the change deleted and added elsewhere, moved by the rule of the text check (see [Moved test files](#moved-test-files)), and run before the change by the runner that runs it now. Its lines are the lines it had; whether it has tests it did not have, its counts say. Said of the file, "moved from" the path it had, with how its lines are the same, and that it reports as many tests as it had where every run says so. Not counted |

### Moved test files

git sees a test file that is moved or renamed as one file deleted and one added. The check takes
an added file for a deleted test file (named or placed like one, or under `paths.test`) moved by
one rule, the text check's (Integrity), so that both
parts of the tool agree on which pairs are moves: the added file has exactly the deleted file's
lines; or, both being JVM sources (`.java`, `.kt`, `.groovy`, `.scala`), its lines but its
`package` line (a class moved to another package); or its lines but its `package` and `import`
lines. Nothing else is a move: no measure of how alike two files are, and not git's rename
detection. Each added file is the move of one deleted file at most, and the other way round,
exactly the same lines first, then the package line, then the imports, in the order of the
paths: of two deleted copies and one added, one moved and the other is a deleted file; of one
deleted and two added copies, one is the moved file and the other a new file. For the run the
pair is a move only where the runner that runs the added file after the change runs the deleted
one before it; otherwise the added file is judged as any file the change adds.

A moved file is run as it was where it was, at its old path on the code before the change; the
runs before and after the change run it at its new path, as any added file. What is judged:

| What | How |
| --- | --- |
| New tests | By the counts, as for an edited file, with the number of tests the file reported as it was (T) and after the change in place of the names it no longer has and the names it did not have: its lines are the lines it had, but a source of rows (in the code, or in what it imports) can give it more. Where it reports as many as it had or fewer, none is new by count, and a test that passes on both sides is a test it had, not counted. Where it reports more, at least (more - T) - X new tests pass on both sides, X being its tests after the change that do not pass on both sides: said of the file and failing the check, as for any new test that passes on both sides; where that is 0, or the report as it was may not list every test (a failure, a case run again, a skipped case, no case: see below), whether a new test passes on both sides could not be judged. The names do not say which tests are new, as they changed with the move |
| A failure after the change | Fails, as for any file |
| Fails before, passes after | Sensitive, as for any file: the runs before and after the change both run it at its new path, so its tests are named alike there |
| Tests that ran before the change do not run now | By the counts, as for an edited file: the file as it was passed R0 tests, after the change it runs R1; fewer is said and fails. An import the move changed can switch tests off. There is no fourth run (the old file has the same lines), so the line says that whether the move or the change of the code took them is not known. Where the file as it was gave no result, this could not be judged; where it reported a failure, fewer tests running could not be judged, as for an edited file |
| Skipped | Listed, as in a file the change adds |
| Cases gone that did not pass | Where it reports fewer tests than it had and as many run as passed as it was, the cases gone were skipped, failed or passed only when run again as it was: could not be judged, as for an edited file |
| By names, between the file as it was and after the change | Nothing: the names a runner reports change with the path or the package (a Vitest suite is the file's path, a JUnit class name holds its package), so they are never compared across the move. Its "moved" line answers for the differences of names the move makes, and for nothing else: where the file reports as many tests as it had, every name it no longer reports and every name it did not report; where it reports more, only the names it no longer reports (the new ones are the count's); where fewer, only the names it did not report. An outcome that differs under a name both sides report, and what differs from run to run, is said by its own line or as could not be judged |

Limits of this: a file moved and edited on the way, a test changed or one line more, is no move:
the deleted file and the added one are judged as such (the deletion is a problem where the file
is named like a test file or declares tests, and the added file's tests are new). For a script file (`.ts`, `.js`, ...) only exactly the same lines
make a move: a spec moved to another folder with its relative import adjusted
(`'./prod.js'` to `'../prod.js'`) is a deleted file and an added one. Where a JVM file's imports
changed, its run judges how many of its tests run and whether they pass; an import that makes a
test test less while it still passes (a stub in place of the class it tested) is not seen by any
run, and the moved file's line says so. Its tests are compared with the file as it was by counts
only: where it reports as many tests as it had, a test that an import or a source of rows replaced
by another that passes on both sides is not seen. A move is a test file that becomes a
test file of the same kind: a test file renamed to a path that is no test file, or to another
extension (`sum.test.ts` to `sum.txt`), has not moved, and its deletion is a problem. A test file
moved to where no runner of the check runs it, while a runner ran it where it was, fails the check:
the file is run once as it was, and the tests it passed there ran before the change and do not run
now.

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

Every verdict is made after the last run. A test's verdicts, the counts of tests that do not run
now and of new tests, and the old tests the change broke are made from every report the check made,
each repeat of each way (`--runs`); what the skips and the names gone say is read from the first
runs and compared with every repeat of a file that was run again. A test is read in every
combination of the runs: each run of the file as it was, with each run before the change, with each
run after it. A test that fails in any run after the change fails, and so does a file that does not
compile or load in any run after the change. Any other verdict of a test is said only where every
combination says the same; else it is unstable (a test that looked sensitive in the first runs) or
left to the rule below. Not every way is run again: the run before the change is repeated only for
a file with a test that looks sensitive, and the run of the file as it was only for a file whose
skips or counts say something (see the fourth run, below), so "every run" means every run the check
made, which the report counts.

The order in which a report lists its tests is never read. Surefire writes a report's tests in the
order they finish, which differs from run to run where tests run concurrently, so every report is
put in one order as it is read (by class, by name, then by result), runs are compared by counts and
names, never by the words of a line, and every list of names the check prints is in that order.

Every difference gets an answer. Apart from the rules below, the check lists every way a file's
reports differ: a test the file as it was reported and the file after the change does not, or the
other way, a test switched off or on, a test with another result (passed, passed only when run
again, failed, threw, skipped) before and after the change, and a test one run of a way reported
otherwise than another run of the same way. Each rule of the check says which of these its lines
answer for. Two lines answer for the whole file, as the file must be looked at anyway and both fail
the check: a file whose code does not compile or load in the first run after the change (there is
no report after the change to compare), and the definite line of tests that ran and do not run
now. Every other line answers only for the tests it names, or for those whose counts make the count
it speaks of, so another difference beside it is said all the same: a test that fails after the
change, a new test that passes on both sides, an old test taken out of the way, a line that could
not be judged and a line that says the file differs from run to run. What no line answers for is printed all the same, one line for the
file: "could not be judged" for a difference between the file as it was and after the change, with
the names and what each report said, and "different results from run to run" for one between runs.
So a case that falls between two rules, or a repeat whose report no rule read, is said, not passed.
These lines never fail the check. A test that passes in every run, before and after the change, is
no difference.

Tests that ran and do not run now. For each edited file, R0 is how many of its tests passed in
the run of the file as it was, at the first try, and R1 how many run after the change: every test
the report lists that is not skipped, whatever its result. Each test that passed is one test that
ran (a failing set-up, container or class is never reported as passed), and a test hidden in a
skipped case or behind an empty report does not run. So at least N = R0 - R1 tests that ran before
the change do not run now, whatever is skipped, hidden, renamed or emptied, under any naming, and
without a list of the tests after the change. Whether they were deleted or switched off does not
matter: they ran, and they do not run now. A test that fails after the change counts in R1: it is
said as failing, not again as a test that does not run. That is all the line claims: counting
does not say which change, of the tests or of the code, took them.

A test that failed and passed when the build ran it again (Surefire's `rerunFailingTestsCount`,
a `flakyFailure` in the report; a test with a `retry` in Vitest, as Vitest itself counts it) is not
counted in R0: a set-up that failed and passed when run
again may be reported the same way, and it is not a test. Where only such tests make the count,
it could not be judged, with why. So a flaky test switched off is said, but not as a failure.

Whether the line fails the check. Where the rows of a parameterized test come from the production
code (an enum, a list in the code), a change of the code can take rows away, and that is ordinary
work. So the file as it was is also run on the code after the change (the fourth run, below), and
R4 is how many of its tests run there: the cases it reports that are not skipped, less those that
passed only when the build ran them again, as for R0. On that code, a change of the tests that took
nothing out runs every test the file as it was runs: R1 is at least R4. Every usable report of the fourth run counts. Where R4 > R1 in each, the
change of the tests itself runs fewer tests on the same code, and the line fails the check; where
R4 is no more than R1 in any one of them, the change of the code may have taken all of them, and
the line could not be judged, whatever the other runs of the fourth way gave. This is counting, not
names: a row the code took and one the tests dropped are not told apart, and the line does not try.
A fourth run that gives no usable report (the old file does not compile or load on the code after
the change, it times out, or a failure stands in place of its tests) says nothing of R4: beside
usable ones it is said apart, in the definite line (in how many runs, and why) or from run to run.
Where no fourth run gives a usable report at all, the line fails the check all the same and says
that the file as it was gave no report usable for this count on the code after the change, with
why (it did not compile or load, it timed out, or it reported a failure that is not one of its old
tests, such as a tear-down that failed after the tests ran), so whether the change of the tests or
of the code took them is not known. This is a choice: that tests that ran do not run now
is true either way, and a person must look.

| In the file | Result |
| --- | --- |
| N > 0 in every run (each run as it was against each run after the change), and R4 > R1 in every usable fourth run, or no usable fourth run at all | At least N tests that ran before the change do not run now. Said of the file, with the counts and what the reports show, and with a fourth run that gave no usable report beside usable ones. Fails |
| N > 0 in every run, and R4 <= R1 in some usable fourth run, whatever the others gave | Could not be judged: the change of the code may have taken them. Said once: by the fourth run's line where it did not report some old tests, else by a line of its own. A fourth run that gave no result beside it is said from run to run |
| N > 0 in every run only with the tests that passed when the build ran them again | Could not be judged, with why |
| Fewer run than passed as it was in some runs only | Unstable |
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
against each report after the change, and the answers are compared by what each rests on, never by
its words: its kind, its counts and which story it tells, which names (with their class) ran before
and are skipped now and how many of each, which are skipped under a name the file did not report,
and how many cases are gone and new. An answer that differs from run to run is unstable, also where
the first run's skips said nothing.

New tests that pass on both sides. A file that reports F new names and no longer reports G has
at least F - G tests it did not have. If X of its tests do not pass on both sides (they fail
before, fail after, could not be judged, or are skipped where a skip may hide a test), at least
N = F - G - X of the new tests pass on both sides. F, G, X, the skips and whether the report as it
was lists every test are counted again in every combination of the runs made; N is said where it
is above 0 in every one (the fewest), and is unstable where it is in some only:

| In the file | Result |
| --- | --- |
| N = 0, or the run of the file as it was may not list every test (see below) | Each test under a new name that passes on both sides could not be judged: it may be an old test under a new name, moved on by a row put in before it, or one the old report did not list |
| N > 0 | The file is named: at least N new tests do not test the change. The names under which it reports tests for the first time and that pass on both sides are listed, but they need not be the new tests: a row put in before the others, `[a, b]` to `[c, a, b]`, puts `c` under a name the file had and `b` under a new one. Fails |

So a fix that adds one sensitive test is sensitive; one that adds a sensitive test and one that
passes on both sides fails for the second; a row put in among the arguments that is itself
sensitive does not fail the check, though the last row has a new name.

A file whose counts say tests that ran do not run now, that new tests pass on both sides, or
whose skips may hide a test switched off, is run again as it was and after the change with the
repeat runs, and a file with a test that looks sensitive is run again before and after the
change; what is said must hold in every run made. A file that is not run again is judged from the
one run of it on each side.

The fourth run, for each edited file whose list of tests changed in any way (which every file
that runs fewer tests than passed as it was is): every old test of the file that passed as it was
is run as it was on the code after the change. How many of them run there is R4 (above). One that fails or
throws there, in every run, failed on what the change wrote; what became of it decides:

| The old test | Result |
| --- | --- |
| Its name is gone or skipped now, in every run of the file after the change that the check made (B such tests), and the file has M tests that pass after the change and do not pass on both sides, in the run that has the most (any of which could be one of them rewritten for the changed behaviour) | B > M: at least B - M were taken out of the way, by name when M = 0 (the name is the old file's own, run on both codes). Fails. B <= M: could not be judged, as the old tests may have been rewritten under new names |
| Its name still runs after the change | Could not be judged: it may have been edited for the changed behaviour, or taken out and its name given to the next row. Where the file runs fewer tests than passed before, the check already fails for that |
| Its name is gone or skipped in some runs after the change only, a run after the change gave no result, or it fails as it was in some runs only | Unstable |
| It fails only beside the other edited test files as they were: where more than one file went back, its file alone is put back on the tree after the change, and there it does not fail | Could not be judged: what made it fail is the change of those other test files (an old helper against the new code), which is part of the change |

An old test said to be taken out of the way is one of the tests that ran and do not run now, so
it is not counted again there: that count is said only beyond the old tests already said.

The old test files of one runner that the fourth run needs go back together, so an old test runs
there beside the other edited test files as they were. Where more than one went back, a file with
an old test that fails there and is not run after the change is run once more, alone: the tree
after the change with only that file as it was (one run more for each such file). An old test is
said to be taken out of the way only where it fails there too.

A report that may not list every test. Some counts take a report for the whole list of the
file's tests, one case for each. A case that passed is one test that ran; the others need not
be. A case that failed or threw may stand for a fault outside a test's body: JUnit 4 reports a
class it cannot set up as one case, `initializationError`, in place of its tests, and Vitest
reports a failed `afterAll` as a case of its own. So may a case the build ran again: one that
failed every time is a failure, and one that passed only when run again may be a set-up that
failed and was run again (in Vitest only a test is run again; it is read the same way). A skipped case may stand for any number of
tests: JUnit 4 reports an ignored class as one skipped case, and Surefire a disabled JUnit 5
factory or template as one, whose tests it lists one by one once they run. And a report with
no case at all may come from a JUnit 5 class that stops in its set-up on an assumption. Which
case stands for what a report does not say, and its name is not read to guess it. So a report
is taken for the whole list only where it reports at least one test, holds no test that failed
or threw and none the build ran again, and each case it skips is skipped under its name, as many times, in the other report
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
| `--runs N` | Runs per side for a test that looks sensitive, and as it was and after the change for an edited file whose skips or counts say something (default 3) |
| `--timeout S` | Seconds one test run may take (default 900) |
| `--no-run` | Read the diff only; run no tests |
| `--since 30d` | Replay the check over each recent commit of the branch (`12h`, `2w`, `3m` or a date). `--max N` sets how many (default 20). Per commit: the counts, the line of each verdict about a file that fails a change, the line of a moved test file, and the text check's problems and notes. A report on the past: it never fails |
| `--stop-hook` | For an agent's Stop hook; see below |

Exit code: 0 when nothing is wrong, 1 when a changed test fails, a new test passes on both sides,
tests that ran before the change do not run now, a test the change broke was taken out of the way,
or the integrity scan finds a problem, 3 on a usage error. "Could not be
judged" and notes never fail.

## Which tests it can run

| Tests | How | What it needs |
| --- | --- | --- |
| Java with Maven | Surefire, through `./mvnw` when the repository has it, else `mvn`. In a multi-module build it runs the module of each test with the modules it needs | A JDK and Maven, as for your normal build |
| JavaScript and TypeScript with Vitest | `vitest run` with its JUnit reporter, and a reporter of the check's own beside it (below) | Node.js, and `npm install` already done in the repository |

Test files of other kinds are listed under "Not checked". Integration tests run by Maven
Failsafe are not run.

Both sides run in throw-away copies outside the repository, written by git. For Vitest the
copies link to the repository's `node_modules` folders. Running a test runs the repository's
code, with your user's rights, as your normal test run does.

Vitest's JUnit report writes a test that failed and then passed when Vitest ran it again (the
`retry` of the test, of its `describe` block or of the configuration; a test's own wins over the
configuration and the command line) as an ordinary pass. So beside the JUnit reporter the check
runs a small reporter of its own: a fixed text, written to a temporary folder outside the copies,
that writes for each test its file, its name as the JUnit reporter writes it, its state and how
many times Vitest ran it again (`result.retryCount`), to a file in that folder. It runs in Vitest's
own process, which runs your configuration and tests already, so it can do nothing they cannot;
what it writes is read as untrusted input (a regular file, at most 50 MB, strict JSON in its shape;
nothing in it is used as a path). Its names are compared with the JUnit report's as that report
gives them once read: Vitest writes a name into an XML attribute and takes out what XML 1.0 does
not allow or discourages (control characters, a lone surrogate, and with a lone low surrogate the
character before it), and reading an attribute makes each line end and tab a space. The check
makes the same change to each name before it compares, so a title with a line break
(`test.each(['a\nb'])`) is matched. Two titles that come out alike are one name, which is not told
apart (see Limits). A title Vitest writes so that its report is not XML at all (an `&` or a `<`
just before a lone low surrogate) leaves the run without a report: could not be judged, with why.
A test Vitest ran again is then read as Surefire's
reruns are. A test the JUnit report lists without a mark that Vitest did not run (after a
`bail`) is read as skipped. Where a run does not say which tests passed at the first try (the
reporter's file is missing or cannot be read, it does not name a test the JUnit report lists as
passed or names more tests of a name than the report lists, or a version of Vitest gives a test
retries and does not count them), the file is judged by
its failures alone: one line, could not be judged, with why, and every difference among its reports
said beside it. Vitest keeps a cache of its own (the results of earlier runs, by which it orders the
test files) under `node_modules/.vite` of the folder it runs in, which for the copies is the
repository's own `node_modules`: the check does not turn it off, so that folder, which git ignores,
is written to, and the runs of every side share that cache. It decides only the order of the files.

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
  unstable, or as failing where it fails in one of the runs after the change.
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
  all. Where the code takes rows and the change of the tests drops others, the line says only how
  many tests that ran do not run now, not which change took which.
- With Surefire's `rerunFailingTestsCount`, or a `retry` in Vitest, a test that passed only when
  run again is not counted as a test that ran before the change, and it makes the run of the file
  as it was a report that may not list every test: a flaky test switched off could not be judged,
  not a failure. Vitest's retries are learnt from Vitest itself (see
  [Which tests it can run](#which-tests-it-can-run)); a file of which a run does not say them is
  judged by its failures alone, so a test that passes on both sides, a test lost or an old test
  taken out of the way is not said of it: could not be judged.
- In the fourth run only the edited test files that it needs go back to how they were, those of
  one runner together (as they ran together as they were), in a checkout of that runner's own.
  Fixtures, helpers, build files, other runners' test files and the changed test files that the
  run does not need are as the change has them. The edited test files it needs go back together,
  so an old test there runs beside the other old ones: a helper in another edited test file is
  the old one. An old test that fails there and is not run after the change is therefore run once
  more with its own file alone put back (where more than one went back), and it is accused only
  where it fails there too. So a test renamed and not otherwise edited is judged by a run exactly
  as under its new name after the change, and is not accused for the rename; where it fails only
  beside the old helpers it could not be judged. A test the change renamed and rewrote, or whose fixture it rewrote, is
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
- A test file that is moved or renamed (git sees one file deleted and one added) is judged as the
  file it was only where its lines are the same (see [Moved test files](#moved-test-files));
  moved and edited on the way, it has no earlier version under its new name: all its tests
  count as new, and the deleted file is a test file deleted.
- A test whose name changes from run to run (a time or a random value in it) looks new each time.
- When one new test stops the others from compiling before the change, each changed test file
  is run on its own there, beside the other changed test files as the base has them (or without
  them where they are new), and once on its own after the change too (one run more for each such
  file). A test that passes after the change only beside the other changed test files, and not
  on its own, is not compared with its run before: what it did there may come from those files,
  not from the change. It could not be judged: never sensitive, never a test that passes on both
  sides. A test that passes on its own on both sides is judged as any other, though the other
  changed test files it ran beside before the change were the base's.
  For Maven the compiled tests of the module are removed before each such run, so a test file
  that was taken away is not still there as a class.
- For Maven only the class named after the test file is run (`-Dtest=` that class, with its
  nested classes): tests in another top-level class of the same `.java` file are not run or
  counted, so a test added there is not seen, and a file whose tests are all in such a class
  "ran after the change and reports no tests" (could not be judged).
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
  file. Integrity looks for the usual ways of writing one on the lines the change adds, without
  running anything: a helper that catches mistakes and plain attempts early, in commands that run
  no test (`check-pr`, `--no-run`) and for files no runner owns. **It is not a guard.** A check
  that reads text cannot stop an author who sets out to hide a switch from it: a switch reached
  through another name (`later('x', ...)` after `const later = it.skip`), a quote in a regular
  expression before a comment written between a switch's words, a switch spread over more than
  three lines or one on a line longer than 2,000 characters is not seen. The guard is the run: `sdlc check` reports
  every test that ran before the change and does not run now, whatever the source says. A
  pipeline that must be sure runs `sdlc check`, not only `check-pr`. Where a runner ran the file,
  a test switched off is said twice, as a test that ran and does not run now under the test
  results and as switched off under Integrity.
- The lint says what it finds at one of two levels. It blocks only what a line shows for sure:
  a switch whose whole meaning stands in what it read, with no condition possible. Anything else
  in which it sees a switch's words is a note in `sdlc check` and a warning in `check-pr`, never a
  failure: whether it is a switch, or whether a condition holds it, needs more than the added
  lines hold, and the run of the file says whether a test stopped running.
- No word, prefix or part of a line makes a switch sure by itself. A form is sure only when the
  lint has read all of what it claims, **in the text as written** (see the two readings below):
  - **the argument list is complete** where the form depends on it: its closing bracket is on the
    line, or on the two or three lines read together (a title needs only its own argument to end);
  - **a literal is the whole argument**: `true`, `false`, `True`, `False` or `"true"` with nothing
    else in the argument (`enabled = false || RUN` is computed, not `false`);
  - **a title is a string for certain**: a string, or a template whose `${...}` close inside it,
    alone or followed by `+` and names, calls or more strings, with no other operator (`'adds ' +
    n` is a title; `'a' + b === c` is a condition);
  - **an option is a top-level property** of the object that is a whole argument of the test
    declaration, with a whole `true`, and no spread or property it cannot read after it
    (`{ ...opts, skip: true }` is sure; `{ skip: true, ...opts }` is a note; `{ meta: { skip: true } }`
    is nothing);
  - **a modifier is the callee of the call that registers a test**: `it.skip('x', ...)`, or
    `.each(table)` and then `('x', ...)`; one that is only named (`const each = isWindows ?
    test.skip.each : test.each`) is a note;
  - **a decorator or an annotation is the whole expression**: after it comes the end of the line,
    a comment, another annotation or decorator, or the declaration (`@(pytest.mark.skip if IS_CI
    else identity)` is a note; so is `"@Disabled"` in a string);
  - **it stands on its own**: an annotation after nothing on its statement but complete
    annotations and modifiers (`@Test @Disabled`, `public @Disabled`, `@Tag("a") @Disabled`); a
    decorator at the start of its line, after decorators only; a declaration, an option, an
    assumption, `pytestmark` or a pending test at the start of its statement (the line's start,
    or after `;`, `{`, `}` or the end of a comment), not an argument, a value or a lambda's body
    (`f(it.skip('x', ...))`, `x = @Disabled`, `() -> assumeTrue(false)`, `assertThrows(...,
    () -> assumeTrue(false))` are notes); `pytest.param(..., marks=...)` at the start of its line,
    or after `[`, a comma or a `(` that opens no call (an element of a list or a tuple); an
    assumption also ends its statement (`;`, `}`, a comment or the line's end after its closing
    parenthesis);
  - and, for a declaration, an option, an assumption, `pytestmark` or `pytest.param`, **no
    condition stands before it on its line**, nor on the added line just before the line or lines
    it is read from, when that line opens a block or a statement under a condition or ends where
    an expression goes on (an operator such as `?`, `&&`, `||`, `=`, `+`, `=>` or `->`, a
    backslash, or a word such as `else`, `return` or `and`): `if (ci) it.skip('x', ...)`, or
    `if (!db) {` and then `describe.skip(...)` on one, two or three lines, is a note. A line that
    ends with an open bracket or a comma holds a declaration, an option, an assumption or
    `pytestmark` after it (it is an argument or an element there: `wrap(`, then `it.skip('x',
    ...)`), but not a `pytest.param` (an element of a parametrize list). Annotations
    and decorators take no condition: Java has no conditional annotation, and a Python function
    defined under an `if` is skipped wherever it is defined.
- Sure, a problem ("test switched off", "test focused"; `check-pr` fails), on the lines added to
  any test file, each as just described:
  - `@Disabled`, `@Ignore` (also qualified, `@org.junit.jupiter.api.Disabled`, and after other
    annotations or modifiers, `@Test @Disabled void off()`, `public @Disabled void off()`),
    TestNG's `@Test(enabled = false)`, `@DisabledIf("true")`, `@EnabledIf("false")` (also as
    `value =` or `expression =`);
  - `@pytest.mark.skip` (bare or called, also in parentheses, as `pytestmark = ...` or as the
    `marks=` of `pytest.param`, alone or in a list); `skipif` with a plain `True` among its conditions or with none, also bare
    (`@pytest.mark.skipif`); pytest's rule is followed: when `condition=` is given it is the only
    condition and the positional ones are not read, otherwise every positional condition is, and
    any that holds skips (so `skipif(False, True, ...)` is sure and `skipif(True, condition=False)`
    is nothing); `xfail(run=False)` with no condition or a plain `True`; `@unittest.skip`,
    `skipIf(True, ...)`, `skipUnless(False, ...)`, also by keyword (`skipIf(condition=True, ...)`);
  - a declaration with its title: `skip`, `todo`, `only` or `fixme` on `it`, `test`, `describe`,
    `context`, `suite` or `specify` (`it.skip('x', ...)`, `describe.only('x', ...)`,
    `test.todo('x')`, `test.fixme('x', ...)`; also `it.concurrent.skip`, `it?.skip`,
    `test.describe.skip`), and after `.each` (`it.skip.each([...])('x', ...)`, also with a tagged
    table); `xit`/`xtest`/`xdescribe`/`xcontext`/`xspecify`, `fdescribe` and `fit` with a title
    (also with `.each`); `it.skipIf(true)('x', ...)` and `runIf(false)('x', ...)`; an option object
    that says `skip`, `only` or `todo: true` (`test('x', { skip: true }, fn)`); a test declared
    without a body, its whole declaration on one line (`it('pending');`);
  - `assumeTrue` with a plain `false` as an argument of its own (`assumeTrue(false, () -> "later")`),
    or `assumeFalse` with a plain `true`.
- A note ("may switch a test off", "may focus a test"; a warning in `check-pr`), every other place
  where the lint sees a switch's words:
  - a skip from inside a test, which a condition on another line may hold: `pytest.skip(...)`,
    `self.skipTest(...)`, `raise unittest.SkipTest(...)`, Mocha's `this.skip()`, `t.Skip`,
    `t.Skipf`, `t.SkipNow`, Jasmine's `pending('reason')`, Playwright's `test.skip()`,
    `test.skip(true, ...)` and `test.fixme(true)`;
  - a modifier whose first argument is computed: Playwright's condition
    (`test.skip(browserName === 'webkit', 'reason')`, `test.fixme(isMobile, REASON)`) or a
    computed title (`it.skip(name, ...)`, `xit(name, ...)`, `test(name, { skip: true }, fn)`);
    one that is not called (`const later = it.skip`, `cond ? describe : describe.skip`,
    `const each = isWindows ? test.skip.each : test.each`);
  - a condition that is computed: `skipif(sys.platform == "win32", ...)`, `skipIf(IS_WINDOWS,
    ...)`, `xfail(run=RUN_SLOW)`, `@Test(enabled = RUN)`, `@DisabledIf("isWindows")`,
    `it.skipIf(isWindows)`; an option whose value is computed or that a spread or a quoted key
    after it may change (`{ skip: true, ...opts }`, `{ skip: true, 'skip': false }`);
  - a mark or an annotation that is not the whole expression: inside a larger decorator or value
    (`@(pytest.mark.skip if IS_CI else identity)`), kept in a variable or passed to a call
    (`skip_slow = pytest.mark.skip(...)`, `item.add_marker(pytest.mark.skip(...))`), or in a string;
  - a sure form that does not stand on its own: an argument, a value or a lambda's body
    (`f(it.skip('x', ...))`, `x = @Disabled`, `Runnable later = () -> assumeTrue(false)`,
    `assertThrows(TestAbortedException.class, () -> assumeTrue(false))`), or after a word or a
    quote on its statement (`see @Disabled`, `"it.skip('a', ...)"` in a string);
  - a sure form after a condition on its line (`if (x) assumeTrue(false)`, `cond && it.skip('x',
    ...)`; the text as written decides, so a condition's word in a string or a comment before it
    counts too: `describe('when signed in', () => { it.skip('x', ...) })` on one line is a note), or
    on the line after an added line that holds it (`if (!db) {`, then `describe.skip('db', ...)`,
    on one line or over two or three; `except ImportError:`, then `pytestmark =
    pytest.mark.skip`; `Runnable later = () ->`, then `assumeTrue(false);`; `else` or `wrap(`,
    then `it.skip('x', ...)`);
  - a form that only the code reading shows, or that the text as written does not prove: a
    comment between a switch's words (`@/* why */Disabled`, `it` and `/* why */ .skip(...)` on the
    next line), or inside what the lint reads (`@Test(enabled = false /* || RUN */)`);
  - `Assumptions.abort` (also `Assumptions.<Void>abort` and the line that imports it statically)
    and `abort(` called alone, which may be any method named `abort`;
  - a call or a list whose arguments do not say what it is in any of its readings (it opens on
    one line, and the next two lines do not close it: a `.each` table or an option object longer
    than three lines is a note).
- A call is judged where its arguments are. One whose arguments a line leaves open before they
  say what it is is judged with the next line, and then with the next two: `test.skip(`, then
  `false,` and `'supported');` is nothing, `it.skip(`, then `'later',` is sure, `test.skip('webkit'`,
  then `=== browserName, ...);` is a note. No line is reported from a part of a call.
- Not reported at all: the same forms with the literal that runs: `skipif(False, ...)` (every
  condition `False`), `skipUnless(True, ...)`, `xfail(...)` that runs, `@Test(enabled = true)`,
  `@DisabledIf("false")`, `@EnabledIf("true")`, `it.skipIf(false)`, Playwright's
  `test.skip(false, ...)` (also over lines), `{ skip: false }`, `{ skip: true, skip: false }`, a
  `skip: true` inside another property; an annotation's name right after a name (an address,
  `ci@Disabled.example`); JUnit's other conditional
  annotations (`@DisabledOnOs`, `@EnabledOnJre`, `@DisabledIfEnvironmentVariable`); an
  assumption whose condition is computed (`assumeTrue(ready())`); a method declared with the name
  `abort` (`abort(): void {}`, also over lines) or another object's `abort` (`controller .
  abort()`); another object's `fit` (`model.fit(X, y)`), `fit` called without a title
  (`fit(data)`) or a function declared `fit`; `xit` or `fit` only named (`import { xit }`);
  `this.skip(10)` (Mocha's takes no argument); a skip or a fixme whose first argument is a number
  (`it.skip(1)`, a stream's skip).
  White space of any kind a language allows may stand between a switch's words, and Unicode
  escapes (`\u0040Disabled`) are read as a compiler reads them. A switch written over two or three
  added lines (`it`, then `.skip(` on the next; a block comment that closes on the next line; a
  Python line ending in `\`) is found too, at the level the lines together show.
- Each added line of at most 2,000 characters is read two ways: its code (comments dropped, the
  insides of strings emptied), and as it is written (unless the whole line is a comment: it starts
  with `//`, or with a `#` that no name follows). Two or three added lines are read the same two
  ways together: their code read across the line breaks, and the lines as written joined by a
  space. **The text as written decides what is sure**: the code reading lost text (an emptied
  `'skip'` may be an override, a dropped comment may sit between a switch's words), so it never
  makes a form sure. It adds what only it shows, as a note at most (a comment between a switch's
  words), and where both readings judge the same form, the text as written says what it is
  (`@DisabledIf("false")` is nothing, though its code is `@DisabledIf("")`). One line cannot tell
  its quotes for certain, so what a line shows as written is never hidden: a switch's words
  inside a string, a text block, a trailing comment or a block comment's line are read too, by the
  same rules, and `check-pr` shows the line. They are sure where, as written, the whole form stands
  on its own (`String s = "x"; @Disabled class X {}`, or `@Disabled` on its own line of a text
  block), and a note otherwise (`"@Disabled"`, `"it.skip('a', ...)"`, `x(); // it.skip('a', ...)`,
  ` * @Disabled until PAY-12` in a doc comment). A longer added line is not read: Integrity says
  how many lines of the file were not (a note), `check-pr` warns. The lint's work grows with the
  lines it is given and no faster, whatever they hold.
- Not seen by the lint, as examples: a switch reached through another name (`const later =
  it.skip`, then `later('x', ...)`; a custom annotation that carries `@Disabled`; `@skip` imported
  from `unittest`); a switch of which the change wrote only a part (`.skip(` under an `it` that was
  there) or one written over more than three lines; a switch on an added line longer than 2,000
  characters; a quote in a regular expression before a comment between a switch's words
  (`const r = /'/; it /* ' */ .skip(...)`, or `const r = /'\//; it //'` and `.skip(` on the next
  line); a pytest skip mark applied elsewhere through a name (`skip_slow = pytest.mark.skip(...)`
  is a note where it is written; `@skip_slow` is not seen); a test moved into a class or a `describe` block
  that is already switched off; an empty test. For a file a runner owns (Maven Surefire, Vitest),
  `sdlc check` reports how many tests that ran before the change do not run now, whatever the
  source says; for a file no runner owns, and in `check-pr`, which runs no test, nothing does.
- `check-pr` lints the same test files as `sdlc check`: those named or placed like test files
  (`*.test.ts`, `__tests__/`, `tests/`, `FooTest.java`, ...) and those under `paths.test`. The
  repository's own patterns (`tests.disabledPatterns`) are read on the files under `paths.test`
  only, since they are the repository's patterns for its own test paths.
- A condition further from a switch than its own line and the added line before it is not seen:
  a declaration inside an `if` block that opened two lines up, or on a line the change did not
  add, is reported as sure. The lint reads lines; the run of the file says whether a test stopped
  running.
- A switch on an added line is reported as that line, whatever the change did with the test: a
  skipped test that is moved, given another title or edited on the line of its switch is
  reported as switched off. When overloads of a method change places, git shows one of them
  moved: if that is the one switched off, its switch is on an added line and is reported; if it
  is the other, nothing is.
- Which tests a file holds is not read from its source. A test removed, renamed or moved is
  said by the run when a runner owns the file, and by nothing otherwise (nor by `check-pr`). A
  deleted test file (named or placed like one, or under `paths.test`) is read by the lines the
  change removes, which are all of its lines in order: it is a problem when it is named like a
  test file, or its code calls `it`, `test` or `describe`, defines a test function or carries a
  JUnit test annotation (`@Test`, `@ParameterizedTest`, ...). That code is read as one text, so an
  example inside a comment, a template string or a string in three quotes (a Java text block, a
  Python docstring) that runs over several lines is text, not code; one in three quotes that does
  not close runs to the file's end. A line of code longer than 2,000 characters is not read for a
  test call: when no line that was read declares one, it is a note that says a line was not read.
  A note when only a comment or a string in it mentions one, or it is not a source file (a fixture
  that holds sample source); otherwise a note (a fixture or a helper). A test file deleted and
  added elsewhere with exactly the same lines is a note; so is a JVM test class moved to another
  package (only its package line differs). One whose import lines differ too is a note, "moved
  and its imports changed": an import can change what a name in it means, and the run of the
  moved file is its judge (see [Moved test files](#moved-test-files): its counts and its failures,
  not what an import makes a test test). A line longer than 2,000 characters is never taken for a
  package or an import line: the two files match only when such lines are the same as written.
  One edited otherwise on the way is a problem. Each added file is the move of one deleted file at
  most, and the other way round: of two deleted copies and one added, the second is a test file
  deleted. Each of these readings walks a line once, whatever it holds.
- "Fewer assertions" is a note and a plain count: the calls that look like assertions on the
  lines an edit of a Java or script test file removes, against those on the lines it adds, each
  line read in pieces of 2,000 characters.
- Test files are never read whole, so their size does not matter. Only build and configuration
  files are read whole (a pom, `package.json`, a coverage configuration); one larger than 2 MB
  is not read, and is a problem ("too large to scan").
- In a pull request build the base branch must be fetched, with enough history to reach the
  commit the branch started from. Otherwise the check stops with an error.
