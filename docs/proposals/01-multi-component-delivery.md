# Proposal: multi-component, multi-repo delivery

Status: implemented (phases 0-3) on branch feat/multi-component-delivery; the guide is
[docs/08](../08-multi-component.md). Section 15 lists what changed in each design round. Where the
implementation differs: the scoping eval's ground truth lives in `.sdlc/hidden/` (never in git,
where `git show` would reveal it), and `docs/agent/contracts/<STORY>.json` carries each story's
allowed contract changes so `check-pr` can enforce them in CI without Confluence access.

## 1. The short answer

Add a **solution design** at the epic level (which components change, which contracts change)
above the existing per-team **LLD**, and feed QE and each team's design from it. Then:

1. **Lanes**: only cross-component work gets the new steps; a one-repo change keeps today's flow.
2. **Jira carries the structure**: epic = feature (solution design, test design), one story per
   impacted component (created by the tool from the approved solution), sub-tasks as today. The
   kit's existing page lookup (story -> epic) already finds the epic's pages.
3. **Components and dependencies come from a federated catalog** (one `catalog-info.yaml` per repo,
   Backstage format) and a dependency graph the tool builds, not from an agent searching code.
4. **Impact analysis runs k times**; components not found by every run are disputed until the
   architect decides.
5. **Contracts are files and the tool checks them**: a provider's spec diff must match the
   approved solution; consumers build in parallel against mocks.
6. **Tests the implementer cannot game**: white-box acceptance tests are hash-locked; a black-box
   hold-out set lives in the QE repo and runs in QE's own pipeline.
7. **Measure first**: Phase 0 scores a plain agent on past multi-repo stories. If that is already
   good enough, the catalog is not worth building.

## 2. The test applied in round 3: does it keep the kit's principles?

| Principle of the kit | What it means for this proposal |
| --- | --- |
| Agents do the work, tools check it, humans decide | Every new rule names the command or hook that enforces it (section 9). A rule only an agent reads is not a rule |
| Use what the organisation already has | Jira hierarchy for structure, Confluence for designs, Jira for status, Backstage format for the catalog. No new server; the central `architecture` repo from round 2 is gone |
| The kit only reads CI | New checks go into the existing optional `check-pr` stage. Hold-out tests run in QE's own pipeline, which the kit reads like Jenkins |
| Copy one folder, one config file, no build | All new behaviour is off by default; a repo with no catalog works exactly as today |
| Same rules for Copilot, Claude Code and Codex | Nothing depends on one tool's hook fields; role-aware refusals are an extra where the field exists |
| Tested honestly | Each phase says how it will be proven, in the style of the README's "What was tested" |

## 3. What the kit already does that this builds on

Checked in the code:

- `docs pull` follows Jira parents (sub-task -> story -> epic) and finds pages by key, so an
  epic-level solution design is found from every story under it with no change.
- An approval counts on the issue that owns the page (`Approvals.java`), so the architect
  approves the solution on the epic.
- `tasks create` turns a machine block into Jira sub-tasks; `IssueTracker.children()` lists an
  issue's children. Creating stories from the solution is the same pattern one level up.
- Every commit carries the Jira key (`commitPattern`), so cross-repo history by key is searchable.
- `repos sync` makes **shallow** clones (`--depth 1`): no history. Co-change data has to come from
  GitHub's commit search, not from those clones.
- The guard does not know which agent is calling. Claude Code sends `agent_type` for subagent tool
  calls; Copilot does not reliably. Rules about "the implementer may not edit X" must be enforced
  by content (hashes), with role checks only as an extra.
- `check-pr` is already an optional Jenkins stage; it is where new PR-level checks go.

## 4. How the work maps onto Jira and Confluence

