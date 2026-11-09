package sdlc;

/**
 * Built-in configuration. Order of precedence: COMMON, then the stack preset, then
 * .agent/sdlc.yml. A key ending in "+" in sdlc.yml appends to a list instead of replacing it
 * (for example  protected+: [docs/legal/**]).
 */
final class Defaults {
    private Defaults() {}

    static final String COMMON = """
            version: 1
            stack: java-maven
            project:
              name: ""
              team: ""
            jira:
              deployment: cloud
              url: ""
              projectKeys: []
              issueTypes:
                task: Sub-task
              epicLinkField: ""
              statuses:
                inProgress: In Progress
                inReview: In Review
                done: Done
              agentMayClose: false
            confluence:
              url: ""
              searchSpaces: []
              titlePatterns:
                onePager: "(?i)one[ -]?pager"
                hld: "(?i)\\\\bhld\\\\b|high[ -]?level design"
                lld: "(?i)\\\\blld\\\\b|low[ -]?level design"
                testDesign: "(?i)test (scenarios|design|cases)"
              publish:
                testDesign:
                  space: ""
                  parentId: ""
                  title: "{key} - Test scenarios"
                lld:
                  space: ""
                  parentId: ""
                  title: "{key} - LLD - {team}"
              mermaidMacro: ""
              mindmapImage: true
            github:
              host: github.com
              relatedRepos: []
            ci:
              waitMinutes: 60
              pollSeconds: 30
              jenkins:
                url: ""
              sonar:
                url: ""
                projectKey: ""
            gates:
              testDesign: required
              lld: required
              approvers:
                testDesign: []
                lld: []
              botAccounts: []
            loops:
              review:
                min: 2
                max: 5
              qa:
                max: 3
              ci:
                max: 3
            delivery:
              baseBranch: develop
              createSubtasks: true
              qa: auto
            tools: [copilot]
            agents:
              orchestrator: {model: ""}
              analyst: {model: ""}
              qe-designer: {model: ""}
              designer: {model: ""}
              implementer: {model: ""}
              reviewer: {model: ""}
              qa-verifier: {model: ""}
              ci-fixer: {model: ""}
            git:
              protectedBranches: [main, master, develop, "release/*"]
              branchPattern: "^(feature|bugfix|hotfix|chore)/[A-Z][A-Z0-9]+-[0-9]+(-[a-z0-9._-]+)?$"
              commitPattern: "^(feat|fix|test|refactor|docs|chore|perf|build|ci|style|revert)\\\\([A-Z][A-Z0-9]+-[0-9]+\\\\)!?: .{5,}"
            trace:
              dir: docs/test-links
            analyse:
              output: docs/agent/project-profile.md
            mcp:
              allowWrites: false
              serverHints: [mcp, atlassian, jira, confluence, github, bitbucket, sonar, jenkins]
            guard:
              requireTaskForCodeEdits: true
              maxFileKb: 1024
              maxStopBlocks: 3
            paths:
              protected:
                - .agent/**
                - .githooks/**
                - .github/hooks/**
                - .github/agents/**
                - .github/prompts/**
                - .github/skills/**
                - .github/instructions/**
                - .github/copilot-instructions.md
                - .github/CODEOWNERS
                - .github/workflows/**
                - AGENTS.md
                - CLAUDE.md
                - .claude/**
                - .codex/**
                - .agents/**
                - .mcp.json
                - Jenkinsfile
                - .vscode/**
                - sonar-project.properties
                - .gitattributes
                - .sdlc/**
                - .git/config
                - .git/hooks/**
                - .git/info/**
              agentWritable:
                - ".sdlc/*/drafts/**"
                - ".sdlc/*/inbox/**"
              secret:
                - .agent/local.env
                - "**/*.env"
                - "**/.env.*"
                - "**/.netrc"
                - "**/.m2/settings.xml"
                - "**/settings-security.xml"
                - "**/.gradle/gradle.properties"
                - "**/.git-credentials"
                - "**/.npmrc"
                - "**/id_rsa*"
                - "**/id_ed25519*"
                - "**/.ssh/**"
                - "**/*.jks"
                - "**/*.p12"
                - "**/*.pfx"
                - "**/*.keystore"
                - "**/credentials.xml"
                - "**/secrets/**"
              doc: [docs/**, README.md, CHANGELOG.md]
            build:
              preCommit: ""
              preCommitWindows: ""
            tests:
              requireTestsForMainChanges: true
            """;

