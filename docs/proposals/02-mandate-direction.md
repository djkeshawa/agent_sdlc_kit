# Proposal: approve the plan, not the diff

Mandates, verdicts and audited autonomy for agent-made changes.

Status: proposal, revision 3, 2026-11-14. Revision 2 came from eight independent researchers.
This revision comes from two expert reviewers (platform and supply-chain security; software
verification and adoption) who worked through a register of twelve named doubts in two rounds,
each reviewing the other's report. It narrows the claims, corrects the statistics, and cuts the
scope to 90 days with written stop rules. Nothing in sections 6 to 8 is built. Section 3 is
what was verified in the code and on the test bed. Section 12 is the doubt register: what is
settled, what is not, and the test that contains each open doubt.

## 1. The short answer

Review of agent-made changes is being skipped or handed to an AI approver, and nobody can say
on what grounds a given change was allowed in. Merges without review are up 76% in one
telemetry set of 22,000 developers, about 80% of AI-co-authored pull requests from non-owners in
open source merge with no explicit review, GitHub ships AI approvals that count toward the
required approvals, and Cursor ships an approval agent.

The project is:

> **A portable way to say what a change was authorised to do, approved by a person who has not
> seen the diff, and to state with checked evidence what it did.**

In practice: **approve the plan, not the diff; audit a sample, not every change.**

| Part | What it is | What it can honestly claim |
| --- | --- | --- |
| **Mandate** | A short file a person approves without the diff in view: what must change, what must stay true, the limits | That the approval is bound to exactly this text, by this person, on this host |
| **Verdict** | Two results from a verifier the author does not control. *Scope*: did the change stay inside. *Evidence*: each obligation satisfied, violated, unknown or not run | Scope is independent. Evidence from the change's own test run is labelled *candidate-influenced*; only tests run from outside the repository are *independent* |
| **Ledger** | The record of verdicts, sampled full reviews and escaped defects. It lowers the share of changes a person reads in full, in steps, and never to zero | A stated bound on the rate of findings, under a stated assumption about reviewers |

Two adversaries, kept apart throughout:

- **An unreliable agent** (the common case): it drifts, overreaches, writes tests that prove
  nothing. Candidate-influenced evidence has real value here, and experiment E-A measures it.
- **A hostile contributor** who wants a green check. Against this one only the authorisation
  half holds: scope, approval, policy, verifier. Evidence from the candidate's own test run does
  not.

It grows in three steps on the same files: **Local** (one person, no accounts, no CI), **CI**
(a team, any agent, any vendor), **Organisation** (central policy, evidence for change
management). The first 90 days build the first step, the start of the second, and the two
experiments that decide whether the rest is worth building.

## 2. What this revision changes

Revision 2 changed the problem statement, split the verdict in two, added the trust boundary,
replaced streak-based promotion and reordered adoption around a Local level. Revision 3:

| Topic | Revision 2 | Revision 3 | Why |
| --- | --- | --- | --- |
| Trust claim | The verifier collects test results the author cannot influence | Scope and approval are independent; in-repository test results are candidate-influenced; only outside hold-outs are independent | A job that runs the candidate's tests runs the candidate, on every platform |
| Threat model | One | Two: unreliable agent, hostile contributor | They need different evidence and the plan mixed them |
| The gap in today's CI | Tool and `sdlc.yml` come from the pull request | Also the pipeline definition, the base branch name and the story id (section 3, gap 6) | Read in the source |
| Approval | A separate mandate pull request | One pull request by default: the approval is bound to its first, mandate-only commit. Two pull requests stay as an option | Halves the round trips, cannot be replayed, fails closed |
| "Before the work" | Claimed | "Approved without the diff in view" | Neither variant can prove when the work was done |
| Obligations | Eight, including named wrong implementations | Three in version 0; the fail-then-pass check gets soundness rules and two grades; wrong implementations are cut | A wrong implementation can only be written after the right one exists |
| Statistics | An exact bound on a fixed number of clean reviews | A sequential test on the finding rate; reading stepped down to 1/2, 1/4, 1/10, never zero | Four errors in the earlier rule (section 6.6) |
| Lightest mode | "No per-change human" | A stated fraction always gets a full human review; needs an independent obligation and a host that can enforce it | Sampling bounds an error rate; a forgery is a single event |
| Local value | A zero-configuration lint of the diff | An **executed** statement: which new tests fail before the change and pass after it | The lint half already exists as free tools |
| Premise | To be shown in a pilot | Two experiments with a stop rule written first: E-A (no people) and E-B-min (in the pilot) | No study measures plan approval against defects |
| Change management | "The standard change for agent-made code" | The mandate approval is the authorisation record; a promoted class *may be registered* as a standard change model by the change authority | A standard change is a change model, not an author category |
| Signing | In phase A3 | "Signed where the host offers it", after the gate | Private repositories need the top plan; not on self-hosted |
| Scope | Seven A-phases, four B-phases | 90 days, then a gate. Everything else is a backlog with triggers | About twice too wide for one maintainer |
| Several repositories | Evaluation and fixes now | Frozen until the gate; the full direction is kept in [proposal 03](03-multi-repository-direction.md) | Both reviewers, independently; decided 2026-11-14 |

## 3. Where the repository stands

Verified on 2026-11-13 and 2026-11-14 by running the tests, reading the source and building the
test bed.

| Fact | Detail |
| --- | --- |
| Tests | `selftest` 532 of 532 in the kit, 541 of 541 in each test repository; `tests/e2e/run.py` 166 of 166 (JDK 21, Linux) |
| Size | One tool, about 12,800 lines of Java in seven layers, no build step; 11 agents, 8 prompts, 7 skills |
| Single-repository flow | Complete: test design, LLD, approvals pinned to a page version, tasks with scopes, test-first verify, blind review rounds, QA, PR gate, CI digest and fix loop, retro |
| Multi-repository layer | Complete in code: catalog, scan, solution design with slices, stories per component, lanes, contract allowances, test locks, hold-out digest, scoping eval, co-change, kit upgrade |
| What CI checks | Branch, commits, tests with main changes, disabled tests, secrets, test locks, contract changes. Not task scopes and not scenario links |
| Test bed (built 2026-11-14) | Three private repositories owned by a user account, kit updated, hooks active. Jira and Confluence Cloud with five role accounts, Jenkins in Docker with two jobs. Nothing committed yet |
| Adoption | Public repository, no users, one author |

