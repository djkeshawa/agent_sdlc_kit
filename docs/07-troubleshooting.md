# 7. Troubleshooting

First run these two; they explain most problems:

```bash
java .agent/Sdlc.java doctor      # tools, tokens, connections
java .agent/Sdlc.java status      # branch, story, task, scope, loops, what the PR gate misses
```

Refusals are logged in `.sdlc/logs/guard-decisions.jsonl`; Jira/Confluence writes in
`atlassian.jsonl`; verify runs in `verify.jsonl`.

## Setup and Java

| Symptom | Cause and fix |
| --- | --- |
| `This Java has no compiler` | A JRE, not a JDK. Install a JDK 17+ and put it first on the PATH. |
| Maven: `release version 17 not supported` although Java is 21 | The JDK package lacks `lib/ct.sym` (some Linux `-headless` packages). Install the full JDK (e.g. Temurin), or use `maven.compiler.source/target` instead of `release`. |
| `Compiling .agent/src failed` | A local edit of the tool's sources broke it. `git checkout .agent/src` or fix the error shown. |
| `SELFTEST FAILED` after editing `sdlc.yml` | Your change broke a built-in expectation (for example a protected path removed). Read the failing line; revert or adjust. |
| `run this from inside the repository` | Run `java .agent/Sdlc.java` from the repository root (or below it). |

## Jira and Confluence

