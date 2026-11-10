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
| `run.py` | starts and stops everything |

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

## When to run it

After any change in `template/.agent/src`. The flow runs the self test too (in `setup` and at
the end), so this one command covers both. CI runs it on every push and pull request
(`.github/workflows/selftest.yml`).

Add a check to `flow.py` when you add a rule that needs the whole flow to prove (a gate, a
Jira or Confluence call over HTTP). A rule that only decides allow / ask / deny belongs in
the test classes in `template/.agent/src/sdlc/selftest/`, and so does the logic of a workflow
step: `WorkflowTests` runs `Approvals`, `Docs` and `Tasks` against an issue tracker and a wiki in memory.

## What it does not prove

Real Maven or Gradle builds, Windows, a live agent session in an IDE, and the real services.
[docs/02](../../docs/02-setup.md#step-8-the-hook-test) has the hook test to do once per machine.
