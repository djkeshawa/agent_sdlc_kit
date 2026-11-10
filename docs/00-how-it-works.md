# 0. How it works

## The idea in one paragraph

An AI agent is fast but makes confident mistakes: it skips tests when they fail, edits files it
should not touch, "fixes" a build by switching a check off, or builds something nobody asked
for. This kit gives agents a fixed path (Jira story -> test design -> LLD -> tasks -> test-first
code -> blind review -> QA -> pull request -> CI) and puts **guards at four independent
layers**, so a mistake is caught even when one layer is missing. Humans keep the decisions that
matter: *what* to test and build (approving the test design and the LLD) and *whether* it ships
(merging).

## The documents and who owns them

| Document | Lives in | Written by | Approved by |
| --- | --- | --- | --- |
| Story (and epic) | Jira | product owner | - |
| One pager, HLD | Confluence (any space in `confluence.searchSpaces`) | product owner, architect | (your process) |
| Test design: requirements, scenarios `TS-..`, mind map | Confluence, page `{key} - Test scenarios` | `qe-designer` agent for QE | QE lead, in Jira |
| LLD: contracts, data, sequences, **work breakdown** | Confluence, page `{key} - LLD - {team}` | `designer` agent for a developer | tech lead, in Jira |
| Project profile: this codebase's conventions | `docs/agent/project-profile.md` in the repo | `analyst` agent, once | PR review |
| Test links: `TS-..` -> test method | `docs/test-links/<STORY>.md` in the repo | `implementer` agent | PR review |

Pages are found by the **Jira key in their title**, following the issue's parents (sub-task ->
story -> epic), so an HLD titled with the epic key is found for every story of that epic.

## The flow

```
/analyse                     once per repository -> docs/agent/project-profile.md

/test-design PAY-100         qe-designer
   sdlc docs pull PAY-100        one pager, HLD, other LLDs, Jira -> .sdlc/PAY-100/sources/
   writes .sdlc/PAY-100/drafts/test-design.md
   sdlc docs publish PAY-100 test-design --file ...
        -> Confluence page + mind map image, and a Jira comment with the approval line
   HUMAN GATE 1: the QE lead posts   SDLC-APPROVED PAY-100 test-design 65671v1 1139650cf0f7

/design PAY-100              designer
   reads the sources, the approved test design, other teams' LLDs and repos, the code
   writes .sdlc/PAY-100/drafts/lld.md with a "# sdlc-tasks" block (tasks, scopes, scenarios)
   sdlc docs publish PAY-100 lld --file ...
   HUMAN GATE 2: the tech lead posts SDLC-APPROVED PAY-100 lld 229614v1 b6bf6e6c6ece

/feature PAY-100             orchestrator (never edits code)
   git switch -c feature/PAY-100-refunds origin/develop
   sdlc tasks create PAY-100   -> Jira sub-tasks PAY-101, PAY-102 (refused if the LLD is not approved)
   per task, in dependency order:
      sdlc task start PAY-101  -> agents may now edit ONLY this task's paths; Jira -> In Progress
      implementer: failing test -> code -> green; links TS in docs/test-links; sdlc verify; commit
      reviewer (blind: task key only): verdict file -> sdlc review record   (min 2, max 5 rounds)
   qa-verifier (if the test design has integration / contract / e2e scenarios) -> sdlc qa record
   git push; gh pr create      -> the PR gate refuses until every loop passed at this HEAD
                                  Jira -> In Review, PR linked on the story
   sdlc ci wait                -> reads Jenkins and SonarQube for the PR, files a digest
      red: ci-fixer -> reviewer -> push (the push is refused until the fix is reviewed)
      stops after loops.ci.max failed rounds and hands over to a human
   sdlc retro PAY-100          -> mistakes found and fixed, loops, hook refusals

HUMAN GATE 3: a human reviews the PR (CODEOWNERS for guardrail files) and merges
```

## The approval line

`java .agent/Sdlc.java docs publish` prints, and comments on the story, a line like

```
SDLC-APPROVED PAY-100 lld 229614v1 b6bf6e6c6ece
```

meaning: page 229614, version 1, and the first 12 characters of a SHA-256 of the page body. It
counts only if all of these hold:

