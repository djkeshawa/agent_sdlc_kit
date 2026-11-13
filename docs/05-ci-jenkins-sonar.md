# 5. CI: Jenkins, SonarQube and GitHub

The kit does **not** replace or change your CI. It reads it: the PR's checks from GitHub, the
failed build from Jenkins, and the quality gate from SonarQube, and turns them into a digest the
ci-fixer agent works from. This page lists what your CI must provide, then the parts that make
`./mvnw verify` and the gate strong enough to trust.

## 5.1 What the kit reads

| Source | Call | Used for |
| --- | --- | --- |
| GitHub | `gh pr view`, `gh pr checks <pr> --json name,state,bucket,link,description` | which checks ran on the PR, pending / pass / fail |
| Jenkins | `<build>/testReport/api/json`, `<build>/consoleText` (with `JENKINS_USER` / `JENKINS_TOKEN`) | failed tests with their error, the first failing stage, the error lines of the log |
| SonarQube | `api/qualitygates/project_status`, `api/issues/search`, `api/measures/component_tree` with `pullRequest=<n>` (`SONAR_TOKEN`; none for public SonarQube Cloud projects) | failed gate conditions, new issues with file and line, files with uncovered new lines |

A check is treated as Jenkins when its link starts with `ci.jenkins.url` or contains `/job/`. The Jenkins token is sent only when the link has the same scheme, host and port as `ci.jenkins.url` (anyone who can report a status on the PR sets these links); for other links the digest shows the link without reading the log.
Status links like `.../job/payments/job/PR-12/5/display/redirect` are understood.

## 5.2 What Jenkins must do

1. **Build every pull request into your base branch** and report the result to GitHub as a
   commit status or check, linking to the build. The usual way: a **Multibranch Pipeline** job
   with the **GitHub Branch Source** plugin and a GitHub App credential. It reports the context
   `continuous-integration/jenkins/pr-merge`.
2. Run the same build developers run (`./mvnw -B -ntp verify`) and publish JUnit results
   (`junit 'target/surefire-reports/*.xml'`), so the kit can list failed tests by name.
3. Run the SonarQube analysis **as a PR analysis** and fail the build when the gate fails.
4. Optional but recommended: run the kit's PR policy, so the branch, commit, secret and
   disabled-test rules hold even for someone who skipped the git hooks.

A minimal `Jenkinsfile` with these stages (adapt it to yours; the kit does not ship one):

```groovy
pipeline {
    agent any
    options { timeout(time: 30, unit: 'MINUTES') }
    stages {
        stage('Policy') {
            when { changeRequest() }
            steps {
                sh 'git fetch --no-tags origin "+refs/heads/$CHANGE_TARGET:refs/remotes/origin/$CHANGE_TARGET"'
                sh 'java .agent/Sdlc.java check-pr --title "$CHANGE_TITLE"'
            }
        }
        stage('Build and test') {
            steps { sh './mvnw -B -ntp verify' }
            post { always { junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml,target/failsafe-reports/*.xml' } }
        }
        stage('SonarQube') {
            steps {
                withSonarQubeEnv('SonarQube') {      // the server name in Manage Jenkins -> System
                    sh './mvnw -B -ntp sonar:sonar'  // PR parameters come from the branch source
                }
                timeout(time: 10, unit: 'MINUTES') { waitForQualityGate abortPipeline: true }
            }
        }
    }
}
```

Jenkins checks the PR out on a detached HEAD; the tool then takes the branch name from
`CHANGE_BRANCH` (set by Multibranch for PRs; `GITHUB_HEAD_REF` on GitHub Actions).

**What `check-pr` trusts in a pull request build.** A pull request can change every file in its
own checkout, so in CI the tool does not take its rules from there:

| Input | Where it comes from |
| --- | --- |
| The base branch | The CI event: `CHANGE_TARGET` (Jenkins), `GITHUB_BASE_REF` (GitHub Actions), or `SDLC_BASE_BRANCH` if you set it. Not `delivery.baseBranch` of the checkout |
| The rules (`.agent/sdlc.yml`, `.agent/org.yml`) | The tip of that base branch, read from git. A change to the rules takes effect after it is merged |
| Acceptance test locks | The base branch: a lock cannot be removed or re-hashed, and a test the base branch locked after your branch was made cannot be changed |
| The story | `check-pr --story <KEY>` when you pass it; else the key in the branch name, with a warning, because the author chooses that name |
| Local state (`.sdlc/`) | Not read. A pull request that commits files under `.sdlc/` fails |
| The diff | Read as text whatever `.gitattributes` says, with file names exactly as git holds them. A checkout with no commit in common with the base branch (a shallow clone) fails; it does not pass on an empty diff |

On a developer's machine none of these variables is set and the checkout's own rules apply, as
before. If the base branch has no `.agent/sdlc.yml` yet (the pull request that installs the
kit), the tool says so and uses the pull request's copy.

Fetch enough history for the check: it needs the commit where the branch left the base branch.

Still decided by the pull request itself, and therefore only a warning for a reviewer:

- the contract allowance (`docs/agent/contracts/<STORY>.json`) that says which API operations
  the story may add or remove;
- a pull request that holds only lock files and their tests may remove a lock. QE owns those
  files through CODEOWNERS.

This is half of the boundary. Two more things are the pull request's own and cannot be fixed
inside the tool: the pipeline definition (this `Jenkinsfile`) and the tool's code (`.agent/`).
A pull request that edits either is judged by its own edit, so CODEOWNERS review of `.agent/`
and the `Jenkinsfile` stays necessary until the check runs from a pinned image in a pipeline
defined outside the repository ([proposal 02](proposals/02-mandate-direction.md), section 6.3).