```
Epic PAY-100  "Refunds"                       <- the feature
  Confluence: "PAY-100 - Solution design"     architect agent, approved by the architect on PAY-100
  Confluence: "PAY-100 - Test scenarios"      QE, approved by the QE lead on PAY-100
  |
  |-- Story PAY-101  payments-service          created by `sdlc stories create PAY-100`
  |     Confluence: "PAY-101 - LLD - payments"  approved by payments' tech lead
  |     Sub-tasks PAY-105, PAY-106 ...          created by `sdlc tasks create PAY-101` (today)
  |
  |-- Story PAY-102  orders-service
        Confluence: "PAY-102 - LLD - orders"
        Sub-tasks ...
```

Each story's description holds its slice of the solution and a link to the page. Teams work on
their own story in their own repo; nothing about the per-repo flow changes except its inputs.

## 5. Delivery lanes

| Lane | When | Flow |
| --- | --- | --- |
| **Patch** | One repo, no change to a declared contract or schema | today's flow on the story |
| **Component** | One repo, additive change to a contract it provides | today's flow + contract task and checks |
| **Cross-component** | Two or more components, or a breaking contract change, or a data change across owners | epic flow, section 6 |

The lane is suggested by the agent, then **checked by the tool where it can be**:

- `tasks create`: a task scope that includes a contract file declared in the catalog requires
  at least the component lane.
- PR gate and `check-pr`: a diff that changes a declared contract file in a patch-lane story is
  refused; a breaking change (section 7.6) outside the cross-component lane is refused.
- A human can raise the lane, never lower it below what the tool found.

## 6. The cross-component flow

```
Epic + BRD / one pager (product)
   |
   |  /solution EPIC          architect agent
   |     1. requirements: BRD -> R-.. (EARS form); questions back to product
   |     2. impact analysis: k independent runs over catalog + graph; disputes listed
   |     3. contract deltas, order, decisions (ADR style), sequence
   |  HUMAN GATE: architect approves on the epic
   |
   |  /test-design EPIC       QE; may start from the BRD in parallel
   |     scenarios from the requirements, tagged with components; some marked hold-out
   |  HUMAN GATE: QE lead approves on the epic
   |
   |  sdlc stories create EPIC   one story per component (refused until the solution is approved)
   |
   |  per story, per repo, in parallel:
   |  /design STORY  -> LLD from its slice; `tasks create` checks conformance; tech lead approves
   |  /feature STORY -> today's loops; contract task first in providers; consumers use mocks
   |
   |  sdlc feature status EPIC   from Jira: stories, LLD approvals, PRs, CI, hold-out results
   |  QE pipeline: cross-component and hold-out scenarios against the PR builds
   |  HUMAN GATE: merge and deploy in the solution's order
```

QE writes scenarios from the requirements, not from the design, so a design mistake does not
pass its own tests. A requirement that no component covers is a solution defect, found before code.

## 7. The pieces

### 7.1 Federated catalog

Each repo owns a `catalog-info.yaml` in Backstage's format (kind `Component` and kind `API`, with
`providesApis`, `consumesApis`, `dependsOn`), reviewed by CODEOWNERS like any guardrail file.
If you run Backstage, it reads the same files; if not, nothing else is needed.

Facts a scan cannot know go in annotations:

```yaml
apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: payments-service
  annotations:
    sdlc/owns: "payment, refund, payment-method"          # domain nouns it is the source of truth for
    sdlc/invariants: "a captured payment is never deleted"
spec:
  type: service
  owner: team-payments
  lifecycle: production
  providesApis: [payments-api, refund-events]
  consumesApis: [orders-api]
```

`/analyse` and `sdlc catalog scan` write the draft; the team confirms `owns` and `invariants`.
`sdlc catalog sync` collects the files of the org's repos (GitHub code search for
`catalog-info.yaml`, or `github.relatedRepos`) into `.sdlc/catalog/`.

### 7.2 Dependency graph

