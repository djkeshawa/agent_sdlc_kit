# How the kit itself is verified

This page is for people who change the kit. Users of the kit need none of it.

A defect can enter in many ways: a typo, a missing null check, a test tool that writes its
report in another shape than we thought, a script that works only on one machine. No single
check sees all of these. So the checks are stacked in layers, from cheap and fast to slow and
real. Each layer catches what the layers before it cannot.

```bash
sh verify/run.sh              # every layer, with one line per layer at the end
sh verify/run.sh compile lint # only the layers named
sh verify/run.sh --list       # the layers
```

A layer ends as `PASS`, `FAIL` or `NOT RUN` (a tool it needs is missing; the line says what to
install). The exit code is 0 when every layer passed, 1 when one failed, and 2 when none failed
but one could not run.

## The layers

| # | Layer | What it runs | What it catches | What it cannot see | Time |
| --- | --- | --- | --- | --- | --- |
| 1 | `compile` | The Java compiler with every warning turned into an error, on Java 17 and 21 in CI | Code that does not compile on a supported Java; unsafe casts, missing cases, deprecated calls | Any error in logic | 10 s |
| 2 | `lint` | ShellCheck on the shell scripts, actionlint on the CI workflows, ruff on the Python tests | A script that breaks on another shell; a workflow with a wrong key; an undefined name in a test | Whether the scripts do the right thing | 5 s |
| 3 | `bugs` | SpotBugs on the compiled tool | Bug patterns that no test happens to hit: a possible null, a resource left open, a wrong comparison | Bugs that are not a known pattern | 90 s |
| 4 | `selftest` | The tool's own tests (about 1,400), with line coverage per package | Wrong logic inside the tool; new code that no test runs | How real test tools behave: the self tests use a stand-in runner | 90 s |
| 5 | `e2e` | The whole flow from ticket to merge, against mock services | Commands that do not work together; a broken hook or guard | Real Jira, Confluence, Jenkins, GitHub | 3 min |
| 6 | `real` | `sdlc check` against real Maven with JUnit 4 and 5, and against real Vitest | A report shape of a real test tool that the tool reads wrongly | Versions of the test tools other than the pinned ones | 5 to 20 min |
| 7 | `package` | The bundle and the container image, with their smoke test | A bundle that does not start; a missing Java module; no TLS | Operating systems other than the two that are built | 5 min |

Three more layers are not commands. They are steps of the work:

| # | Layer | Who | What it catches |
| --- | --- | --- | --- |
| 8 | **Plan check** | The author, before handing a piece over | Work that drifted from the plan (`docs/proposals/02-mandate-direction.md`): a claim the plan does not make, effort on something the plan says to stop |
| 9 | **Independent testing (QE)** | A second agent or person who did not write the code | What the author did not think of. QE tries to break the piece, runs the layers, and plants faults to see whether a layer notices |
| 10 | **Review on the pull request** | CI on Linux, macOS and Windows; the review bot; the person who merges | Failures on another operating system; findings of the review |

## Rules

- **A piece is done when layers 1 to 7 pass and QE has only minor findings left.** Minor means: no
  wrong result for a user, no honest work blocked, nothing a later change would trip over.
- **A finding is fixed in the layer that should have caught it, too.** If QE finds a bug that a
  test could have caught, the fix comes with that test.
- **No layer is switched off to get a green result.** What SpotBugs is not asked about is listed
  in `verify/spotbugs-exclude.xml`, each entry with its reason. Coverage floors are in
  `verify/coverage-floors.txt`; a floor is lowered only with a reason in that file.
- **A fault is planted when a layer is added or changed.** A layer that has never failed has not
  been shown to work. The faults that were planted, and what noticed them, are listed below.

## Faults that were planted, and what noticed them

Planted on 2026-11-15 in a copy of the repository, one at a time, when the layers were built.

| Fault | Layer that must notice | Result |
| --- | --- | --- |
| A raw type (a compiler warning) | 1 `compile` | FAIL, as it must |
| An unquoted, unset variable in `packaging/build.sh` | 2 `lint` | FAIL (ShellCheck) |
| A misspelled key in a workflow | 2 `lint` | FAIL (actionlint) |
| An undefined name in a Python test | 2 `lint` | FAIL (ruff) |
| A certain null dereference | 3 `bugs` | **PASS at first: the layer analysed classes left by an earlier, failed compile.** Fixed: it now always compiles first and fails when it cannot see every class. Then FAIL, as it must |
| Code that does not compile | 3 `bugs` | FAIL |
| A flipped condition in the git guard | 4 `selftest` | FAIL (1 of 1,397 self tests) |
| A coverage floor above what is measured | 4 `selftest` | FAIL, with the package and both numbers |
| A floor for a package that the report does not hold | 4 `selftest` | FAIL |

Layers 5 to 7 were not given a planted fault when they were built; QE does that (layer 9).

What the layers found in the code when they were first run: one real defect
(`sdlc mindmap --out picture.png` crashed when the output path had no folder), 36 more places
of the same kind (a path's parent or name taken without a check), two exceptions caught too
widely, and 140 lint findings in the Python tests, 136 of them names that one test file gets
from another. Real Maven and real Vitest confirmed the tool's answers in all 27 cases.

## The tools, and why they do not touch the kit

The kit that users install (`template/`) has no dependencies. The tools here only check it.
Each has a fixed version and a SHA-256 in `verify/tools.lock`, and `verify/fetch.sh` refuses a
download that does not match. They are kept in a cache folder outside the repository
(`~/.cache/sdlc-verify`, or `$VERIFY_CACHE`).

| Tool | Version | Used for |
| --- | --- | --- |
| SpotBugs | 4.10.5 | Layer 3 |
| JaCoCo | 0.8.16 | Coverage in layer 4 |
| ShellCheck | 0.11.0 | Layer 2 |
| actionlint | 1.7.12 | Layer 2 |
| ruff | 0.14.10 | Layer 2 (installed with pip; the version is checked in CI) |
| Maven, Surefire 3.5.2, JUnit 5.11.4 and 4.13.2 | from Maven Central | Layer 6 |
| Vitest | 3.2.4, by `tests/e2e/vitest-runtime/package-lock.json` | Layer 6 |

On a machine without Maven, layer 6 runs Maven in a container (the `maven:3.9-eclipse-temurin-21`
image). With Docker Desktop the cache folder must be one that Docker may mount, which a folder
under your home directory is.

## Where each layer runs in CI

| Workflow | Layers | On |
| --- | --- | --- |
| `verify.yml` | 1, 2, 3 on Java 17 and 21; 4 with coverage; 6 | Linux |
| `selftest.yml` | 4 without coverage, 5 | Linux, macOS, Windows (the flow: Linux and macOS) |
| `package.yml` | 7 | Linux and macOS |

## What the layers do not cover

Said plainly, so that nobody takes a green result for more than it is:

- Gradle and other test tools: the tool has no runner for them yet.
- Windows for layers 5 to 7.
- Real Jira, Confluence, Jenkins and GitHub: the end-to-end flow uses mock services.
- Mutation testing by a tool. The self tests are driven by the tool's own small runner, which
  mutation tools cannot drive. QE plants faults by hand instead (layer 9).
- Whether the product is useful. Only people who try it can say that.
