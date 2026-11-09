# 6. Guardrails reference and limits

**deny** = refused, the agent is told why, the refusal is logged in
`.sdlc/logs/guard-decisions.jsonl`. **ask** = the IDE asks you to approve. Layers: 1 = agent
hooks (Copilot, Claude Code, Codex: the same rules; each tool's edit and shell tools are
understood, including Codex's `apply_patch` and `bash -lc "..."`), 2 = git hooks, 3 = your CI (with the optional `check-pr` stage, [docs/05](05-ci-jenkins-sonar.md)),
4 = GitHub branch protection and CODEOWNERS. `java .agent/Sdlc.java selftest` checks the
layer-1 and layer-2 rules (236 checks).

## Files

| Rule | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| No reading or writing secrets (`.agent/local.env`, `.env`, `~/.m2/settings.xml`, keystores, ssh keys, ...) by tools or shell words | deny | credential files can't be committed | - | - |
| No reading secrets through globs (`cat .agent/*`, `.agent/local.en?`) or whole-folder readers (`grep -r`, `rg -uu`, `tar`, `zip`, `cp -r`, `find -exec`) over a folder that holds one; use `git grep` or name the code folder | deny | - | - | - |
| The tool itself refuses credential files as `--file` input, and `docs publish` attaches only image files inside the draft's folder | tool | - | - | - |
| No edits of guardrail and state files (`.agent/`, `.githooks/`, `.github/` agents, prompts, skills, hooks, instructions, workflows, CODEOWNERS, `AGENTS.md`, `.vscode/`, `.git/config`, `.git/hooks/`, `.sdlc/` except drafts and inbox) by tools **or shell writes** (`>`, `tee`, `sed -i`, `cp`, `mv`, `rm`, `Set-Content`, `git checkout --`, ...) | deny | warn | warn (check-pr) | CODEOWNERS review |
| Code edits only after `task start`, only inside the task's scope, by tools **or shell writes** | deny | pre-push: changed code outside the tasks' paths | - | - |
| Build, dependency and schema files (`pom.xml`, `.mvn/`, migrations, lint configs) | ask | - | - | review |
| No edits on a protected branch | deny | commit refused | - | - |
| Docs (`docs/**`, `README.md`) | allowed | | | |

## Commands

| Rule | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| Skipping or weakening checks: `-DskipTests`, `-Dmaven.test.skip`, `-Dsonar.skip`, `-Djacoco.skip`, `-fae`, `-fn`, `--passWithNoTests`, ... | deny | - | the build runs them | required checks |
| CI-only goals: `deploy`, `release:*`, `sonar:sonar`, `versions:set`, `npm publish`, `npm version` | deny | - | - | - |
| Dependency and snapshot changes: `npm install` / `pnpm add` / `yarn add`, `-u` / `--updateSnapshot` | ask | - | - | - |
| Disabled or focused tests (`@Disabled`, `@Ignore`, `assumeTrue(false)`, `it.skip`, `.only`) | note after edit | commit refused | check-pr | - |
| `--no-verify` (and any abbreviation git accepts, e.g. `--no-veri`), `-n`, changing `core.hooksPath`, `SDLC_*`/`HUSKY=0`/`SKIP=` variables | deny | - | - | - |
| Git aliases: defining one (`git config alias.*`, `git -c alias.*`, config includes) is refused; an existing alias is expanded and checked like the command it runs; shell aliases (`!...`) are refused | deny | - | - | - |
| Shell spellings that hide a path: `{a,b}` braces, `$'..'` escapes and variables set in the same line are expanded before checking; a path built from a variable the guard cannot resolve needs approval | deny / ask | - | - | - |
| Force push, deleting remote branches, pushing tags, pushing to protected branches | deny | pre-push refused | - | rulesets |
| Rewriting refs (`filter-branch`, `filter-repo`, `update-ref`, `replace`). Local `rebase` and `commit --amend` are allowed; pushing rewritten history needs a force push, which is denied | deny | - | - | force-push block |
| `git reset --hard`, `git clean -f`, `git checkout -- .`, `stash drop`, branch delete/rename | ask | - | - | - |
| `git clean -x`, git config outside the repo, identity/credential/remote changes | deny | - | - | - |
| Approval lines (`SDLC-APPROVED`) in any command | deny | - | - | - |
| `gh pr merge`, `gh pr review --approve`, releases, repo settings, secrets, `gh auth token`, raw `gh api` writes in every spelling (`-XPUT`, `--field=`, `-fk=v`, GraphQL mutations), `gh alias` changes, `gh extension install` | deny | - | - | rulesets |
| A `gh` command the guard does not know (an alias or extension) | ask | - | - | - |
| Story and task keys on the command line must be Jira keys (`PAY-123`), so the tool never writes outside `.sdlc/` | tool | - | - | - |
| `gh pr create` before the PR gate is met | deny | - | - | - |
| `git push` of an unreviewed or unverified fix while a PR is open, or after `loops.ci.max` failed CI rounds | deny | pre-push runs verify | - | - |
| Starting another implementer or ci-fixer after `loops.review.max` rounds | deny | - | - | - |
| Network tools (`curl`, `wget`, `ssh`, `scp`, `Invoke-WebRequest`, ...) | ask | - | - | - |
| Inline interpreter code (`node -e`, `python -c`, `perl -e`, `ruby -e`, `deno eval`, ...), which could write files the guard cannot see | ask | - | - | - |
| Downloaded code piped into an interpreter (`curl ... \| sh`, `iwr ... \| iex`) | deny | - | - | - |
| Machine changes (`sudo`, `su`, `chown`, `mkfs`, `dd`, `setx`, `reg`, `shutdown`) | deny | - | - | - |
| Printing the environment (`env`, `printenv`, `set`, `Get-ChildItem env:`), `$...TOKEN` variables | deny | - | - | - |
| Recursive deletes outside build output; deleting outside the project or `.git` | ask / deny | - | - | - |
| Encoded PowerShell (`-EncodedCommand`) | deny | - | - | - |
| Running the hooks, `setup` or `config apply` by hand | deny | - | - | - |
| Docker, kubectl, helm, terraform; `pip install` | ask | - | - | - |

Shell commands are split and checked part by part, through `bash -c`, `sh -c`, `cmd /c`,
`powershell -Command`, `eval`, `xargs`, `env`, `$( )` and backticks.

## External systems (MCP)

Agents **read** Jira, Confluence, GitHub and SonarQube through MCP. Any MCP tool whose name says
create, update, delete, transition, comment, merge, push, approve, publish, run, dispatch, ... is denied
(the action is the name's first verb, so `get_pull_request` is a read and `get_or_create_branch` a write);
an MCP tool with no verb the guard knows needs your approval;
writes go through the tool, which can only: comment (never an approval line), move issues to
In Progress / In Review (Done only with `jira.agentMayClose`), create sub-tasks from the approved
LLD, add a remote link, and publish the test design and the team's LLD. Every write is logged
in `.sdlc/logs/atlassian.jsonl`. The generated `.vscode/mcp.json` also starts the Data Center,
GitHub Enterprise and SonarQube servers in read-only mode.

## Workflow gates

| Gate | Enforced by |
| --- | --- |
| Test design approved before the LLD | designer agent; `docs pull` shows the approval status |
| LLD approved before tasks and code | `tasks create` and `task start` (also re-checks that the page did not change since) |
| Task dependencies (`dependsOn`) approved first | `task start` |
| Verified build before finishing | Stop hook (up to `guard.maxStopBlocks` times), pre-push hook |
| Review rounds: report for HEAD, no uncommitted changes, no contradictory or copied report, min..max | `review record`, PR gate |
| QA passed at HEAD when the test design has integration / contract / e2e scenarios | PR gate |
| Every scenario linked to a real test | `trace`, PR gate |
| PR only when all of the above hold | PreToolUse on `gh pr create` |
| Every fix reviewed and verified before it is pushed to an open PR; max failed CI rounds | PreToolUse on `git push` |

## Limits - be honest with your team

- **The hooks check text.** A determined script (writing a Python file that then edits code or
  pushes) can get around layer 1. That is why the same rules exist in git hooks, CI and GitHub,
  and why the pre-push hook and `check-pr` compare changed code with the tasks' paths. The hooks
  stop *mistakes and shortcuts*, which is what agents actually do.
- **The task-scope check needs local state.** In CI there is no `.sdlc/`, so `check-pr` there
  checks branch, commits, tests and secrets but not scopes or scenario links. Human review of
  the PR is the backstop.
- **Git hooks run on developer machines**; anyone can skip them with `--no-verify`. CI and
  branch protection cannot be skipped.
- **Approvals** prove that a listed human posted the exact line in Jira. If agents use **your**
  personal token, an agent could technically post through some other path; the hook, the tool
  and read-only MCP all refuse it, and `gates.approvers` plus a separate bot token for the tool
  close the gap.
- **Each agent tool can refuse things first.** Claude Code's own checks (for example on its
  settings file) and Codex's sandbox act before or besides the hooks; that is fine, they only
  add refusals.
- **Hooks are young in some IDEs.** Hooks for Copilot in VS Code are labelled preview in some
  versions; JetBrains support came later. Run the hook test ([docs/02 step 8](02-setup.md#step-8-the-hook-test))
  after IDE upgrades.
- **A broken guard fails closed** for tool calls (an exception denies the call and asks for
  `selftest`). An IDE **timeout** fails open; the compiled guard answers in about 0.1 s and the
  timeout is 30 s.

## Changing a rule

1. Branch `chore/PAY-n-...`. Settings: edit `.agent/sdlc.yml` ([docs/03](03-configuration.md)).
   Logic: edit `.agent/src/sdlc/*.java` and add a case to `SelfTest.java`.
2. `java .agent/Sdlc.java selftest` and `config check`.
3. PR -> CODEOWNERS review by the tech leads -> merge. Everyone gets the new rules on `git pull`
   (the compiled copy refreshes itself when the sources change).
