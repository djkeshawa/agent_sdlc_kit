# Contract tests and mutation testing

Two checks that make agent-written code harder to fake. Neither is required by the kit; both
plug into what you have. See [docs/08](../docs/08-multi-component.md).

## Mutation testing on changed code (`build.mutation`)

Coverage says code ran; mutation testing says a test would notice it breaking. Run it on the
lines a change touched, not the whole code base.

**Java (PIT, Maven).** In `pom.xml` (check for newer versions of both):

```xml
<plugin>
  <groupId>org.pitest</groupId>
  <artifactId>pitest-maven</artifactId>
  <version>1.17.4</version>
  <dependencies>
    <dependency>
      <groupId>org.pitest</groupId>
      <artifactId>pitest-junit5-plugin</artifactId>
      <version>1.2.1</version>
    </dependency>
  </dependencies>
  <configuration>
    <mutationThreshold>70</mutationThreshold>
    <timestampedReports>false</timestampedReports>
  </configuration>
</plugin>
```

`.agent/sdlc.yml`:

```yaml
build:
  mutation: ./mvnw -B -ntp -q org.pitest:pitest-maven:scmMutationCoverage -DoriginBranch=origin/develop -DdestinationBranch=HEAD
```

**Node (Stryker).** `npm i -D @stryker-mutator/core @stryker-mutator/vitest-runner`, then:

```yaml
build:
  mutation: npx stryker run --incremental --mutate "$(git diff --name-only origin/develop... -- 'src/**/*.ts' | paste -sd, -)"
```

`verify` runs the command after the build; a non-zero exit fails verify, so the PR gate waits.

## Consumer-driven contract tests

The solution design lists contract changes; the PR check refuses operations it does not list.
Contract tests prove the provider still serves what each consumer relies on.

- **Java only**: Spring Cloud Contract. Consumers write contracts in the provider's repository
  (`src/test/resources/contracts/<consumer>/`); the provider's build generates tests from them;
  consumers use the published stubs (`@AutoConfigureStubRunner`).
- **Java and Node**: Pact. Consumers publish pacts to a Pact Broker; the provider's build
  verifies them (`pact-jvm-provider-junit5`, `@pact-foundation/pact`); `can-i-deploy` gates the
  deploy order the solution design gives.

Consumers in a cross-component feature build in parallel against a mock from the provider's
spec on its feature branch (`java .agent/Sdlc.java repos sync`, then Prism or WireMock), not
waiting for the provider to merge.
