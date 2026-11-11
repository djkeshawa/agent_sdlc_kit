# 3. Configuration (`.agent/sdlc.yml`)

An organisation can add a layer: `.agent/org.yml` (same keys, shipped by `sdlc upgrade` from the
organisation's kit). The merge is built-in defaults < stack preset < `.agent/org.yml` < `.agent/sdlc.yml`.

`sdlc.yml` is the only file you configure. Anything you leave out comes from built-in defaults:
first the common ones, then the preset for your `stack`. After editing:

```bash
java .agent/Sdlc.java config check     # validates and prints the effective key settings
java .agent/Sdlc.java config apply     # only after changing agents.*.model or mcp.*
```

**Replace or extend a list.** Setting a list replaces the default. To add to it, end the key
with `+`:

```yaml
paths:
  protected+: [docs/legal/**]      # the defaults plus docs/legal/**
  ask+: ["**/openapi/*.yaml"]
```

Globs: `*` matches inside one folder, `**` across folders; `{a,b}` alternatives.

Agents cannot edit this file (the hook refuses it) and CODEOWNERS makes a tech lead review
human changes. Secrets never go here: they go in `.agent/local.env` ([docs/02 step 4](02-setup.md#step-4-tokens-in-agentlocalenv)).

## Stack and project

| Key | Default | Meaning |
| --- | --- | --- |
| `stack` | `java-maven` | `java-maven`, `java-gradle` or `node`; picks the build, path and test presets below |
| `project.name` | | `{project}` in page titles |
| `project.team` | | `{team}` in the LLD title; tells agents which LLD is "ours" |

## Jira

| Key | Default | Meaning |
| --- | --- | --- |
| `jira.deployment` | `cloud` | `cloud` (basic auth with e-mail + token) or `datacenter` (bearer PAT) |
| `jira.url` | | site URL; `JIRA_BASE_URL` in local.env overrides it for API calls (scoped tokens) |
| `jira.projectKeys` | `[]` | keys accepted in branches, commits and PR titles |
| `jira.issueTypes.task` | `Sub-task` | issue type created for each LLD task |
| `jira.issueTypes.story` | `Story` | issue type `stories create <EPIC>` creates per component of a solution design |
| `jira.epicLinkField` | `""` | Data Center only: the epic link custom field, to find a story's epic |
| `jira.statuses.inProgress` / `inReview` / `done` | `In Progress` / `In Review` / `Done` | exact status names of your workflow |
| `jira.agentMayClose` | `false` | `true` lets `sync` move tickets to Done after the merge |

## Confluence

| Key | Default | Meaning |
| --- | --- | --- |
| `confluence.url` | | e.g. `https://yourcompany.atlassian.net/wiki` (may be another site than Jira); `CONFLUENCE_BASE_URL` overrides it for API calls |
| `confluence.searchSpaces` | `[]` | spaces searched for one pagers, HLDs, test designs, LLDs (all teams) |
| `confluence.titlePatterns.onePager` / `hld` / `lld` / `testDesign` / `solution` | see `sdlc.yml` | regexes that classify a page by its title |
| `confluence.publish.testDesign.space` / `.parentId` / `.title` | `""` / `""` / `{key} - Test scenarios` | where `/test-design` publishes |
| `confluence.publish.lld.space` / `.parentId` / `.title` | `""` / `""` / `{key} - LLD - {team}` | where `/design` publishes |
| `confluence.publish.solution.space` / `.parentId` / `.title` | `""` / `""` / `{key} - Solution design` | where `/solution` publishes an epic's solution design ([docs/08](08-multi-component.md)) |
| `confluence.mermaidMacro` | `""` | name of your Mermaid macro, if you have the app; else diagrams stay as code blocks |
| `confluence.mindmapImage` | `true` | attach the test design's mind map as a PNG |

## GitHub and related repositories

| Key | Default | Meaning |
| --- | --- | --- |
| `github.host` | `github.com` | or your Enterprise host (changes the generated MCP config) |
| `github.relatedRepos` | `[]` | other teams' repos the designer may read: `{name, repo, team, purpose}`; `sdlc repos sync` makes local read-only copies |
| `github.commitPages` | `3` | pages of 100 commits read per branch when looking for a Jira key in the known repositories |
| `github.org` | `""` | your GitHub organisation; limits the commit search of `eval collect` (and `catalog cochange`) to it |
| `eval.dir` | `docs/agent/eval` | the scoping eval's cases (`scoping/cases/`); the ground truth lives in `.sdlc/hidden/eval/truth/`, [docs/08](08-multi-component.md#phase-0-the-scoping-eval) |

## CI (read only)

| Key | Default | Meaning |
| --- | --- | --- |
| `ci.jenkins.url` | `""` | your Jenkins; with `JENKINS_USER`/`JENKINS_TOKEN` the tool reads failed builds' test reports and logs |
| `ci.sonar.url` / `ci.sonar.projectKey` | `""` | SonarQube (or `https://sonarcloud.io`); the tool reads the PR's gate, new issues and coverage gaps |
| `ci.waitMinutes` / `ci.pollSeconds` | `60` / `30` | how long `ci wait` waits for the PR checks, and how often it asks (min 10 s) |

## Human gates and loops

| Key | Default | Meaning |
| --- | --- | --- |
| `gates.testDesign` / `gates.lld` / `gates.solution` | `required` | `required` blocks, `optional` warns, `off` skips the approval |
| `gates.approvers.testDesign` / `.lld` / `.solution` | `[]` | who may approve (Cloud account ids, DC usernames); empty = any human. For another team's LLD (`feature status`) the list is not applied: that team's own config holds it |
| `gates.botAccounts` | `[]` | accounts whose comments never count as approval (put the tool's bot here) |
| `gates.allowSelfApproval` | `false` | `false` = the account that saved the page version cannot approve it (four eyes). With a shared bot token the page author is the bot, so this only bites when people publish with their own token |
| `loops.review.min` / `.max` | `2` / `5` | review rounds per task; the PR gate needs `min` with the last one APPROVE; past `max` the tool refuses and hands over |
| `loops.qa.max` | `3` | failed QA rounds before handing over |
| `loops.ci.max` | `3` | failed CI rounds before pushes are refused and a human takes over |
| `delivery.baseBranch` | `develop` | PR base; `origin/<base>` is compared for policy checks |
| `delivery.createSubtasks` | `true` | `false` = tasks tracked locally only (small stories) |
| `delivery.qa` | `auto` | `auto` (when the test design has integration / contract / e2e / api scenarios), `always`, `never` |

## Several components ([docs/08](08-multi-component.md))

| Key | Default | Meaning |
| --- | --- | --- |
| `catalog.file` | `catalog-info.yaml` | this repository in the component catalog (Backstage format); protected, CODEOWNERS |
| `catalog.repos` | `[]` | more repositories (`owner/name`) for `catalog sync`, besides `github.relatedRepos` |
| `tests.lockAcceptance` | `false` | `true`: `task start` refuses until QE has locked the story's acceptance tests (`tests lock`) |
| `holdout.jenkinsJob` | `""` | QE's Jenkins job that runs the hold-out scenarios against the PR build (on `ci.jenkins.url`); read by `holdout status` |
| `build.mutation` | `""` | optional mutation test command run by `verify` after the build (e.g. PIT on changed code); non-zero fails verify |
| `lanes.enabled` | `false` | `true`: `tasks create` checks the LLD's `lane:` and `solutionSlice:`, the PR policy refuses contract file changes in a patch-lane story |

## Coding agents, models and MCP

| Key | Default | Meaning |
| --- | --- | --- |
| `tools` | `[copilot]` | the coding agents used here: `copilot`, `claude`, `codex`. `config apply` generates the Claude Code (`.claude/`, `CLAUDE.md`) and Codex (`.codex/`, `.agents/skills/`) setups from `.github` |
| `agents.<name>.model` | `""` | Copilot only: model name as the Copilot picker shows it, e.g. `Claude Sonnet 4.5 (copilot)`; comma-separated = fallbacks; empty = the picker's choice. Run `config apply` |
| `mcp.servers.atlassian` / `github` / `sonarqube` | `true` | which read-only MCP servers `config apply` writes to `.vscode/mcp.json` |
| `mcp.allowWrites` | `false` | `true` stops refusing MCP write tools (not recommended) |
| `mcp.serverHints` | `[mcp, atlassian, jira, ...]` | words that identify external MCP tools by name |

## Git

| Key | Default | Meaning |
| --- | --- | --- |
| `git.protectedBranches` | `[main, master, develop, "release/*"]` | no commits, pushes or edits here |
| `git.branchPattern` | `^(feature\|bugfix\|hotfix\|chore)/[A-Z][A-Z0-9]+-[0-9]+(-[a-z0-9._-]+)?$` | branch names |
| `git.commitPattern` | `^(feat\|fix\|test\|refactor\|docs\|chore\|perf\|build\|ci\|style\|revert)\([A-Z][A-Z0-9]+-[0-9]+\)!?: .{5,}` | commit subjects, e.g. `feat(PAY-101): add refund window policy` |

## Paths

| Key | Default | Meaning |
| --- | --- | --- |
| `paths.protected` | `.agent/**`, `.githooks/**`, `.github/{hooks,agents,prompts,skills,instructions,workflows}/**`, `.github/copilot-instructions.md`, `.github/CODEOWNERS`, `AGENTS.md`, `CLAUDE.md`, `.claude/**`, `.codex/**`, `.agents/**`, `.mcp.json`, `Jenkinsfile`, `.vscode/**`, `sonar-project.properties`, `.gitattributes`, `.sdlc/**` | agents never edit these (tool or shell); CODEOWNERS reviews human changes |
| `paths.agentWritable` | `.sdlc/*/drafts/**`, `.sdlc/*/inbox/**` | the exceptions inside `.sdlc/` |
| `paths.secret` | `.agent/local.env`, `**/*.env`, `**/.env.*`, `**/.netrc`, `**/.m2/settings.xml`, keystores, ssh and private keys (`**/*.key`, `**/*key.pem`), the token files of `gh`, cloud CLIs, Docker and Kubernetes (`**/.config/gh/**`, `**/.aws/**`, `**/.kube/config`, ...), `/proc/*/environ`, `**/secrets/**`, ... | agents never read or write these; commits of them are blocked |
| `paths.hidden` | `.sdlc/hidden/**` | agents never read or write these (eval ground truth and report, any local copy of hold-out tests); unlike `paths.secret`, a commit of them is not blocked, but keep them out of git: git commands would show them |
| `paths.code` | Java: `src/**`, `**/src/**` | code: edits need a started task and must be in its scope |
| `paths.main` / `paths.test` | Java: `**/src/main/**` / `**/src/test/**`, `**/src/it/**`, `**/src/integrationTest/**` | main vs test code (tests-with-changes, disabled-test checks) |
| `paths.ask` | Java: `pom.xml`, `.mvn/**`, `mvnw*`, DB migrations, checkstyle/spotbugs configs, `lombok.config` | edits need a human click (build, dependencies, schema) |
| `paths.doc` | `docs/**`, `README.md`, `CHANGELOG.md` | always editable (outside protected paths) |
| `trace.dir` | `docs/test-links` | where `<STORY>.md` links scenarios to tests |
| `verify.ignore` | `[]` | extra globs left out of the "verified for these files" fingerprint |
| `analyse.output` | `docs/agent/project-profile.md` | the analyst's output |

## Build and tests

| Key | Java Maven default | Meaning |
| --- | --- | --- |
| `build.verify` / `build.verifyWindows` | `./mvnw -B -ntp verify` / `mvnw.cmd -B -ntp verify` | the full local build `sdlc verify` runs and the pre-push hook requires |
| `build.preCommit` / `build.preCommitWindows` | `""` | quick command for the pre-commit hook, e.g. `./mvnw -B -ntp -q spotless:check` |
| `build.testOne` | `./mvnw -B -ntp test -Dtest=<Class>#<method> ...` | how agents run one test |
| `build.tools` | `mvn`, `mvnw`, `./mvnw`, `mvnw.cmd` | commands the build rules apply to |
| `build.forbiddenArgs` | `-DskipTests`, `-Dmaven.test.skip`, `-Dsonar.skip`, `-Djacoco.skip`, `-fae`, `-fn`, ... | refused: they hide failures |
| `build.forbiddenGoals` | `deploy`, `release:*`, `sonar:sonar`, `versions:set`, ... | refused: CI's or the release manager's job |
| `build.askArgs` | `[]` | args that need a human click |
| `tests.codeExtensions` | `java`, `kt`, `groovy`, `scala` | code files for secret and test checks |
| `tests.disabledPatterns` | `@Disabled`, `@Ignore`, `assumeTrue(false)`, ... | refused in commits |
| `tests.requireTestsForMainChanges` | `true` | main code changes need test changes (PR policy; warning at commit) |

The **Node preset** (`stack: node`) replaces the Java values:

| Key | Node default |
| --- | --- |
| `build.verify` | `npm run lint --if-present && npm test && npm run build --if-present`. Left at this default, a `pnpm-lock.yaml` or `yarn.lock` in the repository switches the same steps to pnpm or yarn (yarn: lint and build only when `package.json` has the script) |
| `build.testOne` | `npm test -- <path/to/file.test.ts> -t '<test name>'` |
| `build.tools` | `npm`, `npx`, `yarn`, `pnpm`, `jest`, `vitest`, `mocha`, `ng`, `nx` |
| `build.forbiddenArgs` | `--passWithNoTests`, `--no-verify`, `--ignore-scripts`, `--forceExit`, `--bail=0` |
| `build.askArgs` | `-u`, `--updateSnapshot`, `install`, `i`, `add`, `ci`, `dlx`, `exec` (dependencies, snapshots) |
| `build.forbiddenGoals` | `publish`, `version`, `release` |
| `paths.code` | every `*.js`, `*.jsx`, `*.ts`, `*.tsx`, `*.mjs`, `*.cjs`, `*.vue`, `*.svelte` file anywhere, plus `src/**`, `lib/**`, `app/**`, `apps/**`, `packages/**`, `libs/**`, `components/**`, `pages/**`, `server/**`, `client/**` and the test folders |
| `paths.main` | `src/**`, `lib/**`, `app/**`, `apps/**`, `packages/**`, `libs/**`, `components/**`, `pages/**`, `server/**`, `client/**`, `routes/**`, `controllers/**`, `models/**`, `middleware/**`, `services/**`, `api/**`, `utils/**`, `helpers/**`, `hooks/**`, `store/**`, `features/**`, `modules/**`, and `index`/`server`/`app`/`main` files in the root (main code needs a test change) |
| `paths.test` | `**/*.{test,spec}.{js,jsx,ts,tsx,mjs,cjs}`, `**/__tests__/**`, `test/**`, `tests/**`, `e2e/**`, `cypress/**`, `playwright/**` |
| `paths.ask` | `package.json`, lockfiles, `tsconfig*.json`, `jest/vitest/vite/playwright/cypress` configs, ESLint/Prettier/Babel/webpack configs, `.nvmrc` |
| `tests.codeExtensions` | `js`, `jsx`, `ts`, `tsx`, `mjs`, `cjs`, `vue`, `svelte` |
| `tests.disabledPatterns` | `it/test/describe.skip/.only/.todo`, `xit`, `xdescribe`, `fit`, `fdescribe`, `@ts-nocheck`, file-wide `eslint-disable` (in test files) |

Gradle differs from Maven only in the build keys (`./gradlew build`, `-x` refused). See
`.agent/src/sdlc/core/Defaults.java` for the exact lists. Test links for JS name the test by its
string: `| TS-01 | src/refund/policy.test.ts#refuses a refund after day 30 |`.

## Guard

| Key | Default | Meaning |
| --- | --- | --- |
| `guard.requireTaskForCodeEdits` | `true` | code edits need `task start` |
| `guard.minScopeDepth` | Java `4`, Node `2` | folders a task scope must name before its first wildcard: `src/main/java/com/**` passes, `src/main/java/**` and `**/src/**` are refused by `tasks create` |
| `guard.maxFileKb` | `1024` | larger files are refused at commit |
| `guard.maxStopBlocks` | `3` | how often the Stop hook sends an agent back to run `verify` before giving the turn to you |
| `guard.allowPlainHttp` | `false` | `true` lets the tool send tokens to an `http://` server (Jira, Confluence, Jenkins, SonarQube) that is not this machine; leave it off unless a server inside your network has no https |

## Environment variables (`.agent/local.env` or the environment)

| Name | Used for |
| --- | --- |
| `JIRA_USER`, `JIRA_TOKEN` | Jira (Cloud: e-mail + API token; DC: empty + PAT) |
| `JIRA_BASE_URL` | API base instead of `jira.url` (scoped tokens: `https://api.atlassian.com/ex/jira/<cloudId>`) |
| `CONFLUENCE_USER`, `CONFLUENCE_TOKEN` | Confluence; Cloud falls back to the Jira user and token |
| `CONFLUENCE_BASE_URL` | API base instead of `confluence.url` (scoped tokens: `.../ex/confluence/<cloudId>/wiki`) |
| `JENKINS_USER`, `JENKINS_TOKEN` | read failed builds |
| `SONAR_TOKEN` | read the quality gate (not needed for public SonarQube Cloud projects) |
| `HTTPS_PROXY` | company proxy |