Gaps the docs do not state:

1. `catalog sync` reads only repositories listed in `sdlc.yml`.
2. `catalog scan` runs only by hand.
3. A slice lists every other provider's contract.
4. The agreement of the impact-analysis runs is computed by an agent, not the tool.
5. `feature status` reports design readiness only.
6. **CI judges a pull request with the pull request's own rules, in several ways.** The stage
   is defined in the repository's own Jenkinsfile. `Sdlc.java` compiles `.agent/src` from the
   checkout. `Ctx.init` loads `.agent/sdlc.yml` from the checkout, and `Git.base()` takes the
   base branch name from it. `Checks.storyFromBranch` takes the story from the branch name the
   author chose. `Locks.loosened` reads the base's locks at the merge base, not the tip.
   `Contracts.prPolicy` reads the story's allowance from the working tree and only warns when
   the same pull request changed it. Only CODEOWNERS review stands in the way.
7. Hold-out results are not tied to a commit (`Holdout.status` reads `lastCompletedBuild`).
8. Links starting `manual:`, `n/a:` or `other-repo:` pass unchecked (`Trace`); a story without
   a test design gets no QA scenarios (`Tasks.qaScenarios`).
9. Component names lose their namespace, hosts match by substring, and a contract allowance has
   one `breaking` flag per API.
10. The tool decides whether to recompile from file timestamps and ran a stale build on the
    test bed (`Sdlc.stale`). A kit older than the `upgrade` command cannot upgrade itself.
11. On the free Jira site every account is an administrator, so the bot could alter a lead's
    approval comment. The approval record must live on the source host.

## 4. What the research shows

Marks: **V** checked by me at the source, **R** read at the source by a researcher or
reviewer, **S** summary only. Most numbers are vendor telemetry or single studies.

| Finding | Mark | What it means here |
| --- | --- | --- |
| Merges without review up 76.3%, time in QA up 300.6%, monthly incidents up 125.4%, PR size up 71.8% (22,000 developers) | V | The pain moved downstream; evidence for unread merges is the need |
| About 80% of AI-co-authored pull requests from non-owners merged with no explicit review | V | "Full review as today" is often no review |
| Copilot approvals are generally available and count toward required approvals; criteria unpublished. Cursor's approval agent uses the base-branch copy of a changed policy | V | Approval is a vendor feature. Its grounds are opaque |
| Meta's RADAR landed over 331,000 low-risk changes through layered eligibility and deterministic validation | V | Works at scale, in-house |
| In one benchmark's telemetry about half of known bugs were found before merge; reviewer recall is not measured. The best review agent found 71.9% | R | Do not claim any mode is "as safe as review"; nobody knows how safe review is |
| Developers shown an LLM review look where it points and find no more severe issues; developers treat an agent's plan as a faithful proxy and passing tests as correctness | R, S | The approver must think before seeing the draft. Show something the tool did not pick |
| Spec users read specs partially and skip them for small work; 29.9% of repositories using a spec tool have at most one spec commit | R | Mandates must be optional and tiny locally |
| Keeping only code patches whose accompanying tests fail before and pass after more than doubled one agent's precision, to 47.8% | R | The fail-then-pass check is cheap and worth it |
| Hidden tests cut gaming to near zero; a judge model accepted 38% of its own family's failing outputs | R | Hold-outs are the independent evidence. No LLM judge among checked facts |
| A candidate can write a test that tells base from head without exercising the fix | reviewer's analysis | The check says "sensitive to the change", never "fixed" |
| Zero findings in n reviews bounds the finding rate, not the defect rate; a restarted streak of 59 is reached after about 392 reviews on average by a class with a true 5% rate | calculation | Section 6.6 |
| A third of bugs surface only after the next release | S | Samples must mature; demotion must be retroactive |
| Required workflows exist only in organisation rulesets, which need a Team or Enterprise plan; `pull_request_target` is blocked by default on public repositories from 2026-12-02 | V, R | The boundary is real only where someone other than the author controls an organisation rule |
| Free tools already flag weakened tests in a diff for Python, JavaScript and Go; none found for Java | R | The lint is commodity. The executed check is not |
| Agents run for days and in fleets; quality erodes in long runs; incidents come from run-time permissions | R, S | Real, and deferred (section 6.7) |
| IntentSpec v1.2 is an open intent format; status changes are not enforced | V | A drafting input |
| A standard change is a pre-authorised, low-risk, repeatable change model; regulation separates approver, requester and implementer | S, R | Section 7 |

## 5. The gap, stated narrowly

| Question | Who ships something today | What is still open |
| --- | --- | --- |
| Was the change authorised, and did it stay inside? | Qodo and CodeRabbit judge against the ticket with an LLM, after the work. IntentSpec is a format without enforcement | A human approval bound to the text, checked deterministically against the diff, across vendors and hosts |
| What is actually known about it? | Vendors approve on undisclosed grounds | Published, replayable criteria; every obligation typed, with `unknown` allowed; the source of each fact labelled |
| How much does a person still need to read? | Cursor, Copilot, gitStream, ServiceNow and in-house systems route by risk | Stepped-down reading on the team's own record, with a stated bound |
| Across repositories, did each part stay in its slice? | Pact, oasdiff and buf check compatibility; Sourcegraph and Moderne orchestrate | Frozen until there is a user who has the problem |

Vendors will likely ship, inside their own products by the end of 2027: plan approval before a
cloud agent starts, per-task path, tool and network limits at run time, session provenance and
budgets. What is unlikely from any one vendor is a neutral check that binds a human approval to
the resulting diff across vendors and hosts, with published criteria and the same behaviour on
self-hosted systems. **The centre of gravity is therefore the verifier**: a pinned image, the
host recipes, and conformance pull requests with expected verdicts. The specification is
extracted from it. No hosted service.

## 6. The design

### 6.1 Mandate

