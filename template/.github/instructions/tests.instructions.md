---
applyTo: "**/src/test/**,**/src/it/**,**/src/integrationTest/**,**/*.{test,spec}.{js,jsx,ts,tsx,mjs,cjs},**/__tests__/**,**/test/**,**/tests/**,**/e2e/**"
description: "Rules for test code"
---

# Test files

- Java: follow the `java-testing` skill (rule ids `JT-..`): JUnit 5 + AssertJ + Mockito, the
  method name says the behaviour (`refusesRefundWhenOrderIsAlreadyRefunded()`).
- JavaScript / TypeScript: follow the `js-testing` skill (`JST-..`): the project's runner
  (Vitest or Jest), the test name says the behaviour (`it('refuses a refund after day 30')`),
  every promise awaited.
- One behaviour per test.
- Never add `@Disabled`, `@Ignore`, `assumeTrue(false)`, `it.skip`, `xit`, `.only` or empty tests. The git hook and the PR
  policy refuse them. Fix flaky tests; do not switch them off.
- Never delete or weaken an existing assertion to make a test pass. If an existing test
  contradicts the approved LLD or test design, stop and ask the human.
- Every test scenario `TS-..` of your task gets a row in `docs/test-links/<STORY>.md`:
  `| TS-01 | src/test/java/.../OrderServiceTest.java#refusesEmptyCart |`; check with
  `java .agent/Sdlc.java trace`.
