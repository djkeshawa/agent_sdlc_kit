# How agents work in this repository

The rules for every agent are in [AGENTS.md](../AGENTS.md) (VS Code loads it with
`chat.useAgentsMdFile`; read it if it is not in your context). The short version:

- Read `docs/agent/project-profile.md` before writing code.
- Code edits only after `java .agent/Sdlc.java task start <TASK>`, only inside the task's scope.
- Never skip or weaken checks, never read secrets, never edit guardrail or state files.
- Write to Jira and Confluence only through `java .agent/Sdlc.java`; humans approve and merge.
- Before finishing: `java .agent/Sdlc.java verify`, and report its exact result.
