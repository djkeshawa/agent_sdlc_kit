# Mandate, version 0

A mandate says what one change is authorised to do. A person approves it without the diff in
view; a verifier then compares the change with it. This page is the format and the rules a
verifier applies. The reasons are in [proposal 02](../docs/proposals/02-mandate-direction.md).

Status: version 0, built in the tool as `sdlc mandate` and `sdlc check --mandate`. Local use
only so far: approval is by the person at the keyboard and is labelled as such.

## The file

One JSON file per change: `docs/mandates/<id>.json`.

```json
{
  "mandate": "v0",
  "id": "fix-search",
  "intent": "Searching with an empty query returns an empty list, not an error",
  "mustStayTrue": [
    { "is": "A query with text returns the same results as before",
      "test": "src/test/java/com/acme/search/SearchRegressionTest.java" }
  ],
  "failsIf": "an empty query reaches the database",
  "scope": { "paths": ["src/main/java/com/acme/search/**", "src/test/java/com/acme/search/**"] },
  "oracle": {
    "scenarios": ["src/test/java/com/acme/search/SearchServiceTest.java#emptyQueryReturnsEmptyList"],
    "locked": { "src/test/java/com/acme/search/SearchRegressionTest.java": "3b18e512dba79e4c8300dd08aeb37f8e728b8dad" }
  }
}
```

| Field | Required | Rule |
| --- | --- | --- |
| `mandate` | yes | The string `v0` |
| `id` | yes | 1 to 64 characters: letters, digits, `.`, `_`, `-`; starts with a letter or digit. Equals the file name without `.json` |
| `intent` | yes | One sentence, 1 to 300 characters, no line break |
| `scope.paths` | yes | One or more globs, relative to the repository root. `**` crosses folders, `*` stays in one. No leading `/`, no `..`, no backslash |
| `oracle.scenarios` | yes | One or more tests that must pass: `path` (every case in the file) or `path#name` (one case) |
| `mustStayTrue` | no | A list of `{ "is": sentence, "test": path }`. Each `test` must be a key of `oracle.locked` |
| `failsIf` | no | The approver's own sentence, 1 to 300 characters. Written by `sdlc mandate approve` |
| `oracle.locked` | no | A map from a test file's path to the git blob id it must have (`git hash-object <file>`) |
| `scope.apis` | no | Reserved. Accepted and reported as not checked in version 0 |
| `feature.key` | no | A string that groups mandates of one feature |

Rules of the format:

- The identity of a mandate is the **git blob id of the file's bytes**. There is no canonical
  form: change one byte and it is another mandate.
- Parsing is strict. A duplicate key, a key this table does not name, a wrong type, or a path
  that leaves the repository is an error, and the mandate is not used.
- A mandate holds no numbers and no control characters (no line break, tab or escape inside a
  text), and no character that is invisible or that turns the direction of text: what is shown
  must read as it is stored. Exactly, a text or key may hold no code point of the Unicode
  categories Cc (control), Cf (format), Zl and Zp (line and paragraph separators), Cs (half of
  a surrogate pair), Co (private use) or Cn (not assigned in the Unicode version of the Java
  that runs the tool). It is at most 256 KB, is UTF-8, and is a file of its own, not a link.
- The blob id is of the bytes on disk, with no line-ending or other git filter applied. Locked
  tests are compared as git stores them, which is what `git hash-object <file>` prints.
- No YAML, no Markdown front matter, no comments.

## Drafting

`sdlc mandate draft <id>` finishes a draft in place: it adds `mandate` and `id` when they are
missing, and writes into `oracle.locked` the blob id of each `mustStayTrue` test and of each
test already locked, as the file is now in the working tree. It refuses a draft that holds
`failsIf`, writes only a valid mandate, and never approves.

## The card

The readable view of a mandate: intent, what must stay true, the "this fails if" line, the
limits, the tests. `sdlc mandate show <id>` prints it. It is rendered, never stored, so there
is no second text that looks approved.

## Approval

