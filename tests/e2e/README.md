# End-to-end test of the kit

One story from ticket to merge, against mock services, in about a minute:

```bash
python3 tests/e2e/run.py
```

It needs a JDK 17+, git and Python 3 on Linux or macOS (WSL on Windows). No Maven, no network,
no accounts. The last line is `N/N checks passed`; the exit code is 0 only when all pass.
`--keep` leaves the test project on disk so you can look at `.sdlc/` and the git history.

## What it does

`run.py` starts `mock.py` on a free local port, runs `flow.py` against `template/` in a
temporary folder, then stops the mock.

| File | Role |
| --- | --- |
| `mock.py` | Jira Cloud, Confluence Cloud, Jenkins and SonarQube in one small HTTP server, plus a PR store for the fake `gh`. Test control is under `/__admin/*` |
| `bin/gh` | a fake GitHub CLI: `pr create`, `pr view`, `pr checks`, `auth status` |
| `flow.py` | the test itself: installs the template into a scratch repository and walks the flow |
| `vitest.py` | `sdlc check` against real Vitest, run by `flow.py` in its namespace (below) |
| `maven.py` | `sdlc check` against real Maven, Surefire and JUnit 4 and 5: `python3 tests/e2e/maven.py BUILD WORKDIR`. Not part of `run.py`; `sh verify/run.sh real` runs it |
| `vitest-runtime/` | the pinned Vitest that `vitest.py` runs against (`npm ci` there; `verify/run.sh real` does it) |
| `multi.py` | the multi-component checks (scoping eval, catalog, solution design, stories, contracts, locks, hold-outs), run by `flow.py` in its namespace |
| `run.py` | starts and stops everything |
| `live.py` | the same tool against a real Jira and Confluence test project. Not part of `run.py`; `sh verify/run.sh live` runs it ([below](#the-live-run)) |

`flow.py` covers: setup, `config check` / `config apply`, doctor, the git hooks, `docs pull`,
test design publish with the mind map, approvals (wrong person, edited comment, changed page,
four eyes), LLD publish, sub-tasks, task scope, test-first `verify`, the commit hooks (message,
secret, disabled test), the review loop, QA, the PR gate, the push guard, two CI rounds (red on
the Sonar gate, then green), retro, merge sync, and the hook payloads of Copilot, Claude Code
and Codex. The build is a stand-in (`tools/Build.java` compiles `src/` and runs the tests) so
that the test does not need Maven.

It also replays the spellings that once got past the guard, and must stay refused or ask:
escaped and decoded secret paths, `-DskipTests` through a wrapper shell or `MAVEN_OPTS`, git
settings that run a program, `gh pr new`, `gh pr review -a`, `cd` followed by a relative path,
a symbolic link in the drafts folder, patches and archives, and scripts nobody reviewed.

## Real Vitest

The self test runs the check's own reporter for Vitest under `node` when `node` is on the PATH
(it says in one line when it is not); the flow then checks that it ran. `vitest.py` runs `sdlc
check` against real Vitest on throw-away repositories (retries, a guard replaced, ordinary work,
titles with line breaks, tabs and the characters XML changes) only where it is told where Vitest
is installed, as the test installs nothing from the network:

```bash
SDLC_E2E_VITEST_NODE_MODULES=/path/to/node_modules python3 tests/e2e/run.py
```

The folder is linked into each repository, never changed by the test (Vitest writes its own cache
there, under `.vite`). Without the variable the flow prints one `SKIP` line for these checks. By
hand: `python3 -I tests/e2e/vitest.py BUILD NODE_MODULES WORKDIR`, with BUILD a copy of a self-test
copy's `.agent/.build` outside any `.agent` folder.

## The live run

The mock answers the way we think Jira and Confluence answer. `live.py` asks the real services. It
makes one story and a few pages in a **test** project and a **test** space, runs the tool against
them, and then reads what the services hold through their own REST API.

```bash
export SDLC_LIVE_SITE=https://yourtest.atlassian.net
export SDLC_LIVE_PROJECT=SKE          # the key of the Jira test project
export SDLC_LIVE_SPACE=SKE            # the key of the Confluence test space
export SDLC_LIVE_PARENT=131387        # the id of a page in that space; the run makes its pages under it
export SDLC_LIVE_ENV=/path/to/local.env   # JIRA_USER and JIRA_TOKEN of the bot account
sh verify/run.sh live                 # or: python3 tests/e2e/live.py
```

| It checks | Why the mock cannot |
| --- | --- |
| `doctor` logs in as the bot and reaches Confluence | The mock takes any login |
| `docs pull` finds a page through the search of Confluence, and reads the story and the page text | The real search lags behind a write (the run waits up to 7 minutes for it), and the real text has the real format |
| A published page lands directly under the configured parent page, with its table and its mind map image | The mock keeps no page tree and no real attachment |
| The comment the tool posts links to the page on the site | The mock has no address of its own to get wrong |
| That comment, and the exact approval line posted by the bot account, are no approval | The mock gives the account ids that the test hands it |
| `tasks create` is refused by the gate, with the line to post, and Jira holds no sub-task | It reads the real ticket afterwards |

With `SDLC_LIVE_ROLES=/path/to/roles.env` (`QELEAD_USER`, `QELEAD_TOKEN`, `TECHLEAD_USER`,
`TECHLEAD_TOKEN`) the run also posts the approvals as those two people and goes on: the wrong
approver, the right one, a page edited after its approval, two real sub-tasks (made with the real
issue type of the project), and `task start` moving the real tickets. The four-eyes rule is not
tested live: the bot saves every page version of the run. Only a person starts it this way: the approvals of the leads are theirs
to give. Without the variable the last line says `gates only`.

What to know before you run it:

- **Use a project and a space that nobody works in.** The run writes there.
- **It runs only when named.** It is not in the default layers and not in CI, which has no accounts.
- **It tidies up as far as the bot may.** Its pages go to the trash of the space (a page the bot
  may not delete is archived, and named). A bot without the permission to delete issues leaves
  its story and sub-tasks behind; the last lines name them, and each has the label
  `sdlc-live-run`, so `labels = sdlc-live-run` finds them all.
- **The secrets stay out of the output.** Tokens are replaced by `***` in what is printed, and a
  redirect is not followed. The copy of `local.env` in the test project is removed at the end,
  also with `--keep`, after a failure, and when the run is stopped by Ctrl-C, `kill` or a closed
  terminal. A stop that Python cannot catch (`kill -9`) leaves it, in the temporary folder,
  readable by you alone.
- **The login goes to `SDLC_LIVE_SITE` only.** The tool lets `JIRA_*` and `CONFLUENCE_*` of the
  shell win over `local.env`, so the run takes those (and `JENKINS_*`, `SONAR_*`) out of the
  tool's environment. A scoped token with `JIRA_BASE_URL` is therefore not supported here.
- **The run stays in its own folder.** It takes `GIT_*` out of the tool's environment too: a
  `GIT_DIR` of the shell would make the run commit into that repository.
- Exit code 2 means nothing was tested: a setting is missing, or the site does not answer or
  refuses the bot.

## When to run it

After any change in `template/.agent/src`. The flow runs the self test too (in `setup` and at
the end), so this one command covers both. CI runs it on every push and pull request
(`.github/workflows/selftest.yml`).

Add a check to `flow.py` when you add a rule that needs the whole flow to prove (a gate, a
Jira or Confluence call over HTTP). A rule that only decides allow / ask / deny belongs in
the test classes in `template/.agent/src/sdlc/selftest/`, and so does the logic of a workflow
step: `WorkflowTests` runs `Approvals`, `Docs` and `Tasks` against an issue tracker and a wiki in memory.

## What it does not prove

Real Maven or Gradle builds, Windows, a live agent session in an IDE, and the real services
(Jira and Confluence: [the live run](#the-live-run) does; Jenkins, SonarQube and GitHub: nothing here does).
[docs/02](../../docs/02-setup.md#step-8-the-hook-test) has the hook test to do once per machine.
