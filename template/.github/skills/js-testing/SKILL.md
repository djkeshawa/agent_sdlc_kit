---
name: js-testing
description: 'How to write and run tests in this JavaScript or TypeScript repository: test-first, Vitest or Jest, Testing Library, Supertest, Playwright, coverage for SonarQube, fast targeted runs, and linking tests to the test design scenarios (TS-..). Rule ids JST-01... Use when adding or changing JS/TS code and its tests, or verifying a ticket.'
---

# JavaScript and TypeScript testing

Use the runner the project already has (see `docs/agent/project-profile.md`): Vitest or Jest for
unit and integration tests, Playwright or Cypress for end-to-end tests.

## Rules

- **JST-01 Test first.** For a bug: write the test that reproduces it and watch it fail before
  the fix. For a feature: write the test for each acceptance criterion first and watch it fail
  for the right reason (an assertion, not a type error or a missing module).
- **JST-02 Every branch.** Each `if`/`else`, `switch` case, early return, `catch` and loop edge
  (zero, one, many) of code you change has a test that takes it. Rejected promises count.
- **JST-03 Right level.**
  - Unit: plain functions and services with fakes for their dependencies. Fast. Most tests.
  - Integration: the HTTP layer with Supertest (or the framework's inject), a real database in
    Testcontainers or the project's test container setup, named `*.int.test.ts` or as the
    project does.
  - Components: Testing Library; query by role and label like a user, not by class names.
  - End to end: Playwright/Cypress for the few journeys the test design marks `e2e`.
- **JST-04 Names state behaviour**: `it('refuses a refund after day 30', ...)`. One behaviour per
  test; `describe` blocks group by unit, not by method name.
- **JST-05 Assert outcomes, not internals.** Return values, rendered output, HTTP status and
  body, stored rows, published messages. Spy on a call only when the call is the point (an e-mail
  sent). No snapshot tests for logic; snapshots only for stable rendered output, reviewed.
- **JST-06 Every async test awaits.** `await expect(promise).rejects.toThrow(...)`; no tests that
  pass because the promise was never awaited. Use fake timers (`vi.useFakeTimers()` /
  `jest.useFakeTimers()`) instead of real waits.
- **JST-07 Isolated and repeatable.** No dependence on test order, the wall clock (inject or fake
  it), random values without a seed, the network (mock `fetch` with MSW or the project's
  helper), or shared files. Reset mocks between tests.
- **JST-08 No disabling.** Never `it.skip`, `xit`, `describe.skip`, `.only`, `.todo` left in,
  `--passWithNoTests`, or a deleted assertion. The hook, git hook and PR policy refuse them. Fix
  the flake: usually time, order, unawaited promises or shared state.
- **JST-09 Test data** through small builders in the test file, not giant shared fixtures. Fake
  secrets in tests carry `// guard:allow-secret` on that line.
- **JST-10 Coverage is a floor, not a goal.** The runner writes `coverage/lcov.info` for
  SonarQube, which checks coverage on new code; a test that runs code without asserting its result
  does not count in review.

## Running tests (fast loop)

```sh
# one file / one test (Vitest)
npx vitest run src/refund/refund-policy.test.ts -t "refuses a refund after day 30"
# Jest
npx jest src/refund/refund-policy.test.ts -t "refuses a refund after day 30"
# type check and lint, as verify does
npx tsc --noEmit && npm run lint --if-present
# everything, as CI does - must pass before you finish
java .agent/Sdlc.java verify
```

## Linking to the test design

Every test scenario (`TS-..`) of your task gets a row in `docs/test-links/<STORY>.md`, with the
test file and the exact test name (the string in `it(...)` / `test(...)`):

```markdown
| Scenario | Test |
| --- | --- |
| TS-01 | src/refund/refund-policy.test.ts#allows a refund within 30 days |
| TS-04 | test/api/refunds.int.test.ts#returns 404 for another customer's order |
| TS-09 | manual: receipt layout checked by QA on staging |
```

`java .agent/Sdlc.java trace` checks that each file exists and contains that test name.
