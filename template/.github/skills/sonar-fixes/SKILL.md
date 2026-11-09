---
name: sonar-fixes
description: 'How to fix SonarQube quality gate failures in Java, JavaScript and TypeScript properly: bugs, vulnerabilities, security hotspots, code smells, duplication and coverage on new code, without suppressing rules. Rule ids SQ-01... Use when Jenkins reports a failed quality gate.'
---

# Fixing SonarQube findings

The quality gate judges **new code** (the lines this PR changes). Fix what it reports in your
change; do not refactor unrelated legacy code in the same PR.

## Rules

- **SQ-01 Never suppress to pass.** No `@SuppressWarnings("java:Sxxxx")`, `//NOSONAR`,
  `sonar.exclusions` or lowered thresholds unless the human writes the OK in the PR. Explain
  a genuine false positive to the human instead.
- **SQ-02 Reproduce locally** what you can: tests and coverage with `java .agent/Sdlc.java verify`
  (JaCoCo report in `target/site/jacoco/index.html`), SpotBugs in `target/spotbugsXml.xml`.
- **SQ-03 Coverage on new code** below the gate: add tests that assert behaviour for the
  uncovered branches (see `java-testing` JT-02), not tests that only execute lines.
- **SQ-04 Duplication on new code:** extract the shared logic into a method or class used by
  both places.
- **SQ-05 Security hotspots** need a code fix or a human review in SonarQube; never mark them
  "safe" yourself.

## Common Java rules and the right fix

| Rule | Problem | Fix |
| --- | --- | --- |
| java:S2259 | Possible null dereference | Handle the `null`/`Optional` path explicitly |
| java:S2095 | Resource not closed | try-with-resources |
| java:S1166 / S2139 | Exception swallowed or logged and rethrown | Handle once: rethrow with context, or log and recover |
| java:S2076 / S3649 | Command / SQL injection | Bound parameters; never concatenate input |
| java:S5145 | User input in logs | Sanitize or log an id instead |
| java:S2245 | Insecure random | `SecureRandom` for security purposes |
| java:S4790 | Weak hash | SHA-256 or stronger (or a password hasher for passwords) |
| java:S3776 | Cognitive complexity | Split into smaller methods with clear names |
| java:S1192 | Repeated string literal | Constant with a meaningful name |
| java:S107 | Too many parameters | Parameter object / record |
| java:S1172 | Unused parameter | Remove it (check callers) |
| java:S125 | Commented-out code | Delete it |
| java:S6212 | Explicit type where `var` is clearer | Follow the team's style; not worth churn |
| java:S5786 | JUnit 5 test class/method public | Remove `public` |
| java:S2699 | Test without assertion | Assert the outcome |

## Common JavaScript / TypeScript rules and the right fix

| Rule | Problem | Fix |
| --- | --- | --- |
| typescript:S4325 / S4123 | Unnecessary or unsafe type assertion | Narrow with a type guard or validate |
| typescript:S6571 / S4204 | `any` in a union / explicit `any` | A precise type or `unknown` + narrowing |
| typescript:S4822 / S6544 | Promise not awaited or misused | `await` it, return it, or `void` with a reason |
| javascript:S2201 | Return value ignored | Use it or remove the call |
| javascript:S1854 | Dead store | Remove the assignment |
| javascript:S3776 | Cognitive complexity | Split into smaller functions |
| javascript:S1192 | Repeated string literal | Named constant |
| javascript:S5852 | Regex with super-linear backtracking (ReDoS) | Rewrite without nested quantifiers, bound input length |
| javascript:S2068 / S6418 | Hard-coded credential or secret | Read from env/config |
| javascript:S5247 / S6268 | Unsafe HTML (XSS) | Render text, sanitize with the project's sanitizer |
| javascript:S4507 | Debug features in production | Gate behind config, remove from the bundle |
| javascript:S1172 / S1481 | Unused parameter / variable | Remove (check callers) |
| javascript:S2699 | Test without assertion | Assert the outcome |

Coverage for JS/TS comes from `coverage/lcov.info` (`sonar.javascript.lcov.reportPaths`); run the
tests with coverage (`vitest run --coverage` / `jest --coverage`) before reading the numbers.

When a rule is not in this table, open its description in SonarQube (click the issue, "Why is
this an issue?") and apply the compliant solution shown there.
