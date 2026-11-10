# Agentic SDLC kit for Java (Copilot / Claude Code / Codex + Jira + Confluence + GitHub + Jenkins + SonarQube)

A ready-to-copy setup that lets coding agents (GitHub Copilot, Claude Code or Codex) deliver Jira stories in a Java (or
Node) repository **inside guardrails**. QE and developers design in Confluence, humans approve
each design in Jira, agents can only edit the files the approved design allows, tests come
first, a second agent reviews blind, and your existing Jenkins + SonarQube + GitHub branch
protection refuse anything that slipped through. Humans approve designs and merge pull
requests. Agents never do.

## The flow in one picture

```
Jira story (product owner) + one pager / HLD in Confluence
   |
   |  /test-design KEY   (QE)        -> test scenarios + mind map page in Confluence
   |  HUMAN GATE: QE lead posts the approval line as a Jira comment
   |
   |  /design KEY        (developer) -> this team's LLD page with a machine-readable work breakdown
   |  HUMAN GATE: tech lead posts the approval line as a Jira comment
   |
   |  /feature STORY     (developer) -> Jira sub-tasks from the approved LLD, then per task:
   |      task start (edits limited to the task's paths) -> implementer (test first) -> verify
   |      -> blind reviewer (2..5 rounds) -> QA scenarios -> PR (gate) -> Jenkins + SonarQube
   |      -> CI auto-fix loop (fix reviewed before every push, max 3 failed rounds)
   |
   |  HUMAN GATE: a human reviews and merges the PR
```

Read [docs/00-how-it-works.md](docs/00-how-it-works.md) for the agents, the gates and the four
guardrail layers.

## What is in this folder

```
agentic-sdlc-java-kit/
├── README.md                      <- you are here
├── docs/                          <- the guide (read in order)
├── snippets/
│   ├── pom-quality-plugins.xml    <- JaCoCo, Spotless, SpotBugs, enforcer, failsafe for your pom.xml
│   ├── ArchitectureRulesTest.java <- ArchUnit rules (layers, no field injection, ...)
│   └── node-quality.md            <- Node/TypeScript: scripts, Vitest JUnit + lcov, ESLint, Sonar, Jenkins
├── tests/e2e/                     <- the kit's own end-to-end test: python3 tests/e2e/run.py
└── template/                      <- copy its contents into your repository
    ├── AGENTS.md
    ├── .agent/
    │   ├── sdlc.yml               <- the ONE file you configure
    │   ├── Sdlc.java              <- java .agent/Sdlc.java <command>  (JDK 17+, no build step)
    │   ├── src/sdlc/              <- the tool, in layers: util, core, guard, workflow, integrations, cli, selftest
    │   ├── bin/sdlc, sdlc.cmd     <- fast launchers for hooks
    │   ├── templates/             <- test design, LLD, project profile skeletons
    │   └── local.env.example      <- your tokens go in local.env (git-ignored, agents can't read it)
    ├── .githooks/                 <- pre-commit, commit-msg, pre-push
    ├── .github/
    │   ├── copilot-instructions.md, instructions/   <- rules every agent reads
    │   ├── agents/                <- orchestrator, analyst, qe-designer, designer, implementer,
    │   │                             reviewer, qa-verifier, ci-fixer
    │   ├── prompts/               <- /analyse, /test-design, /design, /feature, /review, /fix-ci
    │   ├── skills/                <- Java standards, testing, code review, Sonar fixes, Jira/Confluence
    │   ├── hooks/guardrails.json  <- Copilot agent hooks -> the tool
    │   │                             (Claude Code and Codex: `config apply` generates .claude/, .codex/, .agents/)
    │   ├── CODEOWNERS, pull_request_template.md
    └── .vscode/settings.json, extensions.json
```

## The guide

| # | Document | Time |
| --- | --- | --- |
| 0 | [How it works](docs/00-how-it-works.md) - flow, agents, gates, guardrail layers | 15 min |
| 1 | [Prerequisites](docs/01-prerequisites.md) - software and what to ask other teams for | 30 min |
| 2 | [Setup](docs/02-setup.md) - install into your repository, tokens, Confluence, hook test | 60 min |
| 3 | [Configuration](docs/03-configuration.md) - every `sdlc.yml` setting | reference |
| 4 | [Daily workflow](docs/04-daily-workflow.md) - one story from ticket to merge | read once |
| 5 | [CI: Jenkins, SonarQube, GitHub](docs/05-ci-jenkins-sonar.md) - what the kit needs from CI | 45 min |
| 6 | [Guardrails reference and limits](docs/06-guardrails-reference.md) | read once |
| 7 | [Troubleshooting](docs/07-troubleshooting.md) | when needed |