Version 0 has one kind: **self**. `sdlc mandate approve <id>`, run by a person in a terminal:

1. asks for the "this fails if…" sentence **before** showing anything of the draft, the intent
   included;
2. shows the card beside that sentence and asks which scenario covers it. With none, the
   sentence is kept and reported as unresolved;
3. writes the sentence into `failsIf`, and records the approval of the resulting blob id.

The record is kept outside the working tree (in the repository's git folder), so it is not part
of any commit and cannot be confused with an approval by someone else. It holds the mandate id,
the blob id, the git user name and the time.

An agent never approves. The kit's guard refuses `mandate approve` from an agent's shell.

An approval is of one blob. Editing the mandate afterwards makes it **amended**: the verdict
says so, and the edit needs approving again.

## The verdict

`sdlc check --mandate <id>` compares the change with the mandate and prints two results that
are kept apart.

**Scope** (was the change inside the mandate?)

| Value | Meaning |
| --- | --- |
| `INSIDE` | The mandate is approved in the form it has now, and every changed file matches `scope.paths` |
| `AMENDED` | Every changed file matches, and the mandate is not approved in the form it has now |
| `OUTSIDE` | At least one changed file matches no path. The files are listed |

The mandate's own file is not counted as part of the change.

**Evidence** (what is known about each obligation?). Each line is `satisfied`, `violated`,
`unknown` or `not run`, and says where the fact comes from: *independent* (read from git
alone) or *candidate-influenced* (the result of running the change's own build).

| # | Obligation | Source | Result |
| --- | --- | --- | --- |
| 1 | Locked tests have the blob ids the mandate names | independent | `violated` when one differs or is missing |
| 2 | Build files and test-runner configuration are unchanged from the base | independent | `violated` when one changed. Obligations 3 to 5 are then `unknown`: a changed build can make any test pass |
| 3 | Each scenario was found, ran and passed after the change | candidate-influenced | `violated` when one fails, or does not compile or load after the change; `unknown` when one is not found, when its name fits more than one test, or when it cannot be run for a reason outside the code (no build tool, a timeout) |
| 4 | Each `mustStayTrue` test passes before the change and after it | candidate-influenced | `violated` when it fails, or does not compile or load, on either side; `unknown` when it cannot be run for a reason outside the code |
| 5 | The witness: tests the change adds or edits fail before it and pass after it | candidate-influenced | Reported with its counts. `violated` when a new test passes on both sides, a changed test fails after, tests that ran before the change do not run after it, or an old test that passed before the change fails as it was on the code after it, in every run, and was taken out of the way in every run after the change that the check made; the line gives the number of tests behind each, "at least N" where a file's counts prove only a least number. Whether a test is new or ran before is read from a run of the edited file as it was, on the base commit; a test file the change only moved (by the text check's rule: the same lines, or for a JVM source the same but for its package and import lines) is run as it was at the path it had, and it is compared with its run as it was by counts only, as its reported names change with its path or package: by the count of tests that ran and do not run now, and by the count of new tests that pass on both sides with the number of tests it reported as it was and after the change in place of the names gone and new (its lines are the lines it had, but a source of rows can give it more; where it reports as many tests as it had, none of them is new); a test is known by its exact reported name and nothing else, and what names cannot tell is decided by counting each file's tests, said of the file, as a count does not say which tests. Tests that ran and do not run now: the cases that passed at the first try as the file was (R0), less the cases not skipped after the change (R1), at least R0 - R1 in every run, whatever became of them (deleted, skipped, hidden in a skipped case or an empty report); the count does not say whether the change of the tests or of the code took them. It is `violated` where the file as it was, run on the code after the change, runs more cases there than the file runs now in every run of it there that gave a usable report (R4 > R1, R4 counting the cases there that are not skipped, less those that passed only when the build ran them again: on the same code the change of the tests runs fewer), or where no run of it there gives a usable report (with the reason); `unknown` where R4 <= R1 in any usable run of it there, whatever another gave (the change of the code may have taken them), where only cases that passed when the build ran them again make the count, and where the report as it was holds a failure. Other counts: more new tests than gone ones and than tests that do not pass on both sides is a new test that passes on both sides; more broken old tests taken away than tests that could be their rewrites is taken out of the way. Those counts are taken only from reports that list every test they rest on: a test that failed or threw may stand for a fault outside the tests (in their place, or as a case of its own), a skipped case for any number of tests (an ignored class, a disabled factory or template), a report with no test for a class stopped in its set-up, and a case the build ran again (Surefire's `rerunFailingTestsCount`; Vitest's `retry`, as Vitest itself counts it beside its JUnit report) for a set-up run again. New tests that pass on both sides need the report as it was to report a test, hold no failure and no case run again, and skip each case as often under its name as the other; old tests taken out of the way need that no report after the change holds a failure. What only such a report would prove is `unknown`, with the reason. A case that failed every time the build ran it failed; one that passed only when run again is unstable. An old test taken out of the way is not counted again among the tests that ran and do not run now. Whether the change broke an old test it took away is read from a run of the edited file as it was, on the code after the change, made for each edited file whose list of tests changed; where that run does not compile, load or report an old test as passed or failed, the line is `unknown`, and so it is for an old test that fails there only beside the other edited test files as they were (its file alone put back on the code after the change does not fail). Where the changed test files do not compile together before the change, each is run alone on both sides; a test that passes after the change only beside the other changed test files is `unknown`. A run that does not say which tests passed at the first try (a version of Vitest that does not count retries) leaves its file judged by its failures alone, every other verdict of it `unknown`. Every verdict is made from every run the check made: each run as it was with each run before and after the change; a changed test that fails in any run after the change fails, and so does a changed test file that does not compile or load in any run after the change (a run that gives no result for a reason outside the code is unstable, not a failure); another verdict of a test stands only where every such combination agrees, and new tests are counted again in each. Every difference among a file's reports (a test gone, new, switched off or on, or with another result, between the file as it was and after the change, or from one run of a way to another) is answered by a line of the check; one no rule answered for is printed as `unknown` or unstable. A line that is not `violated` answers only for the tests it names or whose counts it speaks of. The order in which a report lists its tests is not read: runs are compared by counts and names. A test replaced by another under its own name, in a file whose list of tests stayed the same, is not seen. `unknown` when any of the change's tests could not be judged, gave different results from run to run, was skipped or has no runner, or when none is sensitive. A test that throws before the change, the same exception type in every run, and passes after it counts as sensitive, and the line says how many of the sensitive tests did so; an exception that says the code it calls did not exist or did not link, or one from outside the test (an error of the virtual machine, a class's set-up, a time limit), leaves it `unknown`. Each sensitive test is listed as approved with the mandate (its file is in `oracle.locked`) or written with the change, and one that threw with the exception |

Build files and runner configuration, for obligation 2: `pom.xml`, `.mvn/**`, `mvnw`,
`mvnw.cmd`, `build.gradle*`, `settings.gradle*`, `gradle.properties`, `gradle/**`, `gradlew*`,
`package.json`, `package-lock.json`, `npm-shrinkwrap.json`, `yarn.lock`, `pnpm-lock.yaml`,
`pnpm-workspace.yaml`, `bun.lock`, `bun.lockb`, `.npmrc`, `.yarnrc*`, `vite.config.*`,
`vitest.config.*`, `vitest.workspace.*`, `jest.config.*`, `babel.config.*`, `.babelrc*`,
`tsconfig*.json`, at any depth.

Exit code: 1 when scope is `OUTSIDE` or any obligation is `violated`; 0 otherwise. `unknown`
and `AMENDED` do not fail the command; they are printed, and a policy outside this tool decides
what they mean for review.

## What a verdict does not say

- That the change is correct. Obligations 3 to 5 are results of the change's own build, run by
  the change's own author.
- That the approval was given before the work. It says the text was approved in this form.
- Anything about files the mandate does not name beyond "outside".
- That the tests ran on the bytes that were locked, in a repository whose git filters rewrite
  files on checkout.
