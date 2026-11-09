---
name: java-testing
description: 'How to write and run tests in this Java repository: test-first, JUnit 5, AssertJ, Mockito, Spring slices, Testcontainers, JaCoCo, fast targeted runs, and linking tests to the test design scenarios (TS-..). Rule ids JT-01... Use when adding or changing code and its tests, or verifying a ticket.'
---

# Java testing

## Rules

- **JT-01 Test first.** For a bug: write the test that reproduces it and watch it fail before
  the fix. For a feature: write the test for each acceptance criterion first and watch it fail
  for the right reason (an assertion, not a compile error or missing bean).
- **JT-02 Every branch.** Each `if`/`else`, `switch` case, early return, `catch` and loop edge
  (zero, one, many) of code you change has a test that takes it. Error paths count.
- **JT-03 Right level.**
  - Unit: plain JUnit + Mockito, no Spring context. Fast. Most tests live here.
  - Slice: `@WebMvcTest` (controller + validation + security), `@DataJpaTest` (repository
    queries) - only the layer under test.
  - Integration: `@SpringBootTest` with Testcontainers (real Postgres/Kafka/...), named `*IT`
    and run by Failsafe. For behaviour across a boundary.
- **JT-04 Names state behaviour**: `refusesRefundWhenOrderIsAlreadyRefunded()`. One behaviour
  per test; several assertions are fine when they describe it. Use `@DisplayName` only if the
  team does.
- **JT-05 Assert outcomes, not internals.** Check return values, persisted state, HTTP status
  and body, published events. `verify(mock)` only for side effects that are the point (an e-mail
  sent), never for every call.
- **JT-06 AssertJ** (`assertThat(x).isEqualTo(y)`, `assertThatThrownBy(...)
  .isInstanceOf(...).hasMessageContaining(...)`). No `assertTrue(a.equals(b))`.
- **JT-07 Isolated and repeatable.** No dependence on test order, wall-clock time (inject a
  `Clock`), random values without a seed, the network, or shared files. Use `@TempDir`.
- **JT-08 No disabling.** Never `@Disabled`, `@Ignore`, `assumeTrue(false)` or deleting an
  assertion. The git hook and Jenkins refuse them. Fix the flake: usually time, order or
  shared state.
- **JT-09 Test data** through small builders or factory methods in the test, not giant shared
  fixtures. Fake secrets in tests carry `// guard:allow-secret` on that line.
- **JT-10 Coverage is a floor, not a goal.** JaCoCo and SonarQube check coverage on new code;
  a test that executes code without asserting its result does not count in review.

## Running tests (fast loop)

```sh
# one class (unit)
./mvnw -B -ntp test -Dtest=OrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false
# one method
./mvnw -B -ntp test -Dtest='OrderServiceTest#refusesEmptyCart' -Dsurefire.failIfNoSpecifiedTests=false
# one module of a multi-module build, with what it depends on
./mvnw -B -ntp -pl orders-service -am test -Dtest=OrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false
# one integration test (Failsafe)
./mvnw -B -ntp verify -Dit.test=OrderApiIT -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false
# everything, as Jenkins does - must pass before you finish
java .agent/Sdlc.java verify
```

Windows: `mvnw.cmd` instead of `./mvnw`; `java .agent/Sdlc.java verify` is the same everywhere.

## Linking to the test design

Every test scenario (`TS-..`) of your task gets a row in `docs/test-links/<STORY>.md`:

```markdown
| Scenario | Test |
| --- | --- |
| TS-01 | src/test/java/com/acme/orders/OrderServiceTest.java#refusesEmptyCart |
| TS-04 | src/test/java/com/acme/orders/OrderApiIT.java#returns404ForUnknownOrder |
| TS-09 | manual: printer output checked by QA on staging |
```

`java .agent/Sdlc.java trace` checks that each file exists and contains the method
(`manual:`, `other-repo:` and `n/a:` rows carry the reason after the colon). The PR gate runs the same check.

## Performance tests

Run at least 5 times, report median and p90, compare with the budget in the criterion. Warm up
the JVM first (or use JMH for micro-benchmarks). One run proves nothing.
