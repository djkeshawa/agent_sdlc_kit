# 8. Features across several components and repositories

The flow in [docs/00](00-how-it-works.md) delivers a story inside one repository. This page
adds what a feature that spans several components needs. The design is in
[proposals/01](proposals/01-multi-component-delivery.md). Everything here is **off until you use
it**: a repository without a catalog and without `lanes.enabled` works exactly as before.

## Phase 0: the scoping eval

Before building on agents that pick "which components change", measure how well they do it on
your own history. The eval is a regression set: run it again after every change to a prompt,
an agent, a model or the catalog.

```bash
# 1. ground truth and inputs for past features that touched several repositories (needs gh and Jira)
java .agent/Sdlc.java eval collect PAY-10 PAY-42 PAY-77
```

For each epic, the tool searches GitHub for commits that mention the epic's key or one of its
stories' keys (`github.org` limits the search), and writes:

| File | Content | Agents |
| --- | --- | --- |
| `docs/agent/eval/scoping/cases/<EPIC>.md` | the epic's Jira text, one pager and HLD: the input | read |
| `.sdlc/hidden/eval/truth/<EPIC>.yml` | `components:` that really changed, the repositories and commit counts | **never** (`paths.hidden`) |

Review each truth file: remove a repository whose commit only mentioned the key in passing.
Repository names map to component names through the catalog, or `github.relatedRepos` (`name`
for `repo`). Commit the cases. The truth is never committed to a repository agents work in
(`git show` or `git log -p` would reveal it): keep a copy where QE keeps the hold-out tests, and
copy it into `.sdlc/hidden/eval/truth/` before scoring.

```text
# 2. answers: in Copilot Chat, Claude Code or Codex, k times with different run names
/eval-scoping baseline-1
/eval-scoping baseline-2
/eval-scoping baseline-3
```

`eval-runner` starts a fresh `impact-analyst` per case (no shared context) and saves each
answer as `.sdlc/eval/inbox/<run>/<EPIC>.yml` (`components: [...]`). A solution draft with an
`# sdlc-solution` block is also accepted as an answer, so `/solution` drafts can be scored too.

```bash
# 3. score
java .agent/Sdlc.java eval score              # every run in .sdlc/eval/inbox/
java .agent/Sdlc.java eval score --runs baseline-1,baseline-2
```

The report (`.sdlc/hidden/eval/report.md`, hidden from agents because it shows the truth) gives:

| Measure | Meaning |
| --- | --- |
| Recall | share of the true components the answer named. A missed component is found late, in another team's sprint: it matters most |
| Precision | share of the named components that were right. Extra components cost review time |
| pass@k | some run named exactly the right set |
| pass^k | every run named exactly the right set: what you can rely on |
| Consistent | every run gave the same answer, right or wrong |

**Decision point.** If a plain `impact-analyst` (GitHub MCP, no catalog) already gets nearly
every case right in every run, you need less of what follows. If not, the catalog and the
dependency graph are justified by your own numbers, and this set measures them.

## Phase 1: catalog, solution design, component stories, lanes

### The catalog: which components exist and what they own

