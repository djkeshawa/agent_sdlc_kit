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
- No YAML, no Markdown front matter, no comments.

## The card

The readable view of a mandate: intent, what must stay true, the "this fails if" line, the
limits, the tests. `sdlc mandate show <id>` prints it. It is rendered, never stored, so there
is no second text that looks approved.

## Approval

Version 0 has one kind: **self**. `sdlc mandate approve <id>`, run by a person in a terminal:

1. asks for the "this fails if…" sentence **before** showing the card;
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
| 3 | Each scenario was found, ran and passed after the change | candidate-influenced | `violated` when one fails; `unknown` when one is not found or cannot be run |
| 4 | Each `mustStayTrue` test passes before the change and after it | candidate-influenced | `violated` when it fails on either side; `unknown` when it is not in `oracle.locked` |
| 5 | The witness: tests the change adds or edits fail before it and pass after it | candidate-influenced | Reported with its counts. `violated` when a new test passes on both sides or a changed test fails after. Each sensitive test is graded *pre-approved* (its file is in `oracle.locked`) or *author-written* |

Build files and runner configuration, for obligation 2: `pom.xml`, `.mvn/**`, `mvnw`,
`mvnw.cmd`, `build.gradle*`, `settings.gradle*`, `gradle.properties`, `gradle/**`, `gradlew*`,
`package.json`, `package-lock.json`, `npm-shrinkwrap.json`, `yarn.lock`, `pnpm-lock.yaml`,
`vitest.config.*`, `vitest.workspace.*`, `jest.config.*`, `tsconfig*.json`, at any depth.

Exit code: 1 when scope is `OUTSIDE` or any obligation is `violated`; 0 otherwise. `unknown`
and `AMENDED` do not fail the command; they are printed, and a policy outside this tool decides
what they mean for review.

## What a verdict does not say

- That the change is correct. Obligations 3 to 5 are results of the change's own build, run by
  the change's own author.
- That the approval was given before the work. It says the text was approved in this form.
- Anything about files the mandate does not name beyond "outside".
