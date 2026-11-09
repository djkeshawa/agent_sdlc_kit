# 4. Daily workflow: one story from ticket to merge

This follows a real run of the kit (story `PAY-9` "Refunds for paid orders", two tasks).
Replace the keys with yours. `sdlc` below means `java .agent/Sdlc.java`.

## Before you start

- The story exists in Jira with a clear description, under its epic.
- The one pager (title contains the story or epic key) and the HLD (usually the epic key) are in
  a space listed in `confluence.searchSpaces`.
- `sdlc doctor` is all OK on your machine.

## 1. QE: the test design

In Copilot Chat: `/test-design PAY-9`. The qe-designer agent:

1. `sdlc docs pull PAY-9`: copies the story, its epic, the one pager, the HLD and any other
   team's LLD for these keys into `.sdlc/PAY-9/sources/` and writes an `index.md`.
2. Writes `.sdlc/PAY-9/drafts/test-design.md` from `.agent/templates/test-design.md`:
   requirements `R-..` quoted from the sources, scenarios `### TS-01 ...` grouped under `## area`
   headings, each with Requirements, Level (`unit`, `integration`, `contract`, `e2e`, ...),
   Priority, Type and Given/When/Then.
3. `sdlc docs publish PAY-9 test-design --file .sdlc/PAY-9/drafts/test-design.md`: creates the
   page `PAY-9 - Test scenarios` with a mind map image, and comments on the story:

   ```
   [sdlc-bot] Test design v1 published: PAY-9 - Test scenarios
   ... approve this exact version by posting this line as a NEW comment:
   SDLC-APPROVED PAY-9 test-design 65671v1 1139650cf0f7
   ```

**QE lead:** read the page. To change it, edit it in Confluence (or ask the agent to re-publish
from an updated draft); then get a fresh line with `sdlc approval line PAY-9 test-design`.
To approve, post the line as a **new** comment on the story, as plain text. Check:

```bash
sdlc approval check PAY-9 test-design
# APPROVED: Test design v1 of PAY-9 by Jane QE on 2026-11-09T12:36:53
```

## 2. Developer: the LLD

`/design PAY-9`. The designer agent pulls the sources again (now with the approved test
design), reads other teams' LLDs and repositories, `docs/agent/project-profile.md` and the code,
and writes `.sdlc/PAY-9/drafts/lld.md` from `.agent/templates/lld.md`. Section 9 is the work
breakdown the tool turns into Jira sub-tasks:

```yaml
# sdlc-tasks
tasks:
  - id: T1
    title: Refund window policy
    scope:
      - src/main/java/com/acme/payments/refund/policy/**
      - src/test/java/com/acme/payments/refund/policy/**
    scenarios: [TS-01, TS-02]
    acceptance:
      - Given an order paid 3 days ago, when checked, then allowed (TS-01)
      - Given an order paid 31 days ago, when checked, then REFUND_WINDOW_CLOSED (TS-02)
  - id: T2
    title: Refund service
    dependsOn: [T1]
    scope:
      - src/main/java/com/acme/payments/refund/RefundService.java
      - src/test/java/com/acme/payments/refund/RefundServiceTest.java
    scenarios: [TS-03]
    acceptance:
      - Given the service, when a refund is requested, then the policy result is returned (TS-03)
```

Rules the tool enforces when publishing: unique ids, a title, a non-empty scope that is not
`**`, `src/**` or outside the repository, and acceptance criteria for every task. Keep scopes
narrow: **agents can edit nothing else**.

**Tech lead:** review the page and post `SDLC-APPROVED PAY-9 lld 229614v1 b6bf6e6c6ece` as a new
comment. Any later edit of the page needs a new line, and tasks already started are refused
until the new version is approved and `sdlc tasks create` runs again.

## 3. Developer: deliver the story

`/feature PAY-9`. The orchestrator does the rest; you approve the terminal commands it asks
for (pushes, PR creation). What happens, with the tool's output from the real run:

**Branch and tasks**

```bash
git switch -c feature/PAY-9-refunds origin/develop
sdlc tasks create PAY-9
# LLD of PAY-9 APPROVED by Tom Lead
# Created PAY-10 [T1] Refund window policy
# Created PAY-11 [T2] Refund service
```

Running it again finds the existing sub-tasks instead of creating new ones.