    static final String JAVA_MAVEN = """
            build:
              verify: ./mvnw -B -ntp verify
              verifyWindows: mvnw.cmd -B -ntp verify
              testOne: "./mvnw -B -ntp test -Dtest=<Class>#<method> -Dsurefire.failIfNoSpecifiedTests=false"
              tools: [mvn, mvnw, ./mvnw, mvnw.cmd]
              forbiddenArgs: ["-DskipTests.*", "-Dmaven\\\\.test\\\\.skip.*", "-DskipITs.*", "-Dsonar\\\\.skip.*", "-Djacoco\\\\.skip.*", "-Dspotless(\\\\.check)?\\\\.skip.*", "-Denforcer\\\\.skip.*", "-Dcheckstyle\\\\.skip.*", "-Dspotbugs\\\\.skip.*", "-Dpmd\\\\.skip.*", "-Dmaven\\\\.test\\\\.failure\\\\.ignore.*", "-Dtest\\\\.failure\\\\.ignore.*", "-Darchunit\\\\.skip.*", "-fae", "--fail-at-end", "-fn", "--fail-never"]
              askArgs: []
              forbiddenGoals: [deploy, "release:.*", "sonar:sonar", "versions:set", "versions:commit"]
            paths:
              code: [src/**, "**/src/**"]
              main: ["**/src/main/**"]
              test: ["**/src/test/**", "**/src/it/**", "**/src/integrationTest/**"]
              ask: [pom.xml, "**/pom.xml", .mvn/**, mvnw, mvnw.cmd, "**/src/main/resources/db/migration/**", "**/checkstyle*.xml", "**/spotbugs*.xml", "**/lombok.config"]
            tests:
              codeExtensions: [java, kt, groovy, scala]
              disabledPatterns: ["@([\\\\w$]+\\\\.)*(Disabled|DisabledIf\\\\w*|Ignore)\\\\b", "assume(True|That)\\\\s*\\\\(\\\\s*false\\\\s*\\\\)", "assumeFalse\\\\s*\\\\(\\\\s*true\\\\s*\\\\)"]
            """;

    static final String JAVA_GRADLE = """
            build:
              verify: ./gradlew build
              verifyWindows: gradlew.bat build
              testOne: "./gradlew test --tests '<Class>.<method>'"
              tools: [gradle, gradlew, ./gradlew, gradlew.bat]
              forbiddenArgs: ["-x", "--exclude-task", "-DskipTests.*", "-Dsonar\\\\.skip.*", "--continue"]
              askArgs: []
              forbiddenGoals: [publish, "publish.*", sonar, sonarqube, release]
            paths:
              code: [src/**, "**/src/**"]
              main: ["**/src/main/**"]
              test: ["**/src/test/**", "**/src/integrationTest/**", "**/src/testFixtures/**"]
              ask: [build.gradle, "**/build.gradle", build.gradle.kts, "**/build.gradle.kts", "settings.gradle*", gradle/**, gradlew, gradlew.bat, "**/src/main/resources/db/migration/**"]
            tests:
              codeExtensions: [java, kt, groovy, scala]
              disabledPatterns: ["@([\\\\w$]+\\\\.)*(Disabled|DisabledIf\\\\w*|Ignore)\\\\b", "assume(True|That)\\\\s*\\\\(\\\\s*false\\\\s*\\\\)", "assumeFalse\\\\s*\\\\(\\\\s*true\\\\s*\\\\)"]
            """;

    static final String NODE = """
            build:
              verify: npm run lint --if-present && npm test && npm run build --if-present
              verifyWindows: npm run lint --if-present && npm test && npm run build --if-present
              testOne: "npm test -- <path/to/file.test.ts> -t '<test name>'"
              tools: [npm, npx, yarn, pnpm, jest, vitest, mocha, ng, nx]
              forbiddenArgs: ["--passWithNoTests", "--no-verify", "--ignore-scripts", "--forceExit", "--bail=0"]
              askArgs: ["-u", "--updateSnapshot", "--update", "install", "i", "add", "ci", "dlx", "exec"]
              forbiddenGoals: [publish, "version", "release"]
            paths:
              code: [src/**, lib/**, app/**, apps/**, packages/**, libs/**, components/**, pages/**, server/**, client/**, test/**, tests/**, e2e/**, "**/__tests__/**"]
              main: [src/**, lib/**, app/**, apps/**, packages/**, libs/**, components/**, pages/**, server/**, client/**]
              test: ["**/*.{test,spec}.{js,jsx,ts,tsx,mjs,cjs}", "**/__tests__/**", test/**, tests/**, e2e/**, cypress/**, playwright/**]
              ask: [package.json, "**/package.json", package-lock.json, yarn.lock, pnpm-lock.yaml, "tsconfig*.json", "**/tsconfig*.json", "jest.config.*", "vitest.config.*", "vite.config.*", "playwright.config.*", "cypress.config.*", ".eslintrc*", "eslint.config.*", ".prettierrc*", "babel.config.*", "webpack.config.*", .nvmrc]
            tests:
              codeExtensions: [js, jsx, ts, tsx, mjs, cjs, vue, svelte]
              disabledPatterns: ["\\\\b(it|test|describe|context|suite)\\\\.(skip|only|todo)\\\\s*\\\\(", "\\\\bx(it|test|describe|context)\\\\s*\\\\(", "\\\\bf(it|describe)\\\\s*\\\\(", "@ts-nocheck", "eslint-disable(?!-next-line)"]
            """;

    static String preset(String stack) {
        return switch (stack) {
            case "java-maven", "maven", "java" -> JAVA_MAVEN;
            case "java-gradle", "gradle" -> JAVA_GRADLE;
            case "node", "javascript", "typescript", "js", "ts" -> NODE;
            default -> throw new IllegalArgumentException("unknown stack '" + stack + "' in .agent/sdlc.yml (use java-maven, java-gradle or node)");
        };
    }
}
