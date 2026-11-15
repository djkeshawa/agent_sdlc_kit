# 10. Mandates: approve the plan, not the diff

A mandate is a small file that says what one change is allowed to do. A person approves it
without reading the diff. `sdlc check --mandate` then compares the finished change with it.

This page is the how-to. The format and the rules are in
[spec/mandate-v0.md](../spec/mandate-v0.md); the reasons are in
[proposal 02](proposals/02-mandate-direction.md).

Version 0 is for local use: the approval is your own, on your own machine, and every verdict
says so. Nothing is enforced.

## The steps

```bash
sdlc mandate check fix-search      # is docs/mandates/fix-search.json a valid mandate?
sdlc mandate show fix-search       # the mandate as a card you can read
sdlc mandate approve fix-search    # you approve it, in a terminal
# ... you or your agent make the change ...
sdlc check --mandate fix-search    # the change against the mandate
```

1. **Write the mandate.** `docs/mandates/<id>.json`: one sentence of intent, the paths the
   change may touch, and the tests that must pass. Your agent can draft it from the issue or
   its plan. The smallest one:

   ```json
   {
     "mandate": "v0",
     "id": "fix-search",
     "intent": "Searching with an empty query returns an empty list, not an error",
     "scope": { "paths": ["src/main/java/com/acme/search/**", "src/test/java/com/acme/search/**"] },
     "oracle": { "scenarios": ["src/test/java/com/acme/search/SearchServiceTest.java#emptyQueryReturnsEmptyList"] }
   }
   ```

2. **Approve it.** `sdlc mandate approve` asks you three things, in this order:
   - before it shows anything: in your own words, how could this change fail?
   - after it shows the card: which of the tests covers what you just said (or none)?
   - the mandate's id, to confirm.

   Your sentence is written into the mandate as `failsIf`. The order is deliberate: a draft
   written by the agent whose work it limits should not be the first thing you read.
   An agent cannot run this command; the kit's guard refuses it.

3. **Make the change**, then run `sdlc check --mandate <id>`.

## Reading the verdict

```text
sdlc check --mandate fix-search: 3 files changed against origin/main (86ae793)

Mandate   Searching with an empty query returns an empty list, not an error
          approved by Ada <ada@example.com> on 2026-11-14 (their own approval, recorded on this machine)

Scope     INSIDE   every changed file is inside the mandate, and the mandate is approved as it is

Evidence
  satisfied locked tests are the files the mandate names (1)   [read from git]
  satisfied build files and test configuration are unchanged   [read from git]
  satisfied scenarios found, ran and passed: 1 of 1   [from the change's own test run]
  satisfied the change's own tests: 1 fails before it and passes after it (0 approved with the mandate, 1 written with the change)   [from the change's own test run]
```

Two results, never merged into one.

| Scope | Meaning |
| --- | --- |
| `INSIDE` | Every changed file matches the mandate's paths, and the mandate is approved in the form it has now |
| `AMENDED` | The files match, but the mandate was never approved, or was edited after approval. Approve it again |
| `OUTSIDE` | A changed file matches no path. The files are listed. Widen the mandate and approve it again, or take the file out of the change |

| Evidence | Meaning |
| --- | --- |
| `satisfied` | Checked, and it holds |
| `violated` | Checked, and it does not hold. The command exits with 1 |
| `unknown` | Could not be checked: a test that was not found, a build tool that is missing. It is not a pass |
| `not run` | You asked for `--no-run` |

Each line also says where the fact comes from. **Read from git** needs no trust in the change.
**From the change's own test run** is only as good as the change's own build: when a build file
or the test configuration changed, those lines are `unknown`, because a changed build can make
any test pass.

The last line, the change's own tests, is `violated` when a new test passes before and after the
change, a changed test fails after it or its file does not compile or load (in any run after
it), tests that ran before the change do
not run now (an edited test file runs fewer tests after the change than passed in it as it was,
in every run, whatever became of them: deleted, skipped or hidden), or a test that passed before
the change fails as it was on the change, in every run, and was taken out of the way in every run
after it that the check made. The line says how many tests stand behind each of these, "at least
N" where the counts of a file prove only a least number. Which tests are new, ran before or are
gone is read from a run of each edited test file as it was, on the commit the branch started
from; whether the change broke a test it took away, and whether the change of the code alone may
account for tests that do not run now, from a run of that file as it was on the code after the
change (see [sdlc check](09-check.md#which-tests-are-new-gone-or-switched-off)). A test is known
by its exact name, and what names cannot tell is decided by counting the tests of each file; a
count says how many, never which, so these verdicts name the file, and the count of tests that do
not run now never says which change, of the tests or of the code, took them. Tests that ran and
do not run now need no list of the tests after the change: each test that passed as it was, at
the first try, ran, and a test hidden in a skipped case or an empty report does not run. They are
not said where the file as it was reported a test that failed or threw (a choice: that file did
not pass), nor where the file as it was runs no more tests on the code after the change than the
file does now, in any run of it there that gave a usable report (the change of the code may have
taken them). Other counts are taken only from
reports that list every test they rest on: a test that failed or threw may stand for a fault
outside the tests, a skipped case for any number of tests (an ignored class, a disabled factory),
a report with no test at all for a class stopped in its set-up, and a case the build ran again
(Surefire's `rerunFailingTestsCount`; Vitest's `retry`, as Vitest itself counts it) for a set-up
that failed and was run again. So new tests are
counted only where the report as it was holds no failure and no case the build ran again,
reports a test and skips each case as often under its name as the other, and old tests taken out
of the way only where no report after the change holds a failure. A case that failed every time
the build ran it failed; one that passed only when run again is not counted as a test that ran,
and is not one result. What only such a report would prove is `unknown`, and so is an old test the
run of its file as it was, on the code after the change, could not judge (it did not compile,
load or report it), or that fails there only beside the other edited test files as they were
(its file alone, on the code after the change, does not fail). A test of a file that had to be run
alone before the change, that passes after it only beside the other changed test files, is
`unknown` too, and so is every pass of a file whose runs do not say which tests passed at the first
try (a version of Vitest that does not count retries): that file is judged by its failures alone.
Every verdict is made from every run the check made, each run as it was with
each run before and after the change, and a verdict of a test stands only where they all agree.
Every difference among a file's reports, between the file as it was and after the change and
from run to run, is answered by some line of the check; one no rule answered for is printed as
`unknown` or as different results from run to run, never passed. Where the counts cannot tell two
stories apart, one of them fine (a test renamed, or one deleted and another written; an old test
the change breaks, renamed and rewritten for the new behaviour, or taken out and a new test
written; a row the change of the code took away), or runs disagree, the line is `unknown`, not
`violated`, and a review decides. A test replaced by
another under its own name, in a file whose list of tests stayed the same, is not seen (see the
limits of
[sdlc check](09-check.md#limits)).

The command fails (exit code 1) for `OUTSIDE` or a `violated` line. `AMENDED` and `unknown` do
not fail it; they are there for you to read.

## What a verdict does not say

- That the change is correct. The tests and the code come from the same author.
- That the mandate was approved before the work was done. It says this text was approved.
- Anything about an obligation marked `unknown`.

## Limits of version 0

- Approval is yours alone and lives in the repository's git folder on your machine. A clone
  does not carry it, and nobody else can check it. Approval by another person, read from the
  code host, is the next step.
- Tests can be run for Maven (Surefire) and Vitest only.
- `scope.apis` is accepted and not checked.