| Source | What it gives | How |
| --- | --- | --- |
| Declared | `providesApis` / `consumesApis`, OpenAPI and AsyncAPI files | catalog files |
| Static scan | Java: client artifacts in `pom.xml`/Gradle; `@FeignClient`, `WebClient`, `RestClient` with base URLs from `application*.yml`; `@KafkaListener` and `KafkaTemplate.send` topics; queues; shared DB schemas. Node: client packages, base URLs from env config, kafkajs topics | `sdlc catalog scan` in each repo (local, or in `check-pr`) |
| Co-change | Repos changed together for the same Jira key or epic | GitHub commit search by key (`gh api search/commits`), since local clones are shallow |
| Code search | Reverse lookups for repos not in the catalog: "who else uses topic `order-events`" | GitHub MCP or `gh search code`, read-only |
| Runtime traces | Real calls between services | optional import from your APM's service map |

Edges are matched on the contract (method + path, topic, queue, schema) and keep their evidence
(file and line, or commit). A scan that finds a call the catalog does not declare reports it, and
`check-pr` warns until the catalog file is updated. Pairs that change together often with no
declared edge are reported as likely hidden coupling (shared library, table or copied DTO).

### 7.3 Requirements

`/solution` turns the BRD into `R-..` in EARS form ("When <trigger>, the <system> shall
<response>"), with ambiguities sent back to product as questions. QE and every LLD use the same
ids; the tool checks that every requirement has a component and at least one scenario. In the
patch and component lanes the test design keeps owning the requirement list, as today.

### 7.4 Solution design page

Short on purpose: people review generated specs reluctantly. Human sections (context, decisions
with options, sequence, rollout) plus the block the tool reads:

```yaml
# sdlc-solution
lane: cross-component
requirements: [R-01, R-02, R-03]
components:
  - name: payments-service
    role: provider
    change: new refund endpoint and RefundRequested event
    requirements: [R-01, R-02]
    evidence: [src/main/java/com/acme/payments/PaymentService.java:42]
  - name: orders-service
    role: consumer
    change: show refund status on the order
    requirements: [R-03]
ruledOut:
  - {name: notification-service, reason: already notifies on any order-events status change}
disputed: []
contracts:
  - {provider: payments-service, api: payments-api, add: ["POST /orders/{orderId}/refunds"], compatibility: additive}
  - {provider: payments-service, api: refund-events, add: ["RefundRequested v1"], compatibility: additive}
order: [payments-service, orders-service]
```

Checks before it can be approved: components exist in the catalog; every requirement covered;
every consumed API is provided by a listed component or exists already; `disputed` is empty.
`stories create` puts each component's **slice** (its entry, its contracts, its neighbours'
contracts, its requirements, its scenarios) in the story. The slice has its own hash.

### 7.5 Impact analysis

Run the `impact-analyst` subagent k times (default 3), each with fresh context and the same inputs
(requirements, catalog, graph); use at least two model families when available (`config apply`
generates one agent file per configured model, so this works in Copilot too). Components found by
every run are candidates; the rest go to `disputed` with each run's reasoning. The architect
decides. The agent then confirms each candidate with evidence in code, or rules it out.

### 7.6 Contracts

- **T0 in the provider**: commit the spec change first. The PR gate and `check-pr` parse the spec
  diff (OpenAPI and AsyncAPI are YAML; the kit has a parser) and compare the operations and
  messages added or removed with the story's slice. Anything not in the slice is refused; it needs
  a solution change request (7.8).
- **Breaking-change check, built in and limited**: removed operations or messages, removed
  response codes, new required request fields, removed enum values, and type changes. The kit says
  plainly what it does not detect; teams that need more can add `oasdiff` to their own CI.
- **Consumers build in parallel** against a mock from the provider's spec on its feature branch
  (`repos sync` with a branch; Prism or WireMock), not waiting for the provider to merge.
- **Contract tests** in the provider's build: Spring Cloud Contract for Java-only estates, Pact if
  Node is involved.

### 7.7 Tests the implementer cannot game

Coding agents game tests they can see. SpecBench (2026) found every frontier agent passed the
visible tests while the gap to held-out tests grew by about 28 points per tenfold increase in
code size; EvilGenie reports agents editing tests and hard-coding outputs. Two kinds of tests:

