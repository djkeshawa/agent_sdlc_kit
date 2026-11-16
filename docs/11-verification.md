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
install).

| Exit code | Meaning |
| --- | --- |
| 0 | Every layer asked for passed |
| 1 | A layer failed |
| 2 | No layer failed, but one could not run. `VERIFY_ALLOW_NOT_RUN=1` turns this into 0 |
| 3 | A wrong layer name, or another run of the same checkout is going on |

Each checkout has its own work folder in the cache, and one run at a time: two runs that shared
compiled classes could show each other's result. The tools are unpacked afresh from their
checked archives on every run, so an unpacked copy that was changed is never used.

## The layers

| # | Layer | What it runs | What it catches | What it cannot see | Time |
| --- | --- | --- | --- | --- | --- |
| 1 | `compile` | The Java compiler with every warning turned into an error, on Java 17 and 21 in CI | Code that does not compile on a supported Java; unchecked casts, raw types, a fall-through in a switch, calls that are deprecated for removal | Any error in logic. A switch that lacks a case (the compiler has no warning for it). Anything under `@SuppressWarnings` (nine places today) | 10 s |
| 2 | `lint` | ShellCheck on every shell script (by its name or its first line), actionlint on the CI workflows, ruff on every Python file; and a check that every action in a YAML file under `.github` is pinned by a full commit id | A script that breaks on another shell; a workflow with a wrong key; an undefined name in a test; an action that can change under us | Whether the scripts do the right thing. The inputs of an action (`with:`) and whether the pinned commit exists: actionlint knows an action only by its tag, so a misspelled input of a pinned action is seen only when CI runs | 5 s |
| 3 | `bugs` | SpotBugs on the compiled tool, and a second pass for public mutable statics | Bug patterns that no test happens to hit: a possible null, a resource left open, a wrong comparison | Bugs that are not a known pattern. On Java 18 or newer it does not report a place that relies on the default text encoding; the Java 17 job in CI does. A mutable static that is not public | 90 s |
| 4 | `selftest` | The tool's own tests (about 1,400): their result line, their count against `verify/selftest-count.txt`, and line coverage per package against `verify/coverage-floors.txt` | Wrong logic inside the tool; a run that ends early; self tests that are lost; a package whose tests fall behind its code | How real test tools behave: the self tests use a stand-in runner. Untested new code that fits under a floor: the floors are per package and absolute (see below) | 90 s |
| 5 | `e2e` | The whole flow from ticket to merge, against mock services | Commands that do not work together; a broken hook or guard | Real Jira, Confluence, Jenkins, GitHub | 3 min |
| 6 | `real` | `sdlc check` against real Maven with JUnit 4 and 5 (21 cases), and against real Vitest (13 cases). Each case names the lines the check must print, and fails when the test tool did not run | A report shape of a real test tool that the tool reads wrongly | Versions of the test tools other than the pinned ones; a hanging test; a test in an abstract base class (the tool cannot judge it yet, and the case holds it to saying so); a custom report folder; `@RepeatedTest` and `@TestFactory` | 10 to 25 min |
| 7 | `package` | The bundle and the container image, with their smoke test | A bundle that does not start; a missing Java module (the bundle runs its own self tests) | Operating systems other than the two that are built. TLS is required only in CI (`SMOKE_REQUIRE_TLS=1`): on a machine without a network that check is skipped. Without a full JDK the bundle is built in a container, which needs a checkout in a folder Docker may mount; else the layer is `NOT RUN` | 5 min |

One more layer runs only when it is named, because it needs accounts and writes to a real site:

