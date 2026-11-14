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
| **Tests this change adds or edits** | Each such test is run after the change and before it. "Before" is the commit your branch started from, with only the change's test files laid over it. A test file the change edits is run a third time as it was, on that commit, to learn which tests it had |
| **Integrity** | What the diff does to the things that check the change: tests switched off or removed, secrets, changes to the build, the pipeline, dependencies and migrations |
| **Not checked** | What this run did not look at, so silence is never read as approval |

## How to read the test results

| Result | Meaning |
| --- | --- |
| Fails before, passes after | The test is sensitive to the change. It is run three times on each side, so a test that fails at random does not count |
| New test passes before and after | It passes whether or not the change is there, so it does not test the change. This fails the check |
| Fails after the change | The change's own test fails. This fails the check |
| Ran before the change, skipped now | The file as it was ran this test (it passed, failed or threw), and the change skips it: the change switched it off. Like a sensitive test it is run again on both sides, and a skip that comes and goes is unstable instead. The invocations of a parameterized test are counted together, as their numbers (`[1]`, `[2]`) say nothing about which arguments ran: it is switched off when more of them are skipped and fewer run than before. This fails the check |
| Removed | The file as it was had these tests, the file now does not, and the change did not only rename them. They are named. This fails the check, however many new tests came: deleting a test and writing another is not a rename. A file whose tests were all taken out counts too |
| Could not be judged | Before the change the test did not compile, threw something other than a failed assertion, or was not found. Or the file as it was could not be run (it did not compile, timed out, or no build tool), so it cannot be told whether a test is new. A test of code that did not exist before cannot fail there for the right reason, so it is counted neither way |
| Different results from run to run | Unstable. Not counted as sensitive |
| Skipped | A new test that is skipped. Listed |
| Note: renamed or moved | Tests whose name alone the change rewrote; tests found under a renamed group with their own titles unchanged. Each counts as the same test as before. Also tests now in another changed file, where they are judged as new. Not a problem |
| Already in the file, passes on both | A test you did not add. Not counted. The same for a test that was skipped before the change and still is |

A test is **new** when the run of its file as it was, on the commit your branch started from,
did not report a test of the same name in the same class or file. The test framework says which
tests a file has; the check does not read the test files' text for this.

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
a test that ran before the change is skipped now, tests were removed, or the integrity scan finds
a problem, 3 on a usage error. "Could not be judged" and notes never fail.

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
  change and is skipped now, tests removed, a test switched off, a secret.
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
- In a report, a renamed test looks exactly like one test removed and one added. The file's
  text tells them apart: a gone test and a new test of the same class or file are one test
  renamed when every line the change took out where the gone test stood comes back there, as it
  was or with only the gone test's name turned into the new test's name. Lines added around it
  (a new test written next to it) do not matter. A title changed in the same place as the
  test's body, or a test deleted in one place and another added elsewhere, is a removal. A test whose title is built while the
  file runs is found when the part of the title that changed is written out in the file
  (`'adds %i'` to `'sums %i'`). A test deleted and another written in its place with the very
  same lines apart from the name is taken for a rename.
- A file whose tests were all taken out runs and reports none: its tests were removed. For
  Maven this is a build that passes with no report for the class; a build that fails, or no
  build tool, is not read as "no tests".
- A renamed group (`describe` block, nested class) renames every test in it. A test whose own
  title is unchanged and fits exactly one gone test and one new test of the file is taken to be
  the same test, so a test deleted in one group and a trivial one of the same title added in
  another is taken for a rename too.
- A gone test whose own title another changed file now reports as a new test counts as moved
  (a note), not removed. In that other file it is a new test and is judged as one, so a moved
  test that passes on both sides fails the check there.
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