1. **Locked white-box tests (in the component repo).** `qa-verifier` turns the story's approved
   scenarios into acceptance tests before implementation; `sdlc tests lock STORY` records their
   hashes. `verify`, the pre-commit hook, the PR gate and `check-pr` refuse when a locked file
   changed. This does not depend on knowing which agent edited it. Where the hook knows the role
   (Claude Code's `agent_type`), the edit is also refused at once with the reason. Only QE re-locks.
2. **Hold-out black-box tests (in the QE repo).** Scenarios marked hold-out are written as API or
   e2e tests against the running service, so they need nothing compiled into the component and
   never sit in its repo. QE's own pipeline runs them against the PR build (or all PR builds
   together for cross-component scenarios). `sdlc ci wait` reads that result like Jenkins, and the
   digest agents see shows only the scenario id, title and pass/fail, never the test code or its
   inputs.

The PR body and the retro show visible vs hold-out pass rates. A gap is a review signal, not an
automatic fail. Optional: **mutation testing on changed code** (PIT's changed-lines mode for Java,
Stryker for Node); Meta found that about half of the useful tests its mutation-guided generator
wrote (277 of 571) would have been discarded under a line-coverage criterion.

### 7.8 Change control

- A designer or implementer that finds the solution wrong writes a **solution change request**
  (`.sdlc/<KEY>/inbox/scr-N.md`), reports it and stops.
- A new solution version needs a new architect approval. Stories whose slice hash changed lose
  their LLD approval and get the new slice; the others keep theirs.
- The retro counts change requests, contract changes after approval, and scenarios added after
  coding began: the leading signs of a weak spec.

### 7.9 Feature status

`sdlc feature status EPIC` reads it all from what already exists: the epic's stories (Jira), their
LLD approvals (Confluence + Jira), their PRs (already linked on the story), CI digests and the QE
pipeline result. No separate manifest file to keep in sync.

## 8. Living vs per-change artifacts

Böckeler's review of Kiro, spec-kit and Tessl found most spec tools write a spec per change and
then abandon it, and that treating prose specs as the source of truth brings back the rigidity of
model-driven development without its determinism. Only machine-checked things live on here:

| Living (kept true by checks) | Per change (history once merged) |
| --- | --- |
| `catalog-info.yaml`, dependency edges | Solution design, test design, LLDs |
| Contract files, contract tests | Stories' slices, change requests |
| Locked acceptance tests, hold-out tests | Review and CI rounds |
| ADRs | |

## 9. Where each new rule is enforced

| Rule | Enforced by | Can be skipped by |
| --- | --- | --- |
| No stories before the solution is approved | `stories create` | nobody (the tool refuses) |
| LLD changes no contract outside its slice | `tasks create`; PR gate; `check-pr` | `check-pr` stage not configured |
| Lane at least what the diff implies | `tasks create`; PR gate; `check-pr` | as above |
| No breaking change outside the cross-component lane | PR gate; `check-pr` | as above; detection limits listed in 7.6 |
| Locked tests unchanged | agent hook (role-aware where possible); pre-commit; `verify`; PR gate; `check-pr` | `--no-verify` (refused to agents) and no `check-pr` stage |
| Hold-out code not visible to agents | it is not in the repo; read rules for `.sdlc/` copies; redacted digest | a human copying it in |
| Catalog matches the code | `catalog scan` in `check-pr` (warning) | warnings ignored by reviewers |
| Requirements covered by components and scenarios | `docs publish` of solution and test design | nobody |

As today: layer 1 (agent hooks) makes agents fast and honest, the other layers make it hold.
Add `check-pr` to Jenkins if you adopt the cross-component lane; without it, PR-level rules hold
only for PRs the agent opens.

## 10. Phases