| Layer | What it runs | What it catches | What it cannot see | Time |
| --- | --- | --- | --- | --- |
| `live` | `sh verify/run.sh live`: the first half of the flow (doctor, `docs pull`, publishing the test design and the LLD, the approval gates) against a real Jira and Confluence test project. [tests/e2e/README.md](../tests/e2e/README.md#the-live-run) has the settings | A request the real service refuses or reads in another way than the mock: a field name, the page tree, the search, an account id, an issue type | Jenkins, SonarQube and GitHub. The approvals by people and the sub-tasks, unless a person runs it with the lead accounts. It is never run in CI | 2 min |

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
  in `verify/spotbugs-exclude.xml`, each entry with its reason and, for mutable static fields,
  by the field's name. The floors are in `verify/coverage-floors.txt` and
  `verify/selftest-count.txt`; a floor is lowered only with a reason in that file.
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

QE then planted about 46 more faults (2026-11-16). What they showed, and what was done:

| Fault that was NOT noticed | What it showed | Done |
| --- | --- | --- |
| Two runs at once on one cache: a clean result for faulty code | The work folder was shared | A work folder per checkout, and a lock |
| A real-Maven case passed although Maven did not run | The cases looked for single words | Each case names whole lines, and fails when the tool says Maven did not run |
| The self tests end early with exit 0 and a failing test | Only the exit code was read | The result line is required, with 0 failed |
| A test class that is no longer called | Nothing counted the self tests | The count floor |
| An undefined `json` or `state` in a Python test; Python outside `tests/e2e` | The lint knew 16 names everywhere, and read one folder | The names are declared in the two files that need them; every Python file is linted |
| A misspelled input of a pinned action | actionlint cannot check it | Stated above as a limit; every action must now be pinned |
| A changed, unpacked ShellCheck | Unpacked tools were reused | Tools are unpacked afresh on every run |
| A mutable static collection; a lock on the class | The exclusions were for the whole tool | Excluded by field name only; the lock cache was fixed. Round 2: SpotBugs rates most of these low, so a second pass asks for them at every rank. A mutable static that is not public, or that is in a class that is not public, is still not seen |
| A missing switch case | The compiler has no such warning | The guide no longer claims it |
| A mandate reached through a linked folder was accepted | The kit had no self test for that check | The self test was added |
| 120 untested lines | The floors are coarse | Stated above |

QE round 2 (2026-11-16) planted the faults above again; all were noticed. It found four more gaps:

| Fault that was NOT noticed | Done |
| --- | --- |
| A new `public static final List` (SpotBugs rates it low) | The second SpotBugs pass |
| An unpinned action in a `.yaml` workflow, or pinned only in a comment | Every YAML file under `.github` is read, without comments and quotes |
| A floors file with a line that cannot be read, a package twice, or no floor: no floor was applied | `verify/coverage.py` ends with exit 2 |
| `git clean -X` and six more refusals of the guard that no self test named (round 3) | Self tests added |
| Three faults in the guard and the mandate that no self test saw (an option of git that takes a value, the size limit of a mandate, a mandate that is not in force) | Self tests added; each fault now fails one |

QE's faults that were noticed: 6 kinds of bug by SpotBugs, 4 by the compiler, 3 by ShellCheck,
logic faults in `check` and `guard` by the self tests, a broken hook and a wrong Jira path by the
end-to-end flow, a Vitest reporter typo by real Vitest, a missing Java module by the bundle's
own self tests, and a changed download by the checksum.

What the layers found in the code when they were first run: one real defect
(`sdlc mindmap --out picture.png` crashed when the output path had no folder), 36 more places
of the same kind (a path's parent or name taken without a check), two exceptions caught too
widely, and 140 lint findings in the Python tests, 136 of them names that one test file gets
from another. Real Maven and real Vitest confirmed the tool's answers in all 33 cases (20 with Maven, 13 with Vitest).

When the `live` layer was added (2026-11-17), one fault was planted: a page published with a
wrong field name for its parent page. **It was not noticed at first.** The parent page of the test
bed was the top page of the space, and a page without a parent lands there too. Now each run makes
its own parent page and asks for the direct parent; the fault then fails two checks, as it must.

## What the floors do and do not catch

The self-test layer has two floors, and both are coarse on purpose:

- **The count of self tests** (`verify/selftest-count.txt`). It catches a test class that is no
  longer called, or a block of tests that returns early. It does not catch a few tests that
  are taken out while as many are added.
- **Line coverage per package** (`verify/coverage-floors.txt`), for four packages: `check`,
  `guard`, `mandate` and `util`. A floor is a few points under what was measured, so untested
  code fits under it: about 125 lines in `check`, 40 in `guard`, 15 in `mandate`, 60 in `util`.
  The other packages (`cli`, `core`, `integrations`, `workflow`, about 4,300 lines) have no
  floor: the end-to-end flow runs them, and this number does not see that flow.

So the floors catch a large hole, not a small one. A small piece of untested code is for the
review of the pull request and for QE to see.

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
| ruff | 0.14.10 | Layer 2. Installed with pip: CI installs exactly this version, on your machine the layer uses the ruff it finds |
| Maven, Surefire 3.5.2, JUnit 5.11.4 and 4.13.2 | from Maven Central | Layer 6 |
| Vitest | 3.2.4, by `tests/e2e/vitest-runtime/package-lock.json` | Layer 6 |

ShellCheck and actionlint are pinned for Linux x86_64 and macOS arm64. On another platform the
layer uses the ones on the PATH, or says `NOT RUN`.

On a machine without Maven, layer 6 runs Maven in a container, and without a full JDK layer 7
builds in one. Both images are pinned by digest in `verify/run.sh`. Maven's repository is kept
in the cache folder, and Maven runs offline once it is filled. With Docker Desktop the cache
folder and the checkout must be folders that Docker may mount, which folders under your home
directory are. When Maven Central refuses the container (HTTP 429, too many requests), set
`VERIFY_MAVEN_MIRROR` to the https address of a mirror of Maven Central, for example
`https://maven-central.storage-download.googleapis.com/maven2/`. The plugins that the test projects of layer 6 use are pinned in
`tests/e2e/maven.py`.

## Where each layer runs in CI

| Workflow | Layers | On |
| --- | --- | --- |
| `verify.yml` | 1, 2, 3 on Java 17 and 21; 4 with the count and coverage floors; 6 | Linux |
| `selftest.yml` | 4 without coverage, 5 | Linux, macOS, Windows (the flow: Linux and macOS) |
| `package.yml` | 7 | Linux and macOS |

## What the layers do not cover

Said plainly, so that nobody takes a green result for more than it is:

- Gradle and other test tools: the tool has no runner for them yet.
- Windows for layers 5 to 7.
- Real Jira, Confluence, Jenkins and GitHub: the end-to-end flow uses mock services.
- Mutation testing by a tool. The self tests are driven by the tool's own small runner, which
  mutation tools cannot drive. QE plants faults by hand instead (layer 9).
- Coverage of the lines a change adds. The floors are per package (see above).
- The inputs of pinned actions in the workflows (see layer 2).
- Whether the product is useful. Only people who try it can say that.
