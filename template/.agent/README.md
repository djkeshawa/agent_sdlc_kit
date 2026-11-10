# .agent - the agent SDLC tool

| Path | What it is |
| --- | --- |
| `sdlc.yml` | The one file you configure: Jira, Confluence, CI, gates, loops, agent models. Agents cannot edit it; CODEOWNERS review. |
| `Sdlc.java` | Bootstrap: `java .agent/Sdlc.java <command>` compiles `src/` when it changed and runs it (JDK 17+). |
| `src/sdlc/**` | The tool, one folder per layer: `util` (parsers, HTTP), `core` (settings, state), `guard` (the rules), `workflow` (gates, documents, tasks, loops, CI digest), `integrations` (Jira, Confluence), `cli` (commands, hooks, git hooks), `selftest`. |
| `bin/sdlc`, `bin/sdlc.cmd` | Fast launchers used by the IDE hooks and git hooks (run the compiled copy in `.build/`). |
| `templates/` | Skeletons the agents fill: test design, LLD (with the `# sdlc-tasks` work breakdown), project profile. |
| `local.env.example` | Template for your `local.env` (tokens). `local.env` is git-ignored and agents can never read it. |
| `.build/` | Compiled copy, local only, git-ignored. Refreshes itself when `src/` changes. |

Local working state lives in `.sdlc/` at the repository root (git-ignored): the current story
and task, review/QA/CI rounds, pulled sources, agent drafts and inbox, and the audit logs
(`.sdlc/logs/*.jsonl`: guard decisions, tool use, Jira/Confluence writes, verify runs).

Quick checks:

```sh
java .agent/Sdlc.java selftest      # built-in tests of the rules
java .agent/Sdlc.java doctor        # tools, tokens and connections
java .agent/Sdlc.java config check  # validate sdlc.yml
java .agent/Sdlc.java status        # branch, story, task, scope, loops, PR gate
java .agent/Sdlc.java help          # every command
```

The full guide is in the kit's `docs/` folder (setup: `docs/02-setup.md`, every setting:
`docs/03-configuration.md`).