All new behaviour is off by default (`lanes: off`, no catalog = today's kit).

### Phase 0 - measure before building (one to two weeks)

- Collect 20-30 past epics that touched more than one repo, by Jira key through GitHub commit
  search. Ground truth: the repos and contract files that actually changed.
- Baseline: a plain agent with GitHub MCP and the BRD, asked "which components change and how",
  k = 3 runs each. Score recall, precision and consistency (all runs right).
- **Decision point:** if the baseline already gets nearly all components right and is consistent,
  build Phase 1 without the scan and co-change parts. If not (the expected outcome), the
  catalog and graph are justified by data, and this set measures them.
- Proven by: the scored report itself, kept in the kit as the regression set.

### Phase 1 - epic flow

| Change | Files |
| --- | --- |
| `catalog-info.yaml` template; `/analyse` and `catalog scan` write it; `catalog sync` | `templates/`, `analyst.agent.md`, new `workflow/Catalog.java`, `workflow/CatalogScan.java` |
| Solution template, `architect` and `impact-analyst` agents, `/solution` | `templates/solution-design.md`, `.github/agents/`, `.github/prompts/solution.prompt.md` |
| Parse and validate `# sdlc-solution`; publish; gate `solution`; slices with hashes | new `workflow/Solution.java`; `Docs.java`, `Gates.java`, `Approvals.java` |
| `stories create EPIC`; designer reads its slice; `tasks create` conformance; lanes | `IssueTracker.java`, `Jira.java` (create story under epic), `Tasks.java`, `designer.agent.md`, `sdlc.yml` |
| `feature status EPIC` | new `workflow/Feature.java` |

Proven by: self tests for the parser and checks; the e2e mock flow extended to an epic with two
repos, two stories, a disputed component, a refused out-of-slice contract change and a slice
change that re-opens one LLD; then the Phase 0 set re-scored with `/solution`.

### Phase 2 - trust

- Contract T0, spec-diff conformance and the breaking-change check in the PR gate and `check-pr`.
- `tests lock`, its checks, and role-aware refusals when `agent_type` is present (`HookFormat.java`).
- Hold-out marking in the test design; reading the QE pipeline result in `ci wait`; redaction.
- Optional mutation testing in `verify`.
- Proven by: e2e checks for each refusal (locked test edited by tool and by shell, unlisted
  operation, removed enum value, hold-out details absent from every agent-visible file), and one
  real run against Jenkins and a real QE pipeline, as was done for the current flow.

### Phase 3 - scale

- Co-change and runtime-trace edges; hidden-coupling report.
- `sdlc upgrade` from a pinned kit version, with org rules layered over repo rules, so twenty
  repos do not run twenty versions of the kit.
- Proven by: the Phase 0 set again, with and without the extra edge sources.

## 11. Spec-kit, Rosetta and current findings

| | spec-kit | Rosetta | This kit today | Proposed |
| --- | --- | --- | --- | --- |
| Flow | constitution -> specify -> plan -> tasks -> implement | classify, then Prepare, Research, Plan, Act, Validate | test design -> LLD -> tasks -> loops -> PR -> CI | lanes; epic solution -> stories -> today's flow per repo |
| Multi-repo | Not built in (Speck adds it) | Not specific | Read-only related repos | Federated catalog, graph, stories per component, contract checks |
| Compliance | Checklists the agent reads | Approval gates; instructions | Hooks, git hooks, CI, branch protection, approval hashes | + conformance, locks, hold-outs |
| Instructions | Per repo | Central, versioned, layered | Per repo copy | Versioned, layered (Phase 3) |

Take from spec-kit an org-level constitution, checked by tools (ArchUnit, contract rules) where it
can be. Take from Rosetta classify-first and versioned central instructions.

What the evidence says matters most:

1. **Checks over prose.** Agents do not reliably follow long specs or checklists (Böckeler).
2. **Hidden oracles.** Visible tests get gamed, more so as changes grow (SpecBench, EvilGenie).
3. **Independent, repeated judgement.** Blind review with another model family; k-run impact
   analysis; QE from requirements.
4. **Narrow context.** Each story carries only its slice; one session per repo.
5. **Proportionate process.** Lanes; heavy specs on small changes make delivery worse.
6. **Measure the harness.** Real cases, graded on outcomes, consistency reported (Anthropic,
   *Demystifying evals for AI agents*).

## 12. Risks

| Risk | Mitigation |
| --- | --- |
| More gates slow teams | Lanes; everything off by default; LLD review is smaller when the slice is already approved |
| Catalog goes stale | Lives in each repo next to the code; scans in `check-pr` flag drift |
| Architects become a bottleneck | Only the cross-component lane needs them; the page is short and pre-checked |
| Impact analysis still wrong | k-run disputes, co-change signal, Phase 0 set, retro counts of change requests |
| Hold-out details leak | Not in component repos; redacted digests; tested in Phase 2 |
| A runtime leak: agent code logs the hold-out test's inputs | Low likelihood; hold-out environment logs are not readable by agents; listed as a limit |
| Breaking-change detection misses a case | Limits listed; `oasdiff` optional; contract tests catch what consumers rely on |

## 13. Decisions needed

1. Use Backstage's `catalog-info.yaml` format in each repo? Recommended: yes (works with or without Backstage).
2. Which repo and pipeline does QE use for black-box and hold-out tests today?
3. Contract tests: Spring Cloud Contract (Java only) or Pact (Java and Node)?
4. Named solution approvers (`gates.approvers.solution`)?
5. Pilot: which 3-5 services, and who collects the 20-30 epics for Phase 0?

## 14. Sources

- Böckeler, *Understanding Spec-Driven-Development: Kiro, spec-kit, and Tessl*: https://www.martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html
- Zhao et al., *SpecBench: Measuring Reward Hacking in Long-Horizon Coding Agents* (2026): https://arxiv.org/abs/2605.21384
- *EvilGenie: a Reward Hacking Benchmark*: https://alphaxiv.org/abs/2511.21654
- Foster et al., *Mutation-Guided LLM-based Test Generation at Meta*: https://arxiv.org/abs/2501.12862
- Anthropic, *Demystifying evals for AI agents*: https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents
- Anthropic, *Effective harnesses for long-running agents*: https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
- Anthropic, *How we built our multi-agent research system*; *Effective context engineering for AI agents* (anthropic.com/engineering)
- Claude Code hooks reference (`agent_type` in tool events): https://code.claude.com/docs/en/hooks
- Backstage descriptor format: https://backstage.io/docs/features/software-catalog/descriptor-format
- Grid Dynamics Rosetta: https://griddynamics.github.io/rosetta/docs/overview
- GitHub spec-kit: https://github.com/github/spec-kit

## 15. Changes by round

**Round 2:**
- Added lanes.
- Impact analysis runs k times and lists disputed components.
- Tests are locked by hash, plus a hold-out set.
- Co-change history used as a dependency signal.
- Added a Phase 0 eval and EARS requirements.
- Each team gets its own slice of the solution, each slice with its own hash.
- Consumers build against mocks in parallel.
- Added change requests, a generated catalog and a risks section.

**Round 3**: every part was checked against the kit's principles (section 2).
- **The epic carries the feature:**
  - Solution and test design pages hang on the epic.
  - `stories create` makes one story per component.
  - This reuses the existing parent lookup and approval-on-owning-issue.
- **No central repo or manifest:**
  - The catalog is federated, with one Backstage-format `catalog-info.yaml` per repo.
  - Feature status is read from Jira.
- **Each new rule names the check that enforces it,** with an enforcement table in the style of docs/06.
- **The lane is checked against the scopes and the diff,** not only suggested by the agent.
- **Contract conformance is a parsed spec diff,** compared with the slice, plus a limited built-in breaking-change check whose limits are stated.
- **Hold-out tests are black-box only:**
  - They live in the QE repo and run in QE's pipeline, so the kit still only reads CI.
  - Agents see redacted results.
- **Co-change uses GitHub commit search,** because `repos sync` clones are shallow.
- **Phase 0 has a go/no-go:** if a plain agent already scopes well, the catalog work shrinks.
- **Each phase says how it will be proven.**
- **All new behaviour is off by default.**