**Per task:** `sdlc task start PAY-10` checks the LLD is still the approved version, refuses a
task whose `dependsOn` is not approved yet, moves the task and the story to In Progress, and
limits agent edits to the task's scope. The implementer writes a failing test, watches it fail
(`sdlc verify`), writes the code, links each scenario in `docs/test-links/PAY-9.md`:

```
| TS-01 | src/test/java/com/acme/payments/refund/policy/RefundPolicyTest.java#allowsRefundWithinWindow |
| TS-02 | src/test/java/com/acme/payments/refund/policy/RefundPolicyTest.java#refusesRefundOnDay31 |
```

then `sdlc verify` (green) and commits `feat(PAY-10): add refund window policy`.

The reviewer gets only the task key, reads the LLD, test design, profile and the diff, and
files a report ending in `VERDICT:` and `REVIEWED_COMMIT:`. `sdlc review record` refuses a
report for another commit than HEAD, an APPROVE with BLOCKING findings, uncommitted changes, a
copy of the previous round's report, and rounds past `loops.review.max`. A real round 1:

```
VERDICT: CHANGES_REQUESTED
REVIEWED_COMMIT: 56fdcaa
- [BLOCKING] No boundary test for day 30 at .../RefundPolicyTest.java:13 - cites TS-02
```

The implementer fixes it, the reviewer approves round 2, and the next task starts.

**QA.** When the test design has integration, contract or e2e scenarios, the qa-verifier runs
them and records `VERDICT: PASS` with one `- TS-03 PASS - ...` line per scenario
(`sdlc qa record PAY-9 --file ...`).

**The PR gate.** `sdlc status` shows what is missing; `gh pr create` is refused until every
task has at least `loops.review.min` rounds ending in APPROVE at the current HEAD, QA passed at
HEAD (if required), nothing is uncommitted, the build is verified for these exact files, and the
PR policy passes (branch name, commit subjects, tests with main changes, no disabled tests, no
secrets, every scenario linked to an existing test, no changed code outside the tasks' paths).

```
PR gate: READY
```

After `gh pr create`, the hook moves the story and its sub-tasks to In Review, links the PR on
the story and comments there.

**The CI loop.** `sdlc ci wait` waits for the PR's checks, then writes a digest to
`.sdlc/PAY-9/inbox/ci-round-N.md` and comments the result on the story. A real red round:

```
- FAIL continuous-integration/jenkins/pr-merge - http://jenkins/job/payments/5/display/redirect
First failing stage: **SonarQube Cloud**
## SonarQube quality gate: ERROR
- FAILED condition: new_coverage = 30.0 (threshold LT 80)
RESULT: FAILED - fix the causes above (never the checks), get the fix reviewed, then push.
```

The ci-fixer adds the missing tests; the push is refused until the reviewer approved the fix
commit and `verify` passed for it; the next round is green. After `loops.ci.max` failed rounds
pushes are refused and a human takes over.

**The report.** `sdlc retro PAY-9` lists every mistake found and fixed (review findings, CI
failures, Sonar conditions), the loop counts and every hook refusal; `--comment` posts it on the
story.

## 4. Human: review and merge

Review the PR like any other (the template lists what to check), with CODEOWNERS for guardrail
files. Merge when Jenkins and SonarQube are green. `sdlc sync` then comments "merged" on the
story; moving tickets to Done stays with you unless `jira.agentMayClose: true`.

## Useful commands

| Command | Does |
| --- | --- |
| `sdlc status` | branch, story, task, scope, loops, PR gate and what is missing |
| `sdlc docs find KEY` | the pages found for a key and its parents |
| `sdlc approval check KEY test-design\|lld` | approved? if not, why, and the line to post |
| `sdlc tasks list STORY` | tasks with status and review rounds |
| `sdlc trace STORY` | every scenario linked to an existing test? |
| `sdlc check-pr --title "..."` | the PR policy, locally |
| `sdlc ci status` / `ci failures` | the PR's checks / the digest without recording a round |
| `sdlc sync` | move Jira tickets to match the work |
| `sdlc retro STORY [--comment]` | mistakes, loops, refusals |

## Working without the agents

Everything above works by hand: run the same `sdlc` commands yourself. The git hooks apply to
humans too (including the policy and verify checks on push); the IDE hook rules, such as the PR
gate on `gh pr create`, apply to agents. `sdlc status` shows you the same gate.