Each repository describes itself in `catalog-info.yaml` at its root, in
[Backstage's format](https://backstage.io/docs/features/software-catalog/descriptor-format)
(it works with or without Backstage). Start from `.agent/templates/catalog-info.yaml`; `/analyse`
drafts one into `docs/agent/catalog-info.draft.yaml` for the team to review. Two annotations
hold what a scan cannot know:

| Annotation | Example | Used for |
| --- | --- | --- |
| `sdlc/owns` | `"payment, refund"` | domain nouns this component is the source of truth for: the first candidates when a requirement mentions them |
| `sdlc/invariants` | `"refunds never exceed the captured amount"` | rules every design must keep (semicolon separated) |

The `kind: API` entries name the contract files (`spec.definition.$text`). Those files are the
component's **contract files**: lanes and (phase 2) the PR checks watch them.

```bash
java .agent/Sdlc.java catalog check          # this repository's file is complete; its API files exist
java .agent/Sdlc.java catalog sync           # copies github.relatedRepos' (and catalog.repos') catalog files and API specs into .sdlc/catalog/
java .agent/Sdlc.java catalog scan --write   # dependencies found in this code; --write saves docs/agent/catalog-edges.json (commit it)
java .agent/Sdlc.java catalog graph          # .sdlc/catalog/graph.md: every component, API operation and scanned dependency, for agents
```

`catalog-info.yaml` is a protected path: agents cannot edit it; CODEOWNERS reviews human changes.

### How dependencies are found

| Source | What it gives | How |
| --- | --- | --- |
| Declared | `providesApis`, `consumesApis`, `dependsOn`, the API files | `catalog-info.yaml` in each repository |
| Static scan | Java: `@FeignClient` (+ its `@GetMapping`...), `WebClient` / `RestClient` / `RestTemplate` calls, `@KafkaListener` and `KafkaTemplate.send` topics, `@JmsListener`, `@RabbitListener`, `convertAndSend`, service URLs in `application*.yml`. Node: `fetch`, `axios`, kafkajs `subscribe` / `send`. `${...}` placeholders are resolved from `application*.yml` / `*.properties` | `catalog scan` |
| Code search | reverse lookups ("who else uses topic X") for repositories not in the catalog | the `impact-analyst` with GitHub MCP or `gh search code` |

A call is matched to the component whose API file has that operation (method + path template,
or topic). `catalog scan` warns about a use of another component that `catalog-info.yaml` does
not declare. It is a regular-expression scan: it shows the file and line of every finding so a
human can check it, and it misses calls built in unusual ways.

### Lanes

With `lanes.enabled: true`, the LLD's `# sdlc-tasks` block says how much process the story needs,
and the tool checks it against the facts:

| Lane | When | Checked |
| --- | --- | --- |
| `patch` | one repository, no contract file changes | `tasks create`: no task scope covers a contract file. PR policy: no contract file in the diff |
| `component` | one repository, a task changes a contract file it provides | |
| `cross-component` | a parent epic's solution design names this repository's component | `tasks create`: the solution is approved, it names this component, and the LLD's `solutionSlice:` is the current slice hash |

`java .agent/Sdlc.java classify <KEY>` prints the lowest lane the facts allow, and why. A human may
raise the lane in the LLD; the tool refuses a lower one.

### The cross-component flow

```
Epic + one pager / HLD
  /solution EPIC                 architect agent: requirements (EARS), impact-analyst x3, contracts, order
     -> "<EPIC> - Solution design" page (confluence.publish.solution) with a # sdlc-solution block
     publish is refused while a component is disputed, a requirement is uncovered, a removal
     is not marked breaking, or a component / API is not in the catalog
  HUMAN GATE: the architect posts the approval line on the epic (gates.approvers.solution)
  /test-design EPIC              QE: scenarios from the requirements, each with "- Components:"
  java .agent/Sdlc.java stories create EPIC
     -> one Jira story per component ("[payments-service] ..."), with its slice and slice hash
  each team, in its own repository:
     java .agent/Sdlc.java docs pull STORY   -> sources/solution-slice.md (slice + hash)
     /design STORY                            -> LLD with lane: cross-component, solutionSlice: <hash>
     HUMAN GATE: the team's tech lead
     /feature STORY                           -> today's loops
  java .agent/Sdlc.java feature status EPIC  -> solution approval, stories, LLD approvals, slices current or OUTDATED
```

**When the solution changes.** Publish a new version and approve it again, then run `stories
create EPIC` again. Each component's slice has its own hash: stories whose slice changed get a
comment that their LLD must be updated and approved again; `tasks create` refuses an LLD that
carries an old hash, and `feature status` shows it as OUTDATED. Teams whose slice did not change
are not disturbed. A designer who finds the slice wrong writes `.sdlc/<STORY>/inbox/scr-1.md`
and stops; the architect changes the solution.

| Command | Does |
| --- | --- |
| `docs publish <EPIC> solution --file <draft.md>` | validates the block, publishes, asks for approval on the epic |
| `approval line\|check <EPIC> solution` | the approval line / whether it is approved |
| `stories create <EPIC>` | refused until approved; idempotent; comments on stories whose slice changed |
| `feature status <EPIC>` | exit 0 only when the solution and every component LLD are approved and current |
| `classify <KEY>` | lowest lane and why |

## Phase 2: tests the implementer cannot game, and contract checks

Coding agents pass tests they can see in ways that do not meet the requirement: editing the
test, or special-casing its inputs. Two kinds of tests close that gap.

### Locked acceptance tests

```
qa-verifier (job A)   drafts acceptance tests in .sdlc/<STORY>/drafts/acceptance/<repository path>
QE person             reviews them, then: java .agent/Sdlc.java tests lock <STORY>   (agents are refused)
                      -> copies them into the repository, writes docs/test-locks/<STORY>.json (hashes)
implementer           makes the code pass them; may not change them
```

`tests lock <STORY> --paths <glob,glob>` locks test files that already exist (written by QE by
hand). Locking again keeps the earlier files and updates the hashes. With
`tests.lockAcceptance: true`, `task start` waits until the story has a lock.

| Check | Where |
| --- | --- |
| No agent edits a locked test (edit tools and shell writes) | agent hooks |
| A commit that changes or deletes a locked test is refused | pre-commit |
| `verify` refuses while a locked test differs from its lock | verify |
| FAIL when a locked test differs; WARN when a lock file changed | PR gate, `check-pr` in CI |
| QE reviews lock changes | CODEOWNERS on `docs/test-locks/` |

The check is on the file's content, not on who changed it, so it holds in every coding tool.

### Hold-out scenarios

QE marks some scenarios `- Hold-out: yes` in the test design (typically boundaries and
combinations), writes them as black-box API or end-to-end tests in **QE's own repository**, and
runs them in its own Jenkins job against the PR build. They never exist in the component's
repository, so no agent can read or tune for them.

```bash
java .agent/Sdlc.java holdout status <STORY> [--build N]
```

reads only the test names and pass/fail from `holdout.jenkinsJob` (the error details, which can
show inputs, are never requested), maps them to `TS-..` ids, and writes a digest with ids,
titles and results to `.sdlc/<STORY>/rounds/holdout-N.md`. Scenarios marked hold-out that did
not run show as NOT RUN. Visible tests passing while hold-out scenarios fail is the signal that
the code meets the tests, not the requirement: review against the requirement, not the hidden test.

### Contract checks in the PR policy

With a `catalog-info.yaml`, every pull request that changes one of this repository's contract
files is compared with the base branch (OpenAPI 3 / 2 paths, AsyncAPI 2 / 3 messages):

- `tasks create` (lanes on) writes `docs/agent/contracts/<STORY>.json`: what the story's lane
  allows. Patch: nothing. Component: additions to its own APIs. Cross-component: exactly the
  operations its slice lists, and breaking changes only if the solution says `compatibility: breaking`.
  Commit it with the story; it is protected, so agents cannot widen it.
- `check-pr` and the PR gate fail an added or removed operation the allowance does not list,
  and a breaking change it does not allow. In CI the story comes from the branch name.
- Breaking changes detected: removed operations or messages, removed response codes, new
  required request fields or parameters, removed enum values, changed property types, removed
  response properties. Without lanes they are warnings. Limits: [docs/06](06-guardrails-reference.md#limits-of-the-multi-component-checks).

### Mutation testing

`build.mutation` runs after the build in `verify`; see `snippets/contracts-and-mutation.md`
for PIT and Stryker on changed code, and for consumer-driven contract tests.