| Symptom | Cause and fix |
| --- | --- |
| `401 ... a scoped token lacks this scope (server says: Unauthorized; scope does not match)` | A scoped token used against the wrong site, or without a needed scope. Scoped tokens are tied to one site and must go through `https://api.atlassian.com/ex/jira/<cloudId>` (`JIRA_BASE_URL`) and `.../ex/confluence/<cloudId>/wiki` (`CONFLUENCE_BASE_URL`). Get the cloud id from `https://<site>.atlassian.net/_edge/tenant_info`. Scopes: [docs/02 step 4](02-setup.md#step-4-tokens-in-agentlocalenv). |
| `doctor`: Jira login works only through the project | The token has no `read:me` scope. Fine: the tool does not need it. |
| `403 ... (server says: Tenant is restricted: suspended-inactivity)` | The Atlassian product was suspended for inactivity. An org admin reactivates it at admin.atlassian.com; until then nothing works through the API. |
| `403 Forbidden` otherwise | The token's user lacks permission (project, space, or create pages). |
| `404 Not Found` | Wrong key or id, or the base URL: Confluence Cloud ends in `/wiki`. |
| Confluence on another site than Jira | Set `confluence.url` to that site and `CONFLUENCE_USER` / `CONFLUENCE_TOKEN` (a Jira token does not cover another site). |
| `PKIX path building failed` / TLS not trusted | Your company inspects TLS. Import the company root CA into the JDK's trust store (`keytool -importcert -cacerts -alias company-root -file root.crt`), or ask IT for the JDK they provide. |
| `cannot reach ...` | Proxy or VPN. Set `HTTPS_PROXY` in `.agent/local.env`. |
| `docs find` does not list a page you just created | Confluence search indexes new pages after a short delay (we measured 30-60 s). The tool finds its **own** test design and LLD by exact title, without search; other pages appear after indexing. |
| `approval check`: `no approval comment yet` right after posting | Jira can take a few seconds to return a new comment. Run it again. |
| `approves another version of the page (now v2)` | The page was edited after the line was printed. Get a new line: `approval line KEY lld`, approve again. |
| `comment ... was edited; post a fresh one` | Edited comments never count. Post a new comment. |
| `not in gates.approvers.lld` | The author is not in the approvers list. Add their account id, or have a listed person approve. |
| Approval comment ignored | Bot comments (`gates.botAccounts`, or containing `[sdlc-bot]`) never count. Pasting the line inside a code block is fine. |
| `the LLD has no work breakdown` | The LLD needs a ```` ```yaml ```` block starting with `# sdlc-tasks` (see `.agent/templates/lld.md`). |
| `scope 'src/**' is too broad` | Narrow the task's scope in the LLD: name at least `guard.minScopeDepth` folders before the first wildcard (Java 4: `src/main/java/com/acme/refund/**`; Node 2: `src/refund/**`). |
| `the person who saved this version of the page cannot approve it` | Four eyes: someone other than the page's last author approves. A team of one sets `gates.allowSelfApproval: true`. |
| `VERIFY REFUSED: MAVEN_OPTS passes -DskipTests to the build` | A variable in your terminal switches checks off. `unset MAVEN_OPTS` (or the variable named) and run `verify` again. |
| An agent is asked to approve `python3 x.py` / `sh x.sh` / `npx <pkg>` | The file is not committed (or the package is not installed), so nobody has reviewed what it runs. Read it, then approve, or commit the script first. |
| `The LLD changed since the tasks were created` | Re-approve the new version, then `tasks create STORY` again. |

## Hooks and agents

| Symptom | Cause and fix |
| --- | --- |
| Nothing is refused in the hook test | [docs/02 step 8](02-setup.md#step-8-the-hook-test): Copilot policy, trusted folder, hooks setting, then run the hook by hand. |
| `Refused: no active task` | Code edits need `java .agent/Sdlc.java task start <TASK>` (after the LLD is approved and `tasks create` ran). |
| `outside the scope of task ...` | The task's scope does not include the file. If the task really needs it, stop: the LLD's scope must change and be re-approved. |
| `the guard failed (...)` on every tool call | The tool crashed and fails closed. A human runs `java .agent/Sdlc.java selftest` and fixes the cause. |
| The agent keeps being sent back to run verify | The Stop hook: code changed since the last passing build. Run `verify`; after `guard.maxStopBlocks` attempts the turn goes back to you. |
| Claude Code / Codex ignore the guardrails | `tools` in `sdlc.yml` lacks the tool, or `config apply` was not run. Codex: trust the project and hooks when it asks (again after `hooks.json` changes). Debug: `touch .sdlc/hook-debug`, then read `.sdlc/logs/hook-payloads.jsonl`. |
| Codex: `failed to load skill ...: invalid YAML` | Codex parses skill front matter strictly. Quote descriptions that contain `: ` and double an apostrophe inside single quotes (`'the team''s LLD'`) in the `.github` source, then `config apply`. |
| Codex: `hook: Stop Failed` / `SessionStart Failed` | Hooks generated by an older kit print the Copilot output shape. Update `.agent/src` and run `config apply` (hooks then run with `--for codex`). |
| `NOT RECORDED: REVIEWED_COMMIT ... is not the current HEAD` | The reviewer reviewed an older commit. Review HEAD. |
| `NOT RECORDED: the report is identical to round N's` | A copied report. Each round is a fresh review. |
| `the pull request gate is not met yet` | The list says what is missing (reviews, QA at HEAD, uncommitted files, verify, policy). After a rebase, every commit is new: review and QA again. |
| `this branch has an open pull request: every fix is reviewed before it is pushed` | Run the reviewer on the fix commit, `verify`, then push. |
| `Changed code stays inside the allowed paths ...: outside -> [...]` | Code outside every task's scope is in the branch. Move it to its own story, or change the LLD and re-approve. Guardrail files are exempt here (CODEOWNERS reviews them). |

## Git hooks

| Symptom | Cause and fix |
| --- | --- |
| Hooks do not run at all | `git config core.hooksPath` must be `.githooks`: run `java .agent/Sdlc.java setup` once per clone. |
| `Commit subject ... must look like: feat(PAY-123): ...` | `type(KEY-123): summary` with a key from `jira.projectKeys`. |
| `Possible secret` false positive | Add the comment `guard:allow-secret` on that line, and tell a reviewer why. |
| `Pre-commit command failed` | Your `build.preCommit` (e.g. Spotless) failed: `./mvnw spotless:apply`. |
| pre-push runs the whole build | The files are not verified yet; `verify` first, or let the hook run it. |
| `Base origin/develop is missing locally` | `git fetch origin`. |

## CI

| Symptom | Cause and fix |
| --- | --- |
| `No checks reported on PR` | Jenkins does not build PRs or does not report status to GitHub ([docs/05](05-ci-jenkins-sonar.md)). |
| `gh pr checks failed` | `gh` older than 2.40 or not logged in: `gh auth status`. |
| Digest says "Set JENKINS_USER and JENKINS_TOKEN" | Add a Jenkins API token to `local.env` to see failed tests and log lines. |
| `(SonarQube: 401 ...)` | `SONAR_TOKEN` missing or expired, or the project is private. |
| Every PR fails the Sonar gate on coverage | No coverage report: JaCoCo for Maven ([docs/05](05-ci-jenkins-sonar.md#54-maven-quality-plugins)); for Node run tests with coverage to `coverage/lcov.info` and set `sonar.javascript.lcov.reportPaths` ([docs/05](05-ci-jenkins-sonar.md#55-node--typescript-quality-setup)). |
| CI digest shows the failed Sonar gate but no "Files with uncovered new lines" | Kit versions before November 2026 sorted SonarQube's new-code metrics without `metricPeriodSort` and read only `period.value`; SonarQube Cloud returned nothing. Update `.agent/src`. |
| Node: CI digest shows no failed test names | The runner writes no JUnit XML. Vitest: `reporters: ["default", "junit"]`; Jest: `jest-junit` (`snippets/node-quality.md`). |
| `trace`: test 'refuses ...' not found in a `.ts` file | JS test links use the exact string in `it(...)` / `test(...)`, after the `#`. |
| `node -e` / `python -c` asks for approval | Inline code cannot be checked by the guard. Approve only after reading it, or put the code in a script file. |
| `check-pr` in Jenkins: `Branch '' matches the naming rule` fails | Old kit version: the tool now reads `CHANGE_BRANCH` (Jenkins) or `GITHUB_HEAD_REF` when HEAD is detached. Update `.agent/src`. |
| SonarQube Cloud shows two projects for the repo | The GitHub app imported it with automatic analysis. Delete that one or turn automatic analysis off. |

## Resetting local state

`.sdlc/` holds the local workflow state (current story and task, rounds, pulled sources, drafts,
logs). It is git-ignored and protected from agents. To start a story over locally:

```bash
rm -rf .sdlc/PAY-100 .sdlc/current.json .sdlc/verify.json
```

Jira tickets and Confluence pages stay; `tasks create` finds the existing sub-tasks again.