One JSON file per change, `docs/mandates/<ID>.json`. Its identity is the git blob SHA of its
bytes; there is no canonical form to argue about. Parsing is strict: duplicate keys and unknown
keys under `scope` are refused. No YAML and no Markdown front matter.

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
    "scenarios": ["SearchServiceTest#emptyQueryReturnsEmptyList"],
    "locked": { "src/test/java/com/acme/search/SearchRegressionTest.java": "<git blob sha>" }
  }
}
```

- **Required:** `intent`, `scope.paths`, `oracle.scenarios` (for a bug fix, the failing test).
- **Optional in version 0:** `mustStayTrue`, `failsIf`, `oracle.locked`, `scope.apis`,
  `feature.key`.
- **Later fields** (section 6.7): effects, budget, checkpoints, parent, hold-outs.

Each `mustStayTrue` line points at a locked test, or it is listed as unresolved. `failsIf` is
the approver's own sentence (section 6.4).

**Drafting.** The tool drafts the mandate from what exists: an issue, the agent's plan, an
`intent.md`, a Spec Kit or Kiro spec, or the kit's approved design. Locally a mandate is
optional. The agent never approves.

**The card** is the readable view. The verifier renders it into the pull request's check
summary. It is never committed beside the JSON, so there is no second text that looks approved.

### 6.2 Verdict

| Result | Question | Values |
| --- | --- | --- |
| **Scope** | Did the change stay inside the mandate? | `INSIDE`, `AMENDED`, `OUTSIDE` |
| **Evidence** | What is known about each obligation? | `satisfied`, `violated`, `unknown`, `not run`, each labelled *independent* or *candidate-influenced* |

Every verdict is bound to the mandate's blob SHA, the policy version, the verifier image digest
and the tree of the tested merge result.

**Obligations in version 0.**

| # | Obligation | Source | Cost |
| --- | --- | --- | --- |
| 1 | Changed paths and API operations inside the mandate; locked tests equal the approved hashes; build files, runner configuration and test discovery unchanged from the base | Independent (git objects only) | Seconds |
| 2 | Each scenario's test was discovered, executed and passed; counts of expected, executed and skipped | Candidate-influenced | The existing test run |
| 3 | The witness: tests the change adds or edits fail on the base and pass on the head | Candidate-influenced | Two short runs, three times each |

If build files or runner configuration changed at all, declared or not, obligations 2 and 3 are
`unknown` and the change gets a full review. It is on the security floor anyway.

**Rules for the witness.**

- Only the head's test files are laid over the base, with the base's build configuration.
- The test must compile there, be discovered, and fail by assertion or its declared exception.
  A compile failure or an unrelated error is `unknown`, not a pass.
- Three runs on each side. A randomly failing test passes a single-run witness up to 25% of
  the time and a three-run witness 1.6% of the time.
- On the head, the test must execute a changed production line (Java in version 0, `unknown`
  elsewhere) and no previously passing test may fail.
- Two grades. *Author-written test*: the test arrives with the change. *Pre-approved test*: the
  test's blob was locked in the approved mandate commit, so a person approved it without the
  diff in view and it has not changed since.
- The wording is fixed: "N of M changed tests fail on the base and pass on the head in 3 of 3
  runs. The tests are sensitive to the change. This is not evidence that the bug is fixed."

**New features.** A new scenario test fails on the base simply because the feature is absent,
so the witness says little. The cheap evidence for a feature is, in order: the locked
`mustStayTrue` tests pass on the base and on the head; scenario tests pre-approved in the
mandate; and new production lines that no linked scenario executes, listed as exceptions.

**After version 0.** Hold-outs run by a job outside the repository against this exact commit:
the only *independent* behavioural evidence. Mutation on changed lines through an existing
engine, advisory. For Java, targeting changed lines needs a licensed plugin or a class-level
run filtered afterwards. Structural change and reported fields (agent, model, cost) are listed,
never checked.

No LLM judgement appears among checked facts. Assertion presence is a warning only.

### 6.3 Trust boundary

**What is protected:** the approval, the verifier, the policy, and the scope result.
**What is not:** the outcome of tests that run inside the candidate's own build.

**Reference architecture, GitHub first.**

| Element | Rule |
| --- | --- |
| Pipeline definition | Outside the candidate repository: an organisation ruleset that requires a workflow from a central repository, pinned by commit, on `pull_request` and `merge_group` |
| Verifier | A container image pinned by digest. Never compiled from the checkout |
| Inputs | The base ref comes from the event. Policy and mandates are read at the tip of the base branch through one trusted-tree reader; the diff is taken from the merge base. The mandate id is declared in the pull request and validated, never taken from the branch name |
| Two jobs | Job 1 runs the test command named in base-tip configuration, with no secrets and a read-only token, and uploads raw reports. Job 2 never executes repository code; it reads reports and git objects as data and writes the verdict |
| Issuer | The required-workflow rule itself. Fallback: the team's own GitHub App and a required check pinned to it |
| After merge | A reconciler on the base branch maps every landed tree to a verdict or lists it as an exception. It detects a bypass, including by an exempt administrator; it cannot prevent one |

**Approval binding.** Bound to content, not to commits.

- **Default, one pull request.** The first commit holds only the mandate and the tests it
  locks. An `APPROVED` review counts when the tree at its `commit_id` differs from the merge
  base only in those files and holds the same mandate blob as the head. An approval that lands
  on a commit containing work does not count. Later pushes may dismiss the review; the host
  keeps the record.
- **Option, two pull requests.** The mandate is merged first; "at the tip of the base branch"
  is the approval. For organisations that want the mandate on the protected branch before an
  agent starts. A mandate is single use.
- Reviews by Copilot, by bot accounts, and by the work's author or committers are refused. The
  host's ruleset enforces the approver's role; the verifier does not re-derive team membership.
- An amendment is always approved with the diff in view, so it forces a full review.
- What this proves: the text was approved by that person without the diff in view. It does not
  prove when the work was done.

**Where it holds.**

| Host and plan | What holds |
| --- | --- |
| GitHub organisation on Team or Enterprise; GitHub Enterprise Server | Everything above |
| GitHub public repositories on any plan; private repositories on a paid user account | Approval binding and the own-App issuer only. No required workflows |
| GitHub private repositories on the Free plan; any solo developer | Nothing is enforced. `approval: self`, labelled as such. One account cannot approve its own pull request |
| Jenkins with GitHub as host | A separate, centrally defined verifier job that reads the build's test report and posts through its own App credential. The repository's Jenkinsfile is the candidate's |
| GitLab | Top tier: pipeline execution policy. Below it a maintainer can repoint the CI file. After the gate |

**Known holes.** Shared, long-lived self-hosted runners break the two-job separation. Without
"branch must be up to date" or a merge queue, a verdict can describe a tree that never lands.
An agent's operator is not visible to the host, so it is reported, not checked.

**Security floor.** CI and build files, the policy, agent configuration, authentication code
and dependency manifests always get a full human review. So does any mandate file other than
the one approved in this pull request's first commit and unchanged since.

### 6.4 Approval that is not a rubber stamp

- **Blind first line.** In `sdlc mandate approve`, the approver types "this fails if…" before
  the draft is shown. The tool then shows the draft beside it and the approver marks which
  scenario covers the line. If none does, the line goes to `unresolved` or approval stops. This
  is the one cheap rule against an agent framing its own mandate. A review on the host cannot
  enforce it, so it is a step of the command, and an approver who adds `failsIf` to the
  mandate-only commit does not count as a committer of the work.
- **Requester is not approver** wherever policy asks for independence. An agent is never an
  approver.
- **Counter-anchoring.** Beside the exceptions, the review summary shows one hunk chosen by a
  rule unrelated to them.
- **Measured.** Share of mandates approved unchanged and edits per mandate, at team level.
- **Planted flaws** are used once, inside the pilot, as experiment E-B-min (section 9). They
  become a standing feature only if that experiment shows they work.

### 6.5 Review modes

| Mode | When | Human work |
| --- | --- | --- |
| **Full review** | No approved mandate; scope `OUTSIDE`; any `violated` obligation; build or runner changes; a security-floor path; any amendment | Read the change |
| **Exception review** | Approved mandate; scope `INSIDE`; no `violated` obligation; build and runner unchanged | Read the card, every `unknown`, and one unrelated hunk. The diff is available |
| **Sampled review** | Exception-review conditions, plus at least one *independent* obligation satisfied, plus the class has earned a level (section 6.6) | A drawn fraction (1/2, 1/4, 1/10) gets a full review. The rest merge on the verdict. Never zero |

Rules:

- **No sampling level below 100% without an independent obligation.** Sampling bounds a rate of
  honest errors. A forged result is a single event, and it passes a level of 1/4 three times in
  four. A team with nobody to write hold-outs stays on Exception review.
- **Enforcement decides what a level means.** On GitHub a level below 100% can be enforced only
  at the merge queue (the required workflow on `merge_group` fails unless an eligible human
  approval exists, except for a sampled change that was not drawn) or by an App that approves.
  Private repositories need the top plan for a merge queue. Elsewhere a level is advisory and
  the reconciler reports violations afterwards.
- **The draw is sticky.** It is keyed on the root mandate id and the host's id of the approving
  review, so re-rolling costs a fresh human approval and shows in the record. Where an App
  outside the repository holds a salt, the draw is also unpredictable.
- The mode is judged per root mandate. A change to an API the repository provides is never
  sampled. A vendor's AI approver may approve where the organisation allows; the verdict stays
  the required check and disagreements are recorded.
- Exception review is not claimed to be as safe as reading the diff. Experiment E-A measures
  how much information the exception view loses.

All of this is after the gate. In the 90 days the modes are computed in shadow only.

### 6.6 Ledger

The earlier rule ("59 clean sampled reviews for a 5% bound") had four errors:

1. It bounded the rate at which reviewers *find* problems, not the defect rate. If reviewers
   catch half, 59 clean reviews bound the true rate near 9.9%.
2. A streak that restarts after a finding is not a test. A class with a true 5% rate reaches 59
   in a row after about 392 reviews on average.
3. "Tightened after two findings in five" is a rule about lots, and tolerates 40%.
4. Classes split by agent configuration never collect enough data.

**The procedure.**

- **Event.** A material finding in a sampled full review, or a later escape attributed to the
  change. Escapes count retroactively, on unsampled changes too. A person confirms each
  attribution.
- **Statistic.** A running likelihood ratio between a tolerable finding rate p2 (default 5%)
  and p1 = p2/5. Each clean review adds ln((1−p1)/(1−p2)); each finding subtracts ln 5. It is
  never reset.
- **Promotion.** One level at a time when the sum reaches ln 20. On each promotion ln 20 is
  subtracted and the remainder carries over; findings are never forgotten. The chance of *ever*
  promoting a class whose true finding rate is at or above p2 is then at most 5%, however often
  one looks, assuming changes are independent.
- **Levels.** 100%, then 1/2, 1/4, 1/10. Never zero.
- **Demotion.** When the sum falls below zero, or on a severe escape.
- **Classes.** One pooled class per repository until a proposed sub-class has 100 matured
  observations. A model change is not a class; it doubles the sampling fraction for the next 20
  samples.
- **Maturation.** A sample counts 30 days after merge with no attributed escape.
- **Output.** The finding-rate bound, the defect-rate bound under a stated reviewer
  sensitivity, and the expected extra escapes against reading everything.

| Tolerable finding rate | Clean reviews to move one level | With one finding | With two |
| --- | --- | --- | --- |
| 10% | 36 | 55 | 73 |
| 5% | 73 | 112 | 151 |
| 2% | 185 | 285 | 384 |

**What a five-person team gets** (30 changes a month, 20 eligible, 5%), given an independent
obligation and a host that can enforce a level: reading half from about month five, a quarter
from about month twelve; roughly 70 of 240 eligible full reviews saved in the first year. If
reviewers catch half of what is there and the true rate is 2 to 5%, that costs about 0.3 more
escaped defects per 100 changes at one half. Without the obligation and the host, section 6.5
keeps this team on Exception review. They never get "nobody reads". The plan says so.

**Pruning.** A check is removed only when replaying known faults shows it catches nothing
another check does not. The security floor is never pruned.

**Limits.** Changes are not independent: agents fail in bursts after a model or prompt change.
Escapes on unread changes are under-counted unless someone traces them, so the ledger leans
optimistic. In the 90 days the ledger is this design and a simulation, nothing more.

### 6.7 Designs deferred until after the gate

Kept as design notes; none is built in the 90 days. Each has a trigger.

| Design | Trigger |
| --- | --- |
| Budgets, checkpoints and interim verdicts for runs of hours or days | A pilot user runs agents unattended for more than a working day |
| Sub-mandates a child agent can only narrow; an overlap check between open mandates | A user runs several agents on one repository at once |
| Effects (`dependencies`, `migrations`, `ci`, `network`) as mandate fields; adapters that write an execution profile into vendor controls | A user asks to constrain what an agent may do at run time, and a vendor exposes a stable policy format |
| `resume`, a maintainer's `--contribution` check, `policy infer`, cost per accepted change, the unread-code share | Asked for by a retained user |
| Signing and in-toto statements | Someone is going to verify one |
| Planted flaws as a standing feature | E-B-min shows they separate attentive from inattentive approval |

## 7. Who it is for

| Level | Who | What they run | Approval | What is enforced |
| --- | --- | --- | --- | --- |
| **Local** | One developer, a maintainer | `sdlc check`; optionally `/mandate` and `sdlc check --mandate` | Self, labelled | Nothing. It informs |
| **CI** | A team with pull requests, any agent | The verifier as an advisory report, then as a required workflow | An independent person, bound to the mandate commit | Per the table in section 6.3 |
| **Organisation** | A platform team, a regulated firm | Central policy, the reconciler's coverage report, evidence export | Roles as policy | After the gate |

**The ten-minute experience.**

```text
sdlc check                     # no configuration, no account, no model call, writes nothing
```

It prints, for the current change:

- **The executed statement.** "Of the tests this change adds or edits, N fail before the change
  and pass after it; M pass on both, so they do not test the change; K could not run on the
  base."
- **The integrity diff.** Tests disabled, deleted or emptied; changes to CI, build files,
  dependencies, migrations, secrets.
- **What it did not check.**

`sdlc check --since 30d` replays that over recent commits. One more line wires it as a stop
hook, so an agent cannot report "done" while a new test passes on both sides. The hook never
blocks on `unknown`; otherwise feature work would always be blocked and the hook switched off. Linters,
plan modes and AI review give none of this: a plan nobody checks afterwards, and comments that
are probabilities.

**Packaging.** Java stays. The verifier is the container image. Local use ships as bundles with
their own runtime for Linux x64 and macOS arm64, and one Claude Code skill. A native binary is
tried for one day and adopted only if it needs no reflection configuration and passes
`selftest`.

**Retention** is counted as check runs per agent session, not mandates written.

**Change management and regulated firms.** What fits, and no more:

- The mandate approval is the authorisation record, by a person who is not the requester or the
  implementer. The agent's operator counts as implementer.
- The verdict is part of the verification evidence.
- A class that has earned a level *may be registered* as a standard change model, by the change
  authority, not by a tech lead approving a policy pull request.
- Minimum evidence: every commit on the protected branch maps to a verdict or a logged
  exception; host-authenticated identities and timestamps; the change history of the control
  itself; the bypass list including exempt actors; retention outside the source host.
- Never claimed: compliance; "independent verification" of tests run inside the build; that
  sampled review satisfies a control that asks for review of every change; segregation of duties
  where one person operates the agent and approves the mandate; that a signature shows
  correctness.

**The full kit** (Confluence designs, Jira approvals, the agent flow, hooks, QE hold-outs)
becomes one producer of mandates, for organisations with those roles.

## 8. Features across several repositories

Frozen until the gate (decided 2026-11-14). The mandate keeps `feature.key`, so a feature view
is later a join over unit verdicts without any redesign.

The whole direction is kept in [proposal 03](03-multi-repository-direction.md): what the kit
has today, how an agent finds what to work on, ten gaps in the code, the target design, the
fixes, the failure modes and their measures, the research, and the order of work when it
resumes. In short: deployable units instead of repositories; discovery evidence with its source
and freshness; the union of analysis runs with evidence-based exclusion; contract compatibility
by existing engines; typed prerequisites; release states.

The question that reopens it: ask the pilot team for its last five features that crossed
repositories, and whether an edit outside the agreed slice ever caused harm. If it did, B0
(the discovery evaluation) is the first step.

## 9. The 90 days

**Days 1 to 30: the local wedge.**

- The verifier image and the two local bundles.
- `sdlc check` with the executed statement for Maven Surefire and Vitest, the integrity diff,
  the not-checked list, `--since`, and the stop-hook recipe.
- In the code: the trusted-tree reader, the base ref from the event, a declared mandate id, a
  content hash for the build stamp (the code half of gap 6, and gap 10).
- Ten outside people try it; one team agrees to pilot. This is yours to arrange.
- E-A: sixty tasks selected.
- **Exit:** with only the project's own toolchain installed (so no JDK for a Node change), a
  stranger gets the executed statement for a Java and a Node change within ten minutes. The
  witness, run over at least 30 real bug-fix commits that build under the two supported
  runners (test repositories first), reports how often it is `unknown` or fails for the wrong
  reason.

**Days 31 to 60: the mandate and an advisory report.**

- Mandate version 0; `/mandate` with the blind first line; `sdlc mandate approve`;
  `sdlc check --mandate` for obligations 1 and 2.
- Gradle, and one more runner chosen from the stacks of the people who tried it.
- An advisory pull-request report for the pilot team: pinned image, base ref from the event,
  policy at the base tip, two jobs. It labels itself advisory, and "not trustworthy for this
  pull request" when the change touches the workflow file, `sdlc.yml` or a mandate. No saving
  in review effort is claimed from it.
- Six attacks from a write-access account: an edited `sdlc.yml`, an edited tool, an edited
  workflow or Jenkinsfile, an added job with the check's name, a status with the check's name,
  a changed base branch name. They are run in two places (section 14):
  - *Locally, free:* the three that test the verifier's own inputs (`sdlc.yml`, the tool, the
    base branch name), against local git remotes with the pinned image.
  - *On GitHub:* the three that test what only the host can enforce (the workflow definition
    and the two name spoofs), in an organisation with a required workflow. A 30-day GitHub
    Enterprise Cloud trial provides this without payment.
- Conformance pull requests with expected verdicts, published in `spec/`.
- E-A: first thirty tasks run.
- **Exit:** the three local attacks fail. The pilot team receives reports. The three host
  attacks fail once the trial organisation exists.

**Days 61 to 90: approval, the pilot, the gate.**

- Commit-bound approval in one pull request, with eight replays: squash, rebase, force push
  after approval, Copilot approval, bot approval, an approver who also committed, a merge by an
  exempt actor, stale-approval dismissal switched on. The reconciler and its coverage report.
- The pilot: human minutes instrumented for two weeks of current practice, then four weeks with
  mandates. E-B-min runs inside it.
- One stronger obligation chosen by the pilot's stack: hold-outs per commit, or mutation.
- The exception summary; modes in shadow; the ledger simulation.
- Thirty seeded faults as a bug hunt. No rate is quoted from it: zero misses in thirty would
  bound the miss rate only below 9.5%, and the project wrote the faults.
- E-A: all sixty tasks by day 75.
- **Exit:** the gate decision below.

**Experiment E-A (no people).** Sixty real bug-introducing pull requests from CRBugBench, thirty
that escaped review and thirty caught in review, Java first. The benchmark ships a container per
task; it states no licence, so only task ids and our verdicts are published. A model of one
family drafts each mandate from the issue text at the base commit, blind to the diff. Measured:

1. *Silence*: the share of buggy changes that end scope `INSIDE` with every obligation
   satisfied.
2. *Information*: the recall of a review agent of another family given the full diff, against
   the same agent given only the exception-review view.
3. *Load*: items to read and amendments per mandate.

Thirty per track gives each share to about ±18 points. Fallback corpus for Java: Defects4J
joined to a set of known bug-inducing commits.

**Experiment E-B-min (in the pilot team).** At least four people. Sixteen planted flaws among
about 48 mandate cards. A team that truly catches 30% passes this 7% of the time; one that
catches 70% passes 97% of the time. With a third of the cards planted, flaws are easier to
catch than in real use: a pass is weak evidence, a fail is decisive. A full comparison of catch
rates would need about 300 defective reviews per arm for a 10-point margin and is not
attempted, which is why no safety claim is made.

**Stop and kill rules, written before any result.**

| Doubt | Rule |
| --- | --- |
| Local value | Fewer than five outside installs by day 30: stop feature work and recruit. Fewer than three of ten still running it in week four, twice: Local is dropped as the lead and the check ships only as the verifier's front end. Nothing past the day-60 items is built below that bar |
| The premise | Any one of these ends the lighter review modes and leaves an evidence tool: the exception view's recall in E-A, pooled over the sixty tasks and paired by task, is under half of the full diff's; total human minutes in the pilot, drafting and approval included, are not at least 30% lower than on baseline changes that received a real diff review; fewer than 8 of 16 planted flaws are caught. Where the baseline is no review, the added minutes are reported and no lighter mode is claimed |
| Vendors | If GitHub ships per-task scope enforced by rulesets on GitHub Enterprise Server: narrow to several hosts and regulated evidence |
| Regulated use | Before any Organisation work, a 45-minute walkthrough of one evidence packet with a change manager or internal auditor. If they map it to no control, the regulated pack is dropped and section 7's wording is the ceiling |

**After the gate, in this order if it passes:** approval and enforcement for sampled review;
the ledger on real data; signing where a user verifies; then whichever of section 6.7 and
section 8 a retained user has triggered.

## 10. What to stop or not build

| Stop, freeze or leave to others | Why |
| --- | --- |
| Named wrong implementations ("controls") as an obligation | Cannot be written before the real implementation exists |
| Any claim that test results from the candidate's build are independent | Section 6.3 |
| Any claim that a review mode is as safe as reading the diff | Nobody has measured how safe reading the diff is |
| Promotion on streaks; a level of zero; classes per agent configuration | Section 6.6 |
| LLM judgement among checked facts | Same-family judges accept their own failures |
| New shell guard rules and agent hooks; the eleven-agent choreography; Jira and Confluence as the headline | Fragile, commodity, or one producer among several |
| A hosted app; a sandbox, identity provider, secrets vault, evidence store, portal, code index, deployment controller or planner of our own | All exist. The verifier image and recipes are the product |
| YAML or Markdown front matter for the mandate | Ambiguous parsing in a security file; prose that looks approved and is not checked |
| GitLab, a second agent plugin, self-upgrade for old kits, the live Copilot session, corrections to proposal 01, gaps 7 and 8 | Until a pilot touches them |
| A standards campaign, a foundation, a rename | After an outside user |
| Anything in section 6.7 or section 8 before its trigger | The first 90 days are already full |

## 11. Measures

During the 90 days:

| Measure | Target or use |
| --- | --- |
| Outside installs; people still running the check in week four | Five by day 30; three of ten |
| Check runs per agent session | Retention |
| Share of witness results that are `unknown` or wrong-reason | Soundness of the first feature |
| The six attacks and eight replays | All fail or behave as specified |
| E-A: silence, information ratio, load | The stop rule |
| Pilot: human minutes per accepted change, before and with mandates | The stop rule |
| E-B-min: planted flaws caught | The stop rule |

After the gate: the share of merged agent-made changes carrying an approved mandate and a
current verdict with no `unknown` required obligation; the finding-rate bound per class;
disagreements between a vendor's approval and the verdict; share approved unchanged; the
reconciler's coverage of the protected branch.

## 12. Doubt register

Residual doubt after two review rounds. Where the two reviewers differed, the higher level is
shown.

| # | Doubt | Residual | Settled as | What contains it |
| --- | --- | --- | --- | --- |
| D1 | Is approving a mandate less effort and safe enough? | **High** | Unproven. The claim is split: against real review, fewer minutes; against skipped review, a few minutes buy checked evidence. No safety equivalence is claimed | E-A, E-B-min and the pilot's minutes, with the stop rule |
| D2 | Scope for one maintainer | Medium | 90 days, three blocks, then a gate | The exits of section 9 |
| D3 | Can the trust boundary be built? | Medium | Yes for scope and approval on an organisation plan; not for in-repository test results | The six attacks |
| D4 | Approval binding | Medium | Blob-bound, in the mandate-only first commit | The eight replays |
| D5 | Which evidence is worth its cost? | Medium | Three obligations in version 0, with witness rules and grades | The `unknown` and wrong-reason count on at least 30 real bug-fix commits |
| D6 | Statistics | Medium | Sequential test on the finding rate; never zero. The arithmetic is settled; independence, attribution and enforcement are not | Simulation only until the gate |
| D7 | Would a solo developer keep it? | **High** | The executed statement is the wedge | Ten strangers, three retained; the stop rule |
| D8 | Packaging and format | Low | Java, image and bundles; strict JSON, blob SHA | — |
| D9 | Durability against vendors | **High** | The verifier, the recipes and conformance pull requests; no hosted service | The kill rule |
| D10 | Several repositories | Medium | Frozen; `feature.key` kept; record in proposal 03 | The five-features question to the pilot team |
| D11 | Regulated acceptance | **High** | Narrow wording in section 7; a never-claim list | The walkthrough before any Organisation work |
| D12 | Users before or after the CI verifier? | Low | Before: recruit in week one | — |

Four doubts stay high. None can be removed by more reading; each is contained by a named test
with a rule written before the result. That is what "a clear path" means here: the first 30
days are not in doubt, and every later step has a condition under which it is not taken.

Further doubts raised and where they went: mode enforcement on GitHub (section 6.5);
re-rolling the sample (sticky draw); mandate replay (one pull request, or single use); latency
of two pull requests (one by default); operator identity (reported, not checked); commodity
lint (the executed statement); weak evidence for new features (section 6.2); changes that fail
in bursts and under-counted escapes (section 6.6 limits); language reach (each runner is an
adapter, chosen from users' stacks); the agent framing its own mandate (blind first line).

## 13. Review against the intention

| What we set out to do | How this revision serves it | Gap that remains |
| --- | --- | --- |
| Check the direction | Kept, with the claims narrowed to what can be enforced and measured | Four high doubts, each with a test |
| Value for a company that already has agents | A neutral check across vendors and hosts, with published criteria and typed unknowns | Shown on a pilot team, not yet on a company |
| Useful to normal users | The executed statement in ten minutes, with nothing to configure | Retention is unproven until ten people have tried it |
| Enterprises now and later | The reference architecture, the support matrix, the evidence a change manager needs, a never-claim list | Organisation work waits for the gate and the walkthrough |
| Follow where AI coding is going | Long runs, fleets and run-time effects are designed and parked behind triggers | Not built |
| Features across repositories | Work frozen; the design, gaps, fixes and research are kept in proposal 03 | Unproven beyond a small test bed |
| A solid plan | Every block has an exit; four stop or kill rules are written before any result | Depends on finding ten people and one team |

## 14. Decisions

Taken on 2026-11-13 and 2026-11-14, still valid: redefine the project around authorising and
verifying agent-made changes; `mandate` as the artifact's name; the local test bed; no online
Jira anchor; lead with the Local level; sampled audits instead of a one-click approval; a pilot
gate before the later work; Java, shipped as an image and bundles.

Changed by this revision, within those decisions:

| Decision | Was | Now |
| --- | --- | --- |
| Trust anchor | A separate mandate pull request | One pull request with a mandate-only first commit by default; two as an option |
| Pilot gate | Two outside users | Ten people trying Local with three retained, and one pilot team |
| Lightest mode | No human per change | A drawn fraction always read in full; never zero |

Taken on 2026-11-14 after the expert rounds:

| Decision | Choice | Consequence |
| --- | --- | --- |
| Multi-repository work | Frozen until the gate, with the direction and its details kept on record | [Proposal 03](03-multi-repository-direction.md) holds the record and the question that reopens it. This replaces the earlier decision to run the evaluation and catalog fixes now |
| Testing the trust boundary without paying | Local git remotes for what the verifier itself controls; a 30-day GitHub Enterprise Cloud trial for what only the host can enforce | Private or local repositories cannot stand in for an organisation ruleset: the rule is GitHub's, not ours. The trial needs no payment method and gives up to 50 licences. Two accounts are needed, because one account cannot approve its own pull request. Start it at day 31, not earlier, so the 30 days cover the host tests and the approval replays |

Still yours to arrange: the ten people who try the local check and the one pilot team
(recruiting starts in week one); a second GitHub account for the approver role.

## 15. Sources

**V** checked by me at the source on 2026-11-14. **R** read at the source by a researcher or
reviewer. **S** summary only. Not retrievable: Vercel's post (404).

- Review skipped or delegated:
  [Faros, 2026-09-18](https://www.faros.ai/blog/ai-speed-trap-takeaways) V;
  [On Autopilot?, arXiv 2601.13754](https://arxiv.org/abs/2601.13754) V (abstract);
  [Copilot approvals, 2026-10-28](https://github.blog/changelog/2026-10-28-copilot-code-review-merge-with-confidence-with-max-and-approvals) V;
  [Cursor approval agents](https://cursor.com/docs/approval-agents) V;
  [Meta RADAR, arXiv 2605.30208](https://arxiv.org/abs/2605.30208) V (abstract);
  [Rewind](https://rewind.com/blog/ai-approve-pull-requests-safely/) R;
  [Tessl](https://tessl.io/blog/75-of-tessls-prs-now-merge-without-human-review) R;
  [Bain 2026](https://www.bain.com/insights/the-missing-architecture-for-agentic-software-development-technology-report-2026/) R
- Human oversight and the premise:
  [review agents on bug detection, arXiv 2610.13668](https://arxiv.org/html/2610.13668) R;
  [LLM review anchoring, arXiv 2411.11401](https://arxiv.org/abs/2411.11401) S;
  [plans as faithful proxy, arXiv 2606.05391](https://www.alphaxiv.org/abs/2606.05391.md) R;
  [spec-driven development in practice, arXiv 2608.25202](https://arxiv.org/html/2608.25202v4) R;
  [METR, early-2025 study](https://metr.org/blog/2025-07-10-early-2025-ai-experienced-os-dev-study/) R;
  [automation bias](https://pubmed.ncbi.nlm.nih.gov/21077562/) S;
  [prevalence effect](https://pmc.ncbi.nlm.nih.gov/articles/PMC4224304) S
- Oracles and verification:
  [SWT-bench, arXiv 2406.12952](https://arxiv.org/html/2406.12952v3) R;
  [All Smoke, No Alarm, arXiv 2606.18168](https://arxiv.org/html/2606.18168v1) R;
  [ImpossibleBench](https://arxiv.org/html/2510.20270v1) R;
  [judge false acceptance, arXiv 2604.06996](https://arxiv.org/html/2604.06996v3) R;
  [Meta mutation, arXiv 2501.12862](https://arxiv.org/html/2501.12862v1) R;
  [mutation at Google, arXiv 2102.11378](https://arxiv.org/pdf/2102.11378) S;
  [Arcmutate git integration](https://docs.arcmutate.com/docs/git-integration.html) R;
  [SWE-bench Verified audit](https://openai.com/index/why-we-no-longer-evaluate-swe-bench-verified/) R
- Statistics:
  [exact binomial bound, NIST](https://itl.nist.gov/div898/handbook/prc/section2/prc241.htm) R;
  [Z1.4 switching rules](https://www.sqconline.com/switching-rules-mil-std-105e-z14) R;
  [continuous sampling plan CSP-1](https://metricgate.com/docs/csp-1-continuous-sampling-plan-dodge/) S;
  [dormant bugs, MSR 2014](https://cs.uwaterloo.ca/~m2nagapp/publications/publications/An-Empirical-Study-of-Dormant-Bugs.html) S
- Experiment corpora:
  [CRBugBench](https://github.com/malusamayo/CRBugBench) V;
  [Defects4J](https://github.com/rjust/defects4j) R;
  [InduceBenchmark](https://github.com/justinwm/InduceBenchmark) R
- Trust boundary and hosts:
  [organisation rulesets need Team or Enterprise](https://docs.github.com/en/organizations/managing-organization-settings/creating-rulesets-for-repositories-in-your-organization) V;
  [available ruleset rules, including required workflows](https://docs.github.com/en/enterprise-cloud@latest/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets) R;
  [`pull_request_target` blocked by default](https://github.blog/changelog/2026-11-02-pull_request_target-blocked-by-default-december-2) V;
  [GitHub pricing](https://github.com/pricing) R;
  [Enterprise Cloud trial](https://docs.github.com/en/enterprise-cloud@latest/admin/overview/setting-up-a-trial-of-github-enterprise-cloud) V;
  [pull request reviews API](https://docs.github.com/en/rest/pulls/reviews) R;
  [issue event types](https://docs.github.com/en/rest/using-the-rest-api/issue-event-types) R;
  [merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue) R;
  [Actions settings and token permissions](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository) R;
  [artifact attestations](https://github.com/actions/attest) R;
  [Jenkins: securing multibranch pipelines](https://www.jenkins.io/doc/book/security/securing-org-folders-and-multibranch-pipelines/) R;
  [GitLab pipeline execution policies](https://docs.gitlab.com/user/application_security/policies/pipeline_execution_policies/) R
- Security and identity:
  [Invariant, issue injection](https://invariantlabs.ai/blog/mcp-github-vulnerability) R;
  [Nx postmortem](https://nx.dev/blog/s1ngularity-postmortem) R;
  [approval as attack surface, arXiv 2610.22987](https://arxiv.org/html/2610.22987v1) R;
  [A2A, in-task authorisation scope](https://a2a-protocol.org/latest/specification/#764-in-task-authorization-scope) R;
  [SLSA v1.2 source](https://slsa.dev/spec/v1.2/source-requirements) R
- Change management and regulation:
  [ServiceNow change types](https://www.servicenow.com/docs/r/it-service-management/change-management/change-types.html) S;
  [DORA RTS Art. 17](https://www.springlex.eu/en/packages/dora/rts-rmf-regulation/article-17/) R (mirror);
  [EU AI Act summary](https://digital-strategy.ec.europa.eu/en/policies/regulatory-framework-ai) R;
  [VirtusLab on banks](https://virtuslab.com/blog/ai/how-banks-govern-autonomous-ai-software-development/) R
- Neighbouring tools and vendors:
  [IntentSpec v1.2](https://intentspec.org/spec) V;
  [tamperguard](https://pypi.org/project/tamperguard/) R;
  [Claude Code hooks](https://code.claude.com/docs/en/hooks) R;
  [GitHub Universe 2026 coverage](https://www.infoworld.com/article/4242710/github-bets-on-multi-agent-software-development-with-copilot-overhaul.html) R;
  [Qodo ticket compliance](https://docs.qodo.ai/qodo-documentation/qodo-merge/integrations/ticketing-integrations) S;
  [CodeRabbit validation](https://docs.coderabbit.ai/issues/pr-validation) S
- Trajectory:
  [METR limits of time horizons](https://metr.org/notes/2026-01-22-time-horizon-limitations/) R;
  [SlopCodeBench, arXiv 2603.24755](https://arxiv.org/abs/2603.24755) R;
  [Cursor, scaling agents](https://cursor.com/blog/scaling-agents) R;
  [Osmani, software factories](https://addyosmani.com/blog/software-factories/) R
- Normal users:
  [Stack Overflow 2026](https://survey.stackoverflow.co/2026/technology/data/disruption) R;
  [Spec Kit](https://github.com/github/spec-kit) R; [OpenSpec](https://github.com/Fission-AI/OpenSpec) R;
  [tldraw on contributions](https://tldraw.dev/blog/stay-away-from-my-trash) R
- Several repositories:
  [CrossSWE, arXiv 2611.01484](https://arxiv.org/html/2611.01484v1) R;
  [oasdiff](https://github.com/oasdiff/oasdiff/blob/main/docs/BREAKING-CHANGES.md) R;
  [Buf breaking](https://buf.build/docs/breaking/) R;
  [Pact can-i-deploy](https://docs.pact.io/pact_broker/can_i_deploy) R
