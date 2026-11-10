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
| `docs/agent/eval/scoping/truth/<EPIC>.yml` | `components:` that really changed, the repositories and commit counts | **never** (`paths.hidden`) |

Review each truth file: remove a repository whose commit only mentioned the key in passing.
Repository names map to component names through `github.relatedRepos` (`name` for `repo`),
later through the catalog. Commit the cases and the truth, so the set is kept.

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

The report (`.sdlc/eval/report.md`) gives:

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
