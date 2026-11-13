# Parked record: features across several repositories

Status: parked on 2026-11-14 by the owner's decision, on the advice of two expert reviewers. No
multi-repository work is built until the gate of [proposal 02](02-mandate-direction.md)
(section 9) passes. This file keeps the direction and its details so the work can resume
without redoing the analysis. It draws on three plan revisions, one dedicated research report
and a read of the source. Nothing in sections 4 to 8 is built.

## 1. Why it is parked, and what reopens it

- One maintainer cannot run this track and still learn from the first users.
- Contract compatibility between repositories is already covered by existing tools (Pact,
  oasdiff, buf). The part that is new here, "did each part stay inside the slice of an approved
  feature design", matters only to organisations whose architects approve designs. None is a
  user yet.
- Discovery at the scale of hundreds of repositories is unproven by any published benchmark.

**The question that reopens it.** Ask the pilot team for its last five features that crossed
repositories, and whether an edit outside the agreed slice, or a provider and a consumer built
against different contracts, ever caused harm. If yes, start at section 9, step B0.

**What keeps the door open.** The mandate of proposal 02 keeps a `feature.key` field, so a
feature view is later a join over the verdicts of its units, with no change to the format.

## 2. What the kit has today

Complete in code, tested against mocks and one live run driven by hand in November 2026. Never
driven by agent sessions.

| Piece | Command or file |
| --- | --- |
| Catalog per repository, Backstage format | `catalog-info.yaml`, with `sdlc/owns` and `sdlc/invariants` annotations |
| Catalog commands | `catalog sync`, `catalog scan`, `catalog graph`, `catalog check`, `catalog cochange`, `catalog import-traces` |
| Solution design split into slices | A `# sdlc-solution` block with one slice and slice hash per component |
| Stories per component | `stories create` |
| Lanes | patch, component, cross-component |
| Contract allowance | `docs/agent/contracts/<STORY>.json`, checked in CI against the spec diff |
| Test locks and hold-outs | `docs/test-locks/<STORY>.json`; a QE Jenkins job for hold-out tests |
| Scoping evaluation | `eval collect`, `eval run` |
| Kit version across repositories | `upgrade --from <kit> [--check]` |

Docs: [docs/08-multi-component.md](../08-multi-component.md) and
[proposal 01](01-multi-component-delivery.md).

## 3. How an agent finds what to work on today

Everything comes from one file per repository, `catalog-info.yaml`, which agents cannot edit.

| Question | Where the answer comes from |
| --- | --- |
| Which components change? | The architect agent writes requirements, then starts a fresh impact analyst three times. Each lists the domain nouns in the requirements, takes their owners as first candidates and the owners' providers and consumers as the second ring, and must confirm each with file-and-line evidence. Components all runs named are candidates, the rest are disputed, and only the human architect decides a dispute. `stories create` then makes one story per component |
| What is a repository's intended scope? | `sdlc/owns` (the domain nouns it is the source of truth for) and `sdlc/invariants` (rules every design must keep) |
| What does it depend on? | Declared: `providesApis`, `consumesApis`, `dependsOn`. Found: `catalog scan` reads Feign and HTTP clients, Kafka, JMS and RabbitMQ topics and service URLs in configuration. `catalog sync` copies other repositories' catalog files and specs; `catalog graph` merges it all into one page for agents |
| What is the contract between repositories? | Each `kind: API` entry points at an OpenAPI or AsyncAPI file in the provider's repository. The approved solution lists the operations to add or remove per API. Each story gets its slice, and CI fails a spec diff that adds or removes anything else |

## 4. Gaps in the code

Found by reading the source on 2026-11-13 and 2026-11-14.

| # | Gap | Where |
| --- | --- | --- |
| 1 | `catalog sync` reads only repositories listed in `sdlc.yml`. Proposal 01 says it finds them by code search; it does not. An unlisted repository is invisible | `Catalog.sync` |
| 2 | `catalog scan` runs only by hand. Proposal 01 says `check-pr` warns about undeclared dependencies; it does not | `Checks`, `CatalogScan` |
| 3 | A component's slice lists every other provider's contract (`mayConsume`), so a change to any contract changes every slice hash and reopens designs it does not affect | `Solution.slice` |
| 4 | The agreement of the three analysis runs is computed by the architect agent; the tool only checks that `disputed` is empty | `Solution` |
| 5 | A scenario linked as `other-repo:` passes with no check; `feature status` reports design readiness only, not pull requests, CI or hold-out results | `Trace`, `Feature.status` |
| 6 | Component names lose their namespace; two components with the same name in different namespaces overwrite each other; `self()` picks the first component | `Catalog.entity` |
| 7 | A host matches a component by substring; an HTTP call can resolve to the first API that matches a method and path, without the provider's identity | `CatalogScan.provider` |
| 8 | Configuration values are taken from the first application file, so differences between environments are lost | `CatalogScan` |
| 9 | Sync records the repository and how it was fetched, but no source commit and no time; a failed fetch can leave old cached evidence in place. Missing, inaccessible, unsupported and stale are not told apart | `Catalog.sync` |
| 10 | A contract allowance has one `breaking` flag per API, not per operation; the home-made diff reads one level deep | `Contracts` |

