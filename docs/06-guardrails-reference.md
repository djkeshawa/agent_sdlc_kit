# 6. Guardrails reference and limits

**deny** = refused, the agent is told why, the refusal is logged in
`.sdlc/logs/guard-decisions.jsonl`. **ask** = the IDE asks you to approve. Layers: 1 = agent
hooks (Copilot, Claude Code, Codex: the same rules; each tool's edit and shell tools are
understood, including Codex's `apply_patch` and `bash -lc "..."`), 2 = git hooks, 3 = your CI (with the optional `check-pr` stage, [docs/05](05-ci-jenkins-sonar.md)),
4 = GitHub branch protection and CODEOWNERS. `java .agent/Sdlc.java selftest` checks the
layer-1 and layer-2 rules (520 checks, with the multi-component ones).

## Files

| Rule | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| No reading or writing secrets (`.agent/local.env`, `.env`, `~/.m2/settings.xml`, keystores, ssh keys, ...) by tools or shell words | deny | credential files can't be committed | - | - |
| No reading secrets through globs (`cat .agent/*`, `.agent/local.en?`) or whole-folder readers (`grep -r`, `rg -uu`, `tar`, `zip`, `cp -r`, `find -exec`) over a folder that holds one; use `git grep` or name the code folder | deny | - | - | - |
| The tool itself refuses credential files as `--file` input, and `docs publish` attaches only image files inside the draft's folder | tool | - | - | - |
| No edits of guardrail and state files (`.agent/`, `.githooks/`, `.github/` agents, prompts, skills, hooks, instructions, workflows, CODEOWNERS, `AGENTS.md`, `.vscode/`, `.git/config`, `.git/hooks/`, `.sdlc/` except drafts and inbox) by tools **or shell writes** (`>`, `tee`, `sed -i`, `cp`, `mv`, `rm`, `Set-Content`, `git checkout --`, ...) | deny | warn | warn (check-pr) | CODEOWNERS review |
| Code edits only after `task start`, only inside the task's scope, by tools **or shell writes** | deny | pre-push: changed code outside the tasks' paths | - | - |
| Relative paths are read from the folder the shell is in: the hook's `cwd`, a tool call's `workdir`, and every `cd` / `pushd` / `Set-Location` in the line (`cd src/main/java && echo x > A.java` is a code edit). For secrets and guardrail files the same words are also read from the repository root and from every folder the line names, so a subshell or a function cannot hide a `cd`; a path that climbs with `../` is also read as if it came back inside | deny | - | - | - |
| `cd` into a guardrail folder (`.agent`, `.github`, `.githooks`, `.git`, `.vscode`, `.claude`, `.codex`, `.sdlc` except drafts and inbox) | deny | - | - | - |
| Symbolic links are followed: a link in a folder agents may write to (`.sdlc/*/drafts`) that leads to code, a guardrail file or a secret is treated as that file, for edits, shell writes and reads | deny | - | - | - |
| Patches (`git apply`, `git am`, `patch`): every file the patch names is checked like an edit; a patch the guard cannot read needs approval. `--check` / `--stat` are free | deny / ask | pre-push: changed code outside the tasks' paths | - | - |
| Archives unpacked into the repository (`tar -x`, `unzip`, `7z x`, `jar x`, `cpio -i`), except into build output or the drafts folder | ask | pre-push: changed code outside the tasks' paths | - | - |
| Build, dependency and schema files (`pom.xml`, `.mvn/`, migrations, lint configs) | ask | - | - | review |
| No edits on a protected branch | deny | commit refused | - | - |
| Hidden files (`paths.hidden`: `.sdlc/hidden/**`, the scoping eval's truth and report) are read and written like secrets: never by agents | deny | - | - | - |
| Locked acceptance tests (`docs/test-locks/<STORY>.json` lists them): no agent edits them, by tools or shell writes; the lock files themselves are protected | deny | commit of a changed locked test refused | check-pr: FAIL when a locked test differs from its lock; WARN when a lock changed | CODEOWNERS (QE) on `docs/test-locks/` |
| `catalog-info.yaml` and the contract allowances (`docs/agent/contracts/`) are protected | deny | warn | warn | CODEOWNERS |
| Docs (`docs/**`, `README.md`) | allowed | | | |

## Commands

| Rule | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| Skipping or weakening checks: `-DskipTests`, `-Dmaven.test.skip`, `-Dsonar.skip`, `-Djacoco.skip`, `-Djacoco.haltOnFailure=false`, `-D...failOnViolation=false`, `-fae`, `-fn`, `--passWithNoTests`, ... | deny | - | the build runs them | required checks |
| The same flags through a side door: a wrapper run by a shell (`sh mvnw -DskipTests`), or the environment (`MAVEN_OPTS`, `MAVEN_ARGS`, `JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`, `NODE_OPTIONS`, `npm_config_*`; inline, `export`, `set`, `$env:`). `sdlc verify` also refuses to run while such a variable is set | deny | pre-push needs a verified build | the build runs them | required checks |
| Switching a Maven profile off (`-P!quality`, `-P-quality`) | ask | - | the build runs them | required checks |
| CI-only goals: `deploy`, `release:*`, `sonar:sonar`, `versions:set`, `npm publish`, `npm version` | deny | - | - | - |
| Dependency and snapshot changes: `npm install` / `pnpm add` / `yarn add`, `-u` / `--updateSnapshot` | ask | - | - | - |
| Disabled or focused tests (`@Disabled`, `@Ignore`, `assumeTrue(false)`, `it.skip`, `.only`) | note after edit | commit refused | check-pr | - |
| `--no-verify` (and any abbreviation git accepts, e.g. `--no-veri`), `-n`, changing `core.hooksPath`, `SDLC_*`/`HUSKY=0`/`SKIP=` variables | deny | - | - | - |
| Git settings and variables that make git run a program: `core.fsmonitor`, `core.pager`, `core.editor`, `core.sshCommand`, `diff.external`, `filter.*`, `merge.*.driver`, ... (`git config` and `git -c`), `GIT_SSH_COMMAND`, `GIT_EXTERNAL_DIFF`, `GIT_EDITOR`, ... Harmless values (`cat`, `less`, `true`, empty) are fine | deny | - | - | - |
| Variables and options that load other code into the tools: `LD_PRELOAD`, `DYLD_*`, `BASH_ENV`; an agent, trust store or proxy setting in `JAVA_TOOL_OPTIONS`, `MAVEN_OPTS`, `GRADLE_OPTS`, ... (`-javaagent`, `-Djavax.net.ssl.*`); `--require` / `--import` in `NODE_OPTIONS`; Java options on the guard's own tool (`java -javaagent:... .agent/Sdlc.java`, `java -cp ... sdlc.Main`) | deny | - | - | - |
| `PATH` with a folder agents can write to first (inside the repository, a temp folder, a relative folder); `HTTPS_PROXY` / `HTTP_PROXY` / `ALL_PROXY` | ask | - | - | - |
| Git pointed somewhere else: pushing to an address instead of a remote (`git push https://...`, `--repo`, `--receive-pack`), `git remote set-url`, `--git-dir` / `--work-tree` / `--exec-path`, `git daemon` | deny | - | - | - |
| `git remote add` / `rename` / `remove`, pushing to a remote other than `origin`, `git worktree add`, and git commands that run a command of the agent's choosing (`git rebase --exec`, `git bisect run`, `git submodule foreach`, `git difftool -x`), `git submodule add`, `git lfs install` | ask | - | - | - |
| Git aliases: defining one (`git config alias.*`, `git -c alias.*`, config includes) is refused; an existing alias is expanded and checked like the command it runs; shell aliases (`!...`) are refused | deny | - | - | - |
| Shell spellings that hide a path: `{a,b}` braces, `$'..'` escapes, backslash escapes (`local.e\nv`) and variables set in the same line are expanded before checking; a path built from a variable the guard cannot resolve needs approval | deny / ask | - | - | - |
| Command output used inside a command (`$(...)`, backticks) when it could be a path the agent chose: file contents, decoded text (`cat $(echo ... \| base64 -d)`). Facts (`date`, `pwd`, `git rev-parse`, `git diff --name-only`, ...) and a commit message in a quoted here-document are free | ask | - | - | - |
| Force push, deleting remote branches, pushing tags, pushing to protected branches | deny | pre-push refused | - | rulesets |
| Rewriting refs (`filter-branch`, `filter-repo`, `update-ref`, `replace`). Local `rebase` and `commit --amend` are allowed; pushing rewritten history needs a force push, which is denied | deny | - | - | force-push block |
| `git reset --hard`, `git clean -f`, `git checkout -- .`, `stash drop`, branch delete/rename | ask | - | - | - |
| `git clean -x`, git config outside the repo, identity/credential/remote changes | deny | - | - | - |
| Approval lines (`SDLC-APPROVED`) in any command | deny | - | - | - |
| `gh pr merge`, `gh pr review --approve` / `-a`, releases, repo settings, secrets, `gh auth token`, `gh auth status --show-token`, `git credential ...`, raw `gh api` writes in every spelling (`-XPUT`, `--field=`, `-fk=v`, GraphQL mutations), `gh alias` changes, `gh extension install` | deny | - | - | rulesets |
| A `gh` command the guard does not know (an alias or extension) | ask | - | - | - |
| Story and task keys on the command line must be Jira keys (`PAY-123`), so the tool never writes outside `.sdlc/` | tool | - | - | - |
| `gh pr create` (or its alias `gh pr new`) before the PR gate is met | deny | - | - | - |
| `git push` of an unreviewed or unverified fix while a PR is open, or after `loops.ci.max` failed CI rounds | deny | pre-push runs verify | - | - |
| Starting another implementer or ci-fixer after `loops.review.max` rounds | deny | - | - | - |
| Network tools (`curl`, `wget`, `ssh`, `scp`, `Invoke-WebRequest`, ...) | ask | - | - | - |
| Inline interpreter code (`node -e`, `python -c`, `perl -e`, `ruby -e`, `deno eval`, ...), which could write files the guard cannot see | ask | - | - | - |
| Running a file nobody reviewed (`sh x.sh`, `python3 x.py`, `node x.js`, `java X.java`, `./x.sh`, `source x.sh`): outside the repository, in `.sdlc/`, not committed, or changed outside the task scope. Committed files, in-scope changes and build output (`target/`, `dist/`, `node_modules/`) run freely | ask | - | - | - |
| Packages downloaded and run on the spot: `npx <pkg>` when it is not in `node_modules/.bin`, `npx --yes`, `npm exec`, `pnpm dlx`, `yarn dlx`, `bun x` | ask | - | - | - |
| Downloaded code piped into an interpreter (`curl ... \| sh`, `iwr ... \| iex`) | deny | - | - | - |
| Machine changes (`sudo`, `su`, `chown`, `mkfs`, `dd`, `setx`, `reg`, `shutdown`) | deny | - | - | - |
| Printing the environment (`env`, `printenv`, `set`, `Get-ChildItem env:`, `ps e`, `/proc/<pid>/environ`), `$...TOKEN` variables | deny | - | - | - |
| Recursive deletes outside build output; deleting outside the project or `.git` | ask / deny | - | - | - |
| Encoded PowerShell (`-EncodedCommand`) | deny | - | - | - |
| Running the hooks, `setup`, `upgrade` or `config apply` by hand; `tests lock` (QE's approval); `eval` (its report shows the ground truth) | deny | - | - | - |
| Docker, kubectl, helm, terraform; `pip install` | ask | - | - | - |

Shell commands are split and checked part by part, through `bash -c`, `sh -c`, `cmd /c`,
`powershell -Command`, `eval`, `xargs`, `env`, `$( )` and backticks. A line with backslashes is read
both ways, as sh/bash (`\n` is `n`) and as cmd/PowerShell (a path separator), and the stricter answer wins.

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
| Four eyes: the person who saved the page version cannot approve it (`gates.allowSelfApproval`) | `approval check`, `tasks create`, `task start` |
| Task scopes are narrow: at least `guard.minScopeDepth` folders before the first wildcard (Java 4, Node 2), nothing outside the repository | `tasks create` |
| Task dependencies (`dependsOn`) approved first | `task start` |
| Verified build before finishing | Stop hook (up to `guard.maxStopBlocks` times), pre-push hook |
| Review rounds: report for HEAD, no uncommitted changes, no contradictory or copied report, min..max | `review record`, PR gate |
| QA passed at HEAD when the test design has integration / contract / e2e scenarios | PR gate |
| Every scenario linked to a real test | `trace`, PR gate |
| PR only when all of the above hold | PreToolUse on `gh pr create` |
| Every fix reviewed and verified before it is pushed to an open PR; max failed CI rounds | PreToolUse on `git push` |
| Solution design approved before component stories; publish refused while components are disputed, requirements uncovered, removals not marked breaking, or names unknown to the catalog | `docs publish ... solution`, `stories create` |
| The LLD's lane is at least what the facts imply; a cross-component LLD names the current slice hash of an approved solution that includes this component | `tasks create` (lanes.enabled) |
| Contract files change only as the story's lane allows: nothing in patch, additions in component, exactly the slice's operations in cross-component; breaking changes only when the solution says `compatibility: breaking` | `check-pr` and the PR gate (from the committed `docs/agent/contracts/<STORY>.json`) |
| Acceptance tests locked by QE before code (`tests.lockAcceptance`) | `task start`; `verify` refuses while a locked test is changed |
| Agents cannot lock tests or run the scoping eval | PreToolUse on `sdlc tests lock` / `sdlc eval` |

## Limits - be honest with your team

- **The hooks check text.** Running a file that is not committed needs your approval, but code the
  agent may legitimately write and run (a test, an in-scope script) can still do anything the
  developer's account can, including reading `.agent/local.env`. That is why the same rules exist
  in git hooks, CI and GitHub, why the pre-push hook and `check-pr` compare changed code with the
  tasks' paths, and why a separate bot token with narrow scopes is worth having. The hooks stop
  *mistakes and shortcuts*, which is what agents actually do.
- **The shell's folder is known only when the host tells.** Claude Code and Codex send it with every
  tool call. Where a terminal stays open and the host does not report its folder, a `cd` in one call
  and a relative write in the next is read from the repository root; that is why `cd` into a
  guardrail folder is refused outright and the pre-push hook and `check-pr` re-check changed code.
- **Data flow is only followed inside one command line.** A path hidden in a file, or in a variable
  exported by an earlier command in a terminal that stays open, is not seen; command output used
  as an argument asks for approval instead.
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
- **A broken guard fails closed** for tool calls. An exception, a crash, sources that do not
  compile, a missing Java or a guard that cannot be found from the shell's folder all refuse the
  call and ask for `selftest`; so does a tool call nested too deep to check, and a guard that has
  not answered after 20 s. The IDE's own **timeout** (30 s) still fails open, which is why the
  guard gives up first; it normally answers in about 0.1 s.
- **The guard reads its rules from the repository it is installed in**, never from the folder the
  agent's shell is in. A `cd` to another folder or repository changes where relative paths are
  read from, not which rules apply.
- **Tokens travel only over https** (or to this machine), and the tool does not follow a redirect
  to another address. A server that only speaks http inside your network needs
  `guard.allowPlainHttp: true`.

### Limits of the multi-component checks

- The contract check reads OpenAPI and AsyncAPI files one level deep: removed operations and
  messages, removed response codes, new required fields and parameters, removed enum values,
  changed property types, removed response properties. Not: nested schemas, `oneOf` / `allOf`,
  formats, patterns, security. Add `oasdiff` to CI for more, and keep contract tests.
- `catalog scan` is a regular-expression scan with file and line for every finding: it misses
  calls built in unusual ways, and it can be fooled by commented-out code.
- A locked test can still be passed by code that special-cases its inputs. That is what the
  hold-out scenarios are for: QE's tests the agent never sees.
- Hold-out results show ids, titles and pass/fail only, but code under test sees the inputs at
  run time: keep the hold-out environment's logs away from agents.

## Changing a rule

1. Branch `chore/PAY-n-...`. Settings: edit `.agent/sdlc.yml` ([docs/03](03-configuration.md)).
   Logic: edit the class under `.agent/src/sdlc/` and add a case to the matching test class in
   `.agent/src/sdlc/selftest/` (`JavaGuardTests`, `NodeGuardTests`, `WorkflowTests`, ...). The
   source is in layers, and a class uses only its own layer and the ones before it: `util`
   (parsers, HTTP, processes), `core` (settings, state), `guard` (the rules), `workflow` (gates,
   documents, tasks), `integrations` (Jira, Confluence), `cli` (commands, hooks). Shell rules are small
   methods that take a `ShellCommand` and return a `Decision` (or null for "not mine"), grouped by
   subject in `EnvRules`, `CdRules`, `ReadRules`, `RunRules`, `WriteRules`, `BuildRules`, `GitRules`
   and `GhRules`; `ShellRules` lists them in the order they are asked. File rules are in `FileRules`.
   Every rule gets the repository, its configuration and the shell's folder from a `Guard`, built
   once per tool call, so no rule keeps state between calls. The workflow steps (`Approvals`,
   `Docs`, `Tasks`, `Sync`) reach Jira and Confluence only through the `IssueTracker` and `Wiki`
   interfaces and write through a `Console`; `WorkflowTests` runs them against an in-memory
   tracker and wiki, so a change to a gate can be tested without a server.
2. `java .agent/Sdlc.java selftest` and `config check`.
3. PR -> CODEOWNERS review by the tech leads -> merge. Everyone gets the new rules on `git pull`
   (the compiled copy refreshes itself when the sources change).