- it is in a **new** comment on the issue that owns the page (never an edited comment);
- the page is still at that version **and** that content (any edit in Confluence needs a new line);
- the author is not a bot account (`gates.botAccounts`) and not the tool itself;
- the author is in `gates.approvers.<type>`, when that list is not empty;
- the author is not the person who saved that version of the page (four eyes; a team of one sets
  `gates.allowSelfApproval: true`).

Agents cannot post it: the hook refuses any command containing `SDLC-APPROVED`, the tool refuses
to post it, and the Atlassian MCP server is read-only for agents.

## The agents

The agents are defined once in `.github/agents` (Copilot). For Claude Code and Codex,
`java .agent/Sdlc.java config apply` generates the same agents, the `/feature`-style commands
and the skills in their formats (`tools` in `sdlc.yml`, [docs/02 step 8](02-setup.md#step-8-the-hook-test)).

| Agent | Started by | Does | Can edit |
| --- | --- | --- | --- |
| `analyst` | `/analyse` | Learns the codebase, writes the project profile with evidence | `docs/` |
| `qe-designer` | `/test-design KEY` | Test design + mind map from the one pager, HLD and code | `.sdlc/*/drafts` |
| `designer` | `/design KEY` | This team's LLD with contracts and the work breakdown | `.sdlc/*/drafts` |
| `orchestrator` | `/feature STORY` | Runs the whole delivery, starts the others, checks their work | nothing (runs commands) |
| `implementer` | orchestrator | One task, test first, inside the task's paths | the task's scope |
| `reviewer` | orchestrator, `/review TASK` | Blind review against LLD, test design, profile | only its report |
| `qa-verifier` | orchestrator | Runs integration / contract / e2e scenarios | tests in scope |
| `ci-fixer` | orchestrator, `/fix-ci STORY` | Fixes a red Jenkins build or Sonar gate | the task's scope |

Tip: give the **reviewer a different model family** than the implementer (`agents.reviewer.model`
in `sdlc.yml`, then `java .agent/Sdlc.java config apply`). Different models miss different things.

## The four guardrail layers

| Layer | Runs | Catches | Can be skipped by |
| --- | --- | --- | --- |
| 1. **Agent hooks**: Copilot `.github/hooks/guardrails.json`, Claude Code `.claude/settings.json`, Codex `.codex/hooks.json` -> the same tool | before and after every agent tool call, at session start, and when the agent wants to stop | dangerous commands, edits outside the task (by edit tools **and** shell writes), edits to guardrail files, reading secrets, MCP writes, skipped checks, the PR gate, unreviewed pushes, finishing without a passing build | an IDE without hook support, or someone switching hooks off |
| 2. **Git hooks** (`.githooks/`) | every `git commit` / `git push`, for humans and agents | commits on protected branches, branch names, commit messages, secrets, disabled tests, big files, tags, pushes of unverified builds, changed code outside the tasks' paths | `--no-verify` (agents are refused it by layer 1) |
| 3. **Your CI** (Jenkins + SonarQube; optionally `sdlc check-pr` as a stage) | every PR | the build and every test, coverage and the Sonar quality gate; with the `check-pr` stage also branch, commit, secret and disabled-test rules | nothing, if branch protection requires it |
| 4. **GitHub branch protection + CODEOWNERS** | merge | merging without green CI or human approval; guardrail changes without a tech lead | org admins only |

Layer 1 makes agents fast and honest: they are told *why* immediately, and the refusal is logged.
Layers 2-4 make the rules hold when layer 1 is not there. **Never rely on layer 1 alone.**
[docs/06](06-guardrails-reference.md) lists every rule and the honest limits.

## Where the rules live

One file: `.agent/sdlc.yml` (Jira, Confluence, CI, gates, loops, agent models). Everything not
set there comes from built-in defaults for your stack (`java-maven`, `java-gradle` or `node`):
protected paths, secret paths, forbidden build flags, commit and branch formats.
[docs/03](03-configuration.md) lists every setting. `sdlc.yml` is itself a protected path:
agents cannot edit it, and CODEOWNERS makes a tech lead review human changes.