## Quick start (if you already know the tools)

```bash
cp -r agentic-sdlc-java-kit/template/. /path/to/your-java-repo/
cd /path/to/your-java-repo
java .agent/Sdlc.java setup          # git hooks, ignores, MCP config, self test
# edit .agent/sdlc.yml (Jira, Confluence, CI) and .agent/local.env (tokens), then:
java .agent/Sdlc.java doctor
git switch -c chore/PROJ-1-agent-kit && git add -A && git commit -m "chore(PROJ-1): add agent kit"
```

Then in Copilot Chat, Claude Code or Codex: `/analyse` once, and per story `/test-design`,
`/design`, `/feature`. For Claude Code or Codex, set `tools: [copilot, claude, codex]` in
`sdlc.yml` and run `java .agent/Sdlc.java config apply` first. Do the hook test in [docs/02-setup.md](docs/02-setup.md#step-8-the-hook-test) once.

## What was tested

- **Self test:** `java .agent/Sdlc.java selftest` - 408 checks of the shell, file, MCP, git,
  secret, scope, gate and parsing rules.
- **Full flow against mocks:** a scratch Maven repository with a mock Jira Cloud, Confluence
  Cloud, Jenkins and SonarQube and a fake `gh`: setup, doctor, ~30 hook decisions, docs
  find/pull, test design publish (with mind map), approvals (wrong version, edited comment,
  non-approver, bot, valid), LLD publish, sub-tasks, task scope, test-first verify, Stop hook,
  commit hooks (message, secret, disabled test), review loop, PR gate, push guard, CI digest
  (Jenkins test report and log, Sonar gate and issues), retro, merge sync, LLD changed after
  approval. This test is in the repository: `python3 tests/e2e/run.py` (120 checks, about a
  minute, needs only a JDK, git and Python 3; see [tests/e2e](tests/e2e/README.md)).
- **Full flow against real services** (November 2026): Jira Cloud through the API gateway
  with a scoped bot token, Confluence Cloud on a second site, a private GitHub repository, a
  Jenkins LTS in Docker and SonarQube Cloud. Two human approvals posted in Jira, two Jira
  sub-tasks, two tasks implemented test-first with review loops, QA, a real pull request, and
  three real CI rounds (green, red on the SonarQube coverage gate, green after a reviewed fix).
  The bugs that run found are fixed (see docs/07 for the symptoms).

- **Live agent sessions in Claude Code (2.1) and Codex CLI (0.159)** with the generated
  setups: each attempted the same forbidden actions (force push, `-DskipTests`, reading
  `local.env`, writing `sdlc.yml`, `AGENTS.md` and its own hook config, editing out of scope with
  its edit tool and through `bash -lc`, posting an approval, `gh pr merge`); every one was
  refused, the in-scope edit was allowed, and the session-start, post-edit and Stop hooks reached
  the agent.

- **Node / TypeScript** (`stack: node`): the full mock flow on a TypeScript + Vitest project -
  analyse, designs and approvals, tasks with TS scopes, test-first `verify` (`tsc`, Vitest,
  build), JS test names in `trace`, commit blocks for `it.skip`, `.only`, `@ts-nocheck`,
  `eslint-disable` and secrets in `.ts`, 20 Node-specific hook decisions (`--passWithNoTests`,
  `npm publish`, `npm install`, `package.json`/`tsconfig`/`vitest.config` edits, `.env`,
  `~/.npmrc`), review, QA, PR gate, CI rounds and retro. The `snippets/node-quality.md` configs
  were run on that project. Then real CI for it: a private GitHub repository, Jenkins installing
  Node 22 and running `npm ci`, `tsc`, Vitest (JUnit read by Jenkins) and the build, and SonarQube
  Cloud analysing TypeScript with lcov coverage. Three real rounds on the PR: green, red on the
  new-code coverage gate (the digest named the file and its 11 uncovered lines), green after a
  reviewed fix.

Not tested: Jira/Confluence Data Center, a Windows machine, GitHub Enterprise Server, and a
live VS Code + Copilot session (its hooks were driven with the payloads VS Code and the Copilot
CLI send). Do the 10-minute hook test in docs/02 on your machine, with every tool you use.