## 5. The target design

| Level | Artifact | Approved by | Checked where |
| --- | --- | --- | --- |
| Feature | Feature mandate: the change units, the API operations each provides and consumes, typed prerequisites between them | An architect, before any unit starts | `stories create`; refused while a unit is disputed |
| Change unit | One mandate per unit | That unit's lead | That repository's CI; several unit mandates may share one pull request |
| Feature | Feature verdict: every unit's verdict, contract compatibility, and the cross-unit scenarios, on the exact revisions that ship | A person releases on it | `feature status`, read from verdicts |

Principles, each a change from what the kit does now:

- **The unit is a deployable component, not a repository.** In a monorepo a unit is a
  component root and a build target, with its own owner. Units are identified by repository,
  namespace, root and target. What a unit may read is kept apart from what it may edit.
- **Discovery is a query over evidence, not a catalog lookup.** Each answer carries its source
  revision, how fresh it is, and whether it is missing, inaccessible, unsupported or stale.
  Sources: the catalog, build manifests (another team's client artifact), the scan, contract
  test files, runtime traces, and optional adapters to an index or portal the organisation
  already has (Sourcegraph, Backstage, Port, Cortex). No index and no portal is built here.
  The retrieval method stays replaceable and is chosen by measurement.
- **The union of analysis runs is investigated; exclusion needs evidence.** Three analysts
  reading the same incomplete catalog agree on the same omission. The tool merges the runs and
  records them and their models. The number of runs is set by measured recall, not fixed.
- **Ownership claims are kept apart from observed dependencies.** A noun two units claim asks
  for a qualifier by context; it is not an error by itself.
- **Contracts are compared by existing engines per protocol:** oasdiff for OpenAPI, the
  AsyncAPI diff, buf for protobuf, schema checks for GraphQL, Pact's can-i-deploy for
  consumer-driven contracts. Allowances are per operation. Compatibility decides, not hash
  equality: rejecting every changed hash causes needless re-approval, and one `breaking` flag
  per API is too broad.
- **Order is a set of typed prerequisites, not "provider first".** For backward-compatible
  event schemas the consumer upgrades first; for forward-compatible ones the producer does.
- **Done is a release state, not "all merged".** Per unit: expand, deploy, migrate or backfill,
  activate, observe, contract. Each has an owner, an environment, an artifact digest, a
  compatibility window and a way back. Existing deployment tools are used, not replaced.
- **The feature verdict is bound to the exact combination.** A merge queue checks a temporary
  commit against the latest target; the verdict must name those revisions. Individually valid
  verdicts on different states say nothing about the combination.
- **Campaigns.** The same small change in many repositories uses one approved mandate template
  with parameters. Repeatable edits go to existing recipe tools (OpenRewrite, Sourcegraph batch
  changes); agents handle the exceptions. A few trial repositories and a stop condition first.
- **Reconciliation survives interruption.** Expected against observed state of pull requests,
  CI, artifacts and deployments is persisted, with idempotent retries and bounded concurrency.
- **Shared memory carries its sources.** Decisions, invariants and discovery explanations are
  kept with source references and rules for when they become invalid; observations are kept
  apart from approved policy.

## 6. Weak points and their fixes

| Weak point | Fix |
| --- | --- |
| A repository nobody listed is invisible (gap 1) | `catalog sync --org` finds every repository of the organisation with a catalog file, and lists those without one as blind spots the analyst must search by hand. Measure: catalog coverage |
| `sdlc/owns` is hand-written and goes stale | `catalog check --all` after a sync: a noun two units claim, a consumed API nobody provides, a unit that owns nothing. The scan proposes nouns from evidence (resource names in the provided API, entity and table names) and reports "owned with no evidence" and "evidence with no owner" |
| The catalog drifts because the scan is manual (gap 2) | `check-pr` runs the scan and warns on a dependency the catalog does not declare and on a spec file it does not list |
| The scan is regular expressions and drops what it cannot read | Every client call whose target is not a literal is recorded as `unresolved` with file and line, and scan coverage is reported. Note that "resolved of detected call sites" measures the detector, not all dependencies. Host matching becomes exact and carries the provider's identity (gap 7) |
| Other evidence of dependencies is unused | Build files, contract-test files and runtime traces are read as edges. Co-change and trace import stay as built and are switched on by evidence: low scan coverage, or an escaped defect across two units nobody linked |
| The agreement of runs is computed by an agent (gap 4) | `solution merge <EPIC>` reads the run files, computes the union and the disputed set itself, and records runs and models. Publishing is refused with fewer runs than configured, or with components that differ from the merge and no recorded human decision |
| Scoping accuracy has never been measured on real features | Section 9, step B0, before anything else is built |
| A slice lists every provider's contract (gap 3) | Each unit of the solution names what it consumes. Its slice and hash cover only what it provides and consumes |
| A consumer does not record which provider spec it built against | The unit mandate pins the provider repository, the commit and the operations it uses. The feature verdict runs the compatibility engine between the pin and what the provider merged, and names the consumer to re-verify when it is incompatible |
| `other-repo:` scenario links are unchecked (gap 5) | The feature verdict requires the named unit's verdict to list that scenario as covered |
| `feature status` shows design readiness only (gap 5) | It becomes the feature verdict: approvals, slices, pull requests, unit verdicts, prerequisites and cross-unit hold-out results |
| Evidence has no source or freshness (gap 9) | Every synced fact records its source commit and time; a failed fetch marks the fact stale instead of keeping the old one silently |

## 7. What goes wrong, and the measure against it

| Failure | Measure |
| --- | --- |
| A unit that must change is missed | Tool-merged analysis runs; recall measured on your own history; blind spots listed; a unit found late reopens the feature mandate and becomes an evaluation case |
| An agent edits a repository that is not part of the feature | That unit has no mandate for the story, so the change gets a full review at best |
| Provider and consumer build different contracts | Both mandates take their operations from the same slice; the provider's spec diff must match its allowance; the consumer's pins; contract tests |
| The solution changes mid-way | Only mandates whose own slice changed become stale, and CI fails them until approved again |
| A unit is released before what it needs | Typed prerequisites; the feature verdict fails while one is unmet |
| Every part passes alone and the whole fails | Cross-unit scenarios are hold-out tests in QE's pipeline against the combined build, bound to exact revisions |
| Coupling nobody declared (a shared table, a copied DTO) | An escaped defect across two unlinked units is recorded as a missing link and switches co-change analysis on |
| Merged, and not safely deployable | Release states with a compatibility window and a way back |

## 8. How it would be measured

| Measure | Why it matters |
| --- | --- |
| Recall of required units, and unnecessary scope, on time-pinned snapshots with distractor repositories, a stale catalog and an inaccessible dependency | A missed unit is the most expensive failure |
| pass^k: every run names the right units | Reliability, not best case |
| Catalog coverage and evidence freshness | How much the agent can see before it has to guess |
| Units found late, per feature | The same failure, seen in delivery |
| Slice re-approvals per feature, and mandates made stale by each | The cost of changing the solution |
| Contract exceptions and pin incompatibilities per feature | Whether teams stay in their slice and on compatible contracts |
| Cross-unit scenarios passing at the first attempt | Whether the parts fit |
| Integration escapes: defects involving two or more units, found after merge | The outcome that matters |
| Feature lead time, and idle time between the first and the last unit | Where the feature waits |
| Human minutes and agent cost per feature | Whether it pays |

## 9. The order of work when it resumes

**B0. Discovery evaluation, first.** Replay real multi-repository features against time-pinned
snapshots. Compare catalog only, catalog plus scan, indexed search, and a hybrid; compare one
analysis run against several. `eval collect` gains a tracker-free source (linked issues and
pull requests across repositories on GitHub), so it can run on public organisations.
*Exit:* numbers for at least ten features. The number of runs and the evidence sources are then
chosen from them. If the catalog adds little over indexed search, the catalog work is cut.

**B1. Units and evidence.** Change units and namespaces (gap 6); the query interface with
source and freshness (gap 9); `catalog sync --org`, `catalog check --all`, the scan in
`check-pr` (gaps 1, 2, 7, 8).
*Exit, on the test bed:* an undeclared dependency warns in CI; an unlisted repository shows as a
blind spot; two components with the same name in different namespaces do not collide.

**B2. Feature mandate and feature verdict.** Tool-merged analysis (gap 4); precise slices
(gap 3); pluggable contract engines and per-operation allowances (gap 10); typed prerequisites;
checked `other-repo:` links and the feature verdict on exact revisions (gap 5). Needs the
single-repository verifier of proposal 02 first.
*Exit:* one feature across two test-bed repositories, driven by both agent families, where a
solution change makes exactly one mandate stale, an operation outside the slice fails in the
provider's CI, and an incompatible provider change fails the feature verdict while a compatible
one does not.

**B3. Release states and campaigns.** Release states with existing deployment tools; campaign
templates; units found late feed the evaluation.

## 10. What not to build

A whole-estate code index; mandatory embeddings; another developer portal; a replacement
deployment controller; a distributed git transaction; a contract diff or contract-testing tool
of our own; a fixed three-run consensus before its benefit is measured. Regular-expression
scanning stays as a labelled fallback.

## 11. Research behind this

Read at the source by a researcher on 2026-11-14 unless marked.

- Joint development across repositories is hard for agents: the best model completes 58.5% of
  171 tasks on one benchmark, with cross-repository inconsistency behind 44.2% of its failures,
  and 42.5% of 120 tasks on another.
  [CrossSWE](https://arxiv.org/html/2611.01484v1), [WideSWE](https://arxiv.org/html/2609.33382v1)
- Retrieval strategy should stay replaceable: one vendor reported a 12.5% gain from semantic
  search in 2025 and now documents indexed grep without stored embeddings.
  [evaluation](https://cursor.com/blog/semsearch), [current docs](https://cursor.com/docs/agent/tools/search)
- Semantic indexes exist and are reachable by agents:
  [Sourcegraph MCP](https://sourcegraph.com/docs/api/mcp),
  [Glean](https://engineering.fb.com/2024/12/19/developer-tools/glean-open-source-code-indexing/).
  Catalogs are reachable too, which says nothing about whether their dependencies are complete:
  [Backstage](https://backstage.io/docs/features/software-catalog/external-integrations/),
  [Port](https://www.port.io/blog/integrate-software-catalog-every-workflow-port-mcp-server),
  [Cortex](https://www.cortex.io/post/a-new-era-for-your-developer-portal-cortex-mcp)
- A service graph needs operational context beyond code (teams, incidents, deployments):
  [Uber](https://www.uber.com/us/en/blog/efficient-software-factory/)
- Large migrations pair conservative discovery with executable validation, and admit discovery
  is incomplete: [Google](https://arxiv.org/html/2504.09691v1),
  [Airbnb](https://airbnb.tech/infrastructure/accelerating-large-scale-test-migration-with-llms/)
- Fleet control is separate from coding intelligence:
  [Spotify](https://engineering.atspotify.com/2025/11/spotifys-background-coding-agent-part-1),
  [Stripe](https://stripe.dev/blog/minions-stripes-one-shot-end-to-end-coding-agents-part-2).
  Orchestration across repositories is occupied territory:
  [Sourcegraph agentic batch changes](https://sourcegraph.com/blog/introducing-agentic-batch-changes),
  [Moderne](https://docs.moderne.io/user-documentation/agent-tools/mcp/overview/),
  [Grit](https://docs.grit.io/docs)
- Contract engines per protocol:
  [oasdiff](https://github.com/oasdiff/oasdiff/blob/main/docs/BREAKING-CHANGES.md),
  [AsyncAPI](https://github.com/asyncapi/cli/blob/master/docs/usage.md),
  [Buf](https://buf.build/docs/breaking/),
  [Apollo schema checks](https://www.apollographql.com/docs/graphos/platform/schema-management/checks)
- Order is not always provider first: [Pact can-i-deploy](https://docs.pact.io/pact_broker/can_i_deploy),
  [schema evolution](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)
- A merge queue protects one repository's combination, not a distributed release:
  [GitHub merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)
- Rollout and memory need explicit lifecycles:
  [migration flags](https://launchdarkly.com/docs/guides/flags/migrations),
  [Argo analysis](https://argoproj.github.io/argo-rollouts/features/analysis/),
  [agent memory with citations](https://github.blog/ai-and-ml/github-copilot/building-an-agentic-memory-system-for-github-copilot/)
- Bi-directional contract testing with AsyncAPI support, and cross-repository review, exist as
  products (summary only):
  [PactFlow](https://support.smartbear.com/swagger/contract-testing/docs/en/on-premises-admin-guide/on-premises-release-notes/2-6-0.html),
  [Qodo](https://docs.qodo.ai/governance/cross-repo-code-review)

## 12. Open questions

- How accurate are dependency catalogs in real organisations? No independent figure was found.
- Can representative histories of multi-repository features be obtained from an organisation,
  and kept useful across access boundaries?
- Which recovery guarantees can adopters give for data migrations that cannot be undone?
- What breaks first at 500 repositories: discovery, approval load, or reconciliation?

## 13. Limits

Nothing here proves the parts are correct together. It would prove that each stayed in its
slice, that contracts are compatible by a named engine, and that the shared scenarios passed on
the stated revisions.
