# Quality setup for a Node / TypeScript repository

The equivalent of `pom-quality-plugins.xml` for `stack: node`. Merge the parts you are missing
into your project, one at a time, running `java .agent/Sdlc.java verify` after each.
Step-by-step context: docs/05-ci-jenkins-sonar.md. Versions were current in November 2026;
newer minor versions are fine. These files were tested together on a TypeScript + Vitest project.

## 1. `package.json` scripts

`sdlc verify` runs `npm run lint --if-present && npm test && npm run build --if-present`
(`build.verify` in `sdlc.yml` changes it, e.g. for pnpm or yarn).

```json
{
  "scripts": {
    "lint": "tsc --noEmit && eslint .",
    "test": "vitest run --coverage",
    "build": "tsc -p tsconfig.build.json"
  }
}
```

Jest instead of Vitest: `"test": "jest --coverage --ci"`, plus the `jest-junit` reporter
(`"reporters": ["default", "jest-junit"]`, `JEST_JUNIT_OUTPUT_DIR=reports`).

## 2. `vitest.config.ts`: JUnit for Jenkins, lcov for SonarQube

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    reporters: ["default", "junit"],
    outputFile: { junit: "reports/junit.xml" },
    coverage: {
      provider: "v8",                       // npm i -D @vitest/coverage-v8
      reporter: ["text-summary", "lcov"],   // coverage/lcov.info for SonarQube
      include: ["src/**"],
      exclude: ["src/**/*.test.ts", "src/**/*.spec.ts"],
    },
  },
});
```

Add `reports/` and `coverage/` to `.gitignore`.

## 3. `eslint.config.mjs`: the rules behind the `js-coding-standards` skill

```js
// npm i -D eslint typescript-eslint
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist/**", "coverage/**", "reports/**"] },
  ...tseslint.configs.strictTypeChecked,
  {
    languageOptions: { parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname } },
    rules: {
      "@typescript-eslint/no-floating-promises": "error",                 // JS-13
      "@typescript-eslint/no-explicit-any": "error",                      // JS-01
      "@typescript-eslint/ban-ts-comment": ["error", { "ts-expect-error": "allow-with-description" }],
      "no-console": "error",                                              // JS-16
      eqeqeq: "error",                                                    // JS-11
      "no-var": "error",
      "prefer-const": "error",
    },
  },
);
```

On a legacy codebase, start with `recommendedTypeChecked` instead of `strictTypeChecked` and
tighten later; never switch rules off to make an agent's change pass.

## 4. `tsconfig.json`

`"strict": true` (JS-01). A separate `tsconfig.build.json` that excludes tests keeps them out of
`dist/`.

## 5. SonarQube: `sonar-project.properties`

```properties
sonar.projectKey=com.yourcompany:payments-web
sonar.sources=src
sonar.tests=src
sonar.test.inclusions=src/**/*.test.ts,src/**/*.spec.ts
sonar.exclusions=dist/**,coverage/**,reports/**
sonar.javascript.lcov.reportPaths=coverage/lcov.info
```

Run the scanner after the tests (`sonar-scanner` CLI or the Jenkins SonarQube step), so the
lcov report exists. Without it, coverage on new code is 0% and every PR fails the gate.

## 6. Jenkins stages

```groovy
stage('Build and test') {
    steps {
        sh 'npm ci'
        sh 'npm run lint && npm test && npm run build'
    }
    post { always { junit allowEmptyResults: true, testResults: 'reports/junit.xml' } }
}
```

The JUnit report lets `java .agent/Sdlc.java ci wait` list failed tests by name.

## 7. Optional: architecture rules (the ArchUnit equivalent)

`dependency-cruiser` checks import rules such as "routes never import repositories directly":
`npx depcruise --init`, add a rule per layer boundary, and run `depcruise src` in `lint`.
