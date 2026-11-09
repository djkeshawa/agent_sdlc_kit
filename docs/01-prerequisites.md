# 1. Prerequisites

Install these on **your developer machine**. The CI side (Jenkins, SonarQube, GitHub) is in
[docs/05](05-ci-jenkins-sonar.md). If your company installs software through a portal
(Software Center, Company Portal, Self Service), use it - the names are the same.

## Checklist

| Tool | Why | Check it works |
| --- | --- | --- |
| JDK 17 or newer (a **JDK**, not a JRE) | builds your project and runs the kit's tool (`java .agent/Sdlc.java`) | `java -version` shows 17+ |
| Git 2.30+ (Windows: **Git for Windows**, which includes Git Bash) | version control, git hooks | `git --version` |
| VS Code (recommended) or IntelliJ IDEA | where the Copilot agents run | opens |
| A coding agent: GitHub Copilot (Business/Enterprise seat), Claude Code 2.x, or Codex CLI (a version with hooks; tested with 0.159) | runs the agents | Copilot Chat opens / `claude --version` / `codex --version` |
| GitHub CLI `gh` 2.40+ | the agent opens the PR; `ci wait` reads the PR's checks | `gh auth status` |
| Docker Desktop **or** `uv` - only for Jira/Confluence **Data Center** or GitHub Enterprise | runs the read-only MCP servers | `docker --version` or `uv --version` |

## Step by step

### 1. JDK 17+

- **Windows:** download "Eclipse Temurin 21 - Windows x64 MSI" from https://adoptium.net, run
  it, and on "Custom Setup" set **Set JAVA_HOME variable** and **Add to PATH** to "Will be
  installed on local hard drive".
- **macOS:** `brew install --cask temurin@21`.
- **Ubuntu/Debian:** `sudo apt install openjdk-21-jdk`.

Open a **new** terminal and check `java -version`. The kit compiles its own sources with the
JDK's built-in compiler, so a JRE is not enough ("This Java has no compiler" means you have a JRE).

> Some Linux JDK packages lack `lib/ct.sym`, which makes Maven fail with
> `release version 17 not supported`. Install the full `openjdk-21-jdk` or Temurin.

### 2. Git

- **Windows:** https://git-scm.com/download/win; keep the defaults, and on "Adjusting your PATH
  environment" choose **Git from the command line and also from 3rd-party software**. This
  installs Git Bash, which the git hooks use.
- **macOS:** `xcode-select --install` or `brew install git`. **Linux:** `sudo apt install git`.

```bash
git config --global user.name "Your Name"
git config --global user.email "you@yourcompany.com"
```

### 3. VS Code and Copilot

1. Install VS Code (keep it up to date: hooks, custom agents and skills need a 2026 release).
2. Extensions (Ctrl+Shift+X): **GitHub Copilot** and **GitHub Copilot Chat**. The repository
   recommends **Extension Pack for Java** and **SonarQube for IDE**; accept the prompt.
3. Account icon (bottom left) -> **Sign in with GitHub to use GitHub Copilot**, with the account
   that has your company's seat (GitHub Enterprise Server: choose "GitHub Enterprise").

**IntelliJ IDEA:** the GitHub Copilot plugin reads the same instructions, agents, prompts,
skills and MCP configuration, but hook support has lagged behind VS Code. Do the hook test
([docs/02 step 8](02-setup.md#step-8-the-hook-test)) in IntelliJ; if nothing is refused, run
agent sessions in VS Code or the Copilot CLI.

### 4. GitHub CLI

- Windows `winget install --id GitHub.cli`, macOS `brew install gh`, Linux: https://github.com/cli/cli#installation.
- `gh auth login` -> GitHub.com (or your Enterprise host) -> HTTPS -> login with a web browser.
- `gh auth status` must show you logged in. Version 2.40+ is needed (`gh pr checks --json`).

### 5. Only for Data Center or GitHub Enterprise: Docker or uv

The Atlassian Cloud and github.com MCP servers are hosted (nothing to install). For Jira and
Confluence **Data Center**, GitHub **Enterprise Server** and **SonarQube**, the IDE starts small
read-only MCP servers in Docker. Docker Desktop (check your company's license rules) or `uv`.

Not sure which Jira you have? `*.atlassian.net` is **Cloud**; anything else is **Data Center**.

### 6. Node projects (`stack: node`)

Node.js (the version your project uses; an LTS) with npm, pnpm or yarn on the PATH. The kit's
tool itself still needs the JDK from step 1; nothing else of Java is needed. The verify command
is `npm run lint --if-present && npm test && npm run build --if-present`; set `build.verify` for
pnpm or yarn ([docs/03](03-configuration.md#build-and-tests)).

### 7. The build wrapper in your project (Java)

The kit runs `./mvnw -B -ntp verify` (Gradle: `./gradlew build`; Node: `npm test` and friends) so
everyone, every agent and Jenkins use the same build. No wrapper yet? Create it once and commit:

```bash
mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.11
git add mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.properties
```

If your team cannot use the wrapper, set `build.verify` in `.agent/sdlc.yml` ([docs/03](03-configuration.md)).

## What you need from other people

Ask early; these take time in most companies.

| From | What | Used in |
| --- | --- | --- |
| Jira admin | the project key, the workflow status names, the sub-task type, (recommended) a bot user | docs/02 steps 3-4 |
| Confluence admin | write access to a space for test designs and LLDs, and a parent page in it | docs/02 step 5 |
| QE lead and tech lead | their Jira account ids for `gates.approvers` | docs/02 step 3 |
| Jenkins admin | a read-only API token for your user, PR builds reporting status to GitHub | docs/05 |
| SonarQube admin | the project key, a quality gate, PR analysis, a user token (not needed for public SonarQube Cloud projects) | docs/05 |
| GitHub org admin | branch protection / rulesets, a tech-leads team for CODEOWNERS | docs/05 |
| Copilot admin | agent mode, MCP servers and (if still preview) hooks allowed in the Copilot policy | docs/02 step 8 |