`waitForQualityGate` needs a SonarQube webhook to `https://<jenkins>/sonarqube-webhook/` (with
the slash). For **SonarQube Cloud** with the scanner CLI, `-Dsonar.qualitygate.wait=true`
replaces the webhook, and the PR parameters are `sonar.pullrequest.key`, `.branch` and `.base`.

**A Jenkins that GitHub cannot reach** (on a laptop, behind a firewall): something else must
start the builds and report the status. The kit's real-services test did it with a small host
script: send the commit snapshot to Jenkins, trigger a parameterised job through its API, wait,
then `gh api repos/<owner>/<repo>/statuses/<sha> -f state=success|failure -f
context=continuous-integration/jenkins/pr-merge -f target_url=<build>/display/redirect`.

Developers need a Jenkins **API token** (read access to the job) in `.agent/local.env` so the
kit can read failed builds; without it the digest says "open the build" and links it.

## 5.3 SonarQube

- **Edition.** PR analysis needs SonarQube **Developer Edition or higher, or SonarQube Cloud**.
  Community Build analyses one branch only; then the gate judges `develop` after the merge, which
  is weaker, and the CI loop sees only Jenkins.
- **New code.** Project Settings -> New Code -> **Reference branch: develop**, so "new code"
  means what the PR changes.
- **Quality gate** on new code: coverage < 80% fails, duplicated lines > 3% fails,
  maintainability / reliability / security rating worse than A fails, security hotspots reviewed
  < 100% fails ("Sonar way" is close). Do not turn rules off to make agents pass; the
  `sonar-fixes` skill tells them how to fix the common ones.
- **Coverage** comes from JaCoCo's `target/site/jacoco/jacoco.xml` (5.4), or for Node from
  `coverage/lcov.info` (`sonar.javascript.lcov.reportPaths`, 5.5). Without it, coverage
  on new code is 0% and every PR fails the gate.
- **SonarQube Cloud:** turn **automatic analysis off** for a project analysed by CI. If the
  SonarQube Cloud GitHub app is installed for all repositories, it may import a new repository
  into a second project with automatic analysis; delete or disable that one.
- Developers: install **SonarQube for IDE** (recommended by `.vscode/extensions.json`), so
  issues show up before the PR.

## 5.4 Maven quality plugins

`./mvnw verify` decides "done" on your machine (`sdlc verify`, the Stop hook, the pre-push hook)
and in Jenkins. Merge `snippets/pom-quality-plugins.xml` into your `pom.xml`, **one plugin at a
time**, running `./mvnw -B -ntp verify` after each (fix a red build first: agents cannot be held
to a gate that is already broken):

1. **Enforcer**: right Java and Maven versions.
2. **Surefire + Failsafe**: unit tests `*Test`, integration tests `*IT`.
3. **JaCoCo**: the coverage report for SonarQube. Keep `@{argLine}` in front of any `argLine`.
4. **Spotless** with `ratchetFrom origin/develop`: formatting of changed files only, no
   big-bang reformat. Put `./mvnw -B -ntp -q spotless:check` in `build.preCommit`.
5. **SpotBugs** at threshold High: real bugs only (exclude existing ones on legacy code).
6. **ArchUnit**: copy `snippets/ArchitectureRulesTest.java` into your test sources and adapt
   the packages.

Multi-module: put them in the parent `pom.xml`. A company parent POM that already manages a
plugin wins; add only what is missing. Coverage is enforced by the SonarQube gate on new code
(recommended) or, for new projects, by the commented `jacoco:check` execution in the snippet.

## 5.5 Node / TypeScript quality setup

`snippets/node-quality.md` is the Node counterpart of the Maven plugins, tested together on a
TypeScript project: `lint` / `test` / `build` scripts for `sdlc verify`, a Vitest config that
writes `reports/junit.xml` (so Jenkins and the CI digest list failed tests by name) and
`coverage/lcov.info` (for SonarQube), an ESLint flat config whose rules back the
`js-coding-standards` skill (no `any`, no floating promises, `===`, no `console`), the
`sonar-project.properties` lines, and the Jenkins stage. Agents follow the `js-coding-standards`
and `js-testing` skills (rule ids `JS-..`, `JST-..`).

## 5.6 GitHub: branch protection and CODEOWNERS

This is the layer nobody can switch off on a laptop. Needs repository admin.

1. A team for guardrail reviews (e.g. `payments-leads`) with Write access; put it in
   `.github/CODEOWNERS` instead of `@your-org/tech-leads`.
2. Settings -> Rules -> Rulesets -> **New branch ruleset** (older GHES: Branches -> Add rule),
   active, empty bypass list, targets `develop` and `main` (and `release/*`):
   - Restrict deletions; block force pushes.
   - Require a pull request: 1 approval (2 for critical services), dismiss stale approvals,
     **require review from Code Owners**, require approval of the most recent push, require
     conversation resolution.
   - Require status checks: `continuous-integration/jenkins/pr-merge` (it appears after the
     first PR build), branches up to date.
3. Settings -> General -> Pull Requests: your merge method (squash keeps the PR title, which
   names the Jira key, as the commit subject); automatically delete head branches.

Check: a direct push to `develop` is refused (locally by the pre-push hook, on GitHub even with
`--no-verify`); a PR that changes `.agent/sdlc.yml` requests the tech leads; a red build
disables the merge button.

GitHub Enterprise Server: everything exists on 3.10+ (rulesets) or as classic branch
protection; `gh auth login --hostname github.yourcompany.com`; set `github.host` in `sdlc.yml`.
