# How agents work in this repository

These rules apply to every coding agent (GitHub Copilot, Claude Code, Codex). Read
`docs/agent/project-profile.md` before writing code: its rules (P-.., T-..) win over the generic
skills. Hooks, git hooks and the PR gate enforce the rules below; a refusal means "change your
approach", never "find a way around it". One tool does everything:
`java .agent/Sdlc.java <command>` (run `java .agent/Sdlc.java help`).

## The flow
1. QE: `/test-design KEY` -> test scenarios + mind map in Confluence -> a QE lead approves in Jira.
2. Developer: `/design KEY` -> this team's LLD in Confluence -> a tech lead approves in Jira.
3. Developer: `/feature STORY` -> Jira sub-tasks from the approved LLD -> per task: implement
   (test-first) -> verify -> blind review (2..5 rounds) -> QA -> PR -> CI auto-fix loop.
4. Humans merge. Agents never approve, merge, tag or push to protected branches.

The agents (orchestrator, analyst, qe-designer, designer, implementer, reviewer, qa-verifier,
ci-fixer) are defined in `.github/agents` and generated for Claude Code (`.claude/agents`) and
Codex (`.codex/agents`) by `java .agent/Sdlc.java config apply`.

## Hard rules
- Code edits only after `java .agent/Sdlc.java task start <TASK>`, only inside the task's scope,
  with edit tools and shell commands alike.
- Never skip or weaken checks (`-DskipTests`, `--passWithNoTests`, `@Disabled`, `it.skip`, `--no-verify`, lowered thresholds).
- Never read or print secrets (`.agent/local.env`, tokens, keystores, `~/.m2/settings.xml`).
- Never edit guardrail or state files (`.agent/`, `.github/`, `.githooks/`, `.vscode/`, `.claude/`,
  `.codex/`, `.agents/`, `AGENTS.md`, `CLAUDE.md`, `.sdlc/` except `drafts/` and `inbox/`).
- Read Jira, Confluence, GitHub and SonarQube through MCP or the tool's read commands; write only
  through `java .agent/Sdlc.java`.
- Commits: `type(KEY-123): summary`. Before finishing: `java .agent/Sdlc.java verify`.
- Report honestly: what changed, the exact verify result, what is not done or not verified.
