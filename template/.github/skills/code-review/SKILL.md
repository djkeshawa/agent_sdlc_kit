---
name: code-review
description: 'How to review a change in this repository against its approved LLD and test design: per-criterion delivery, blocking vs advisory findings with evidence, and Java and JavaScript/TypeScript defect checklists. Rule ids RV-01... Use for the reviewer agent and for humans reviewing agent pull requests.'
---

# Code review

## Principles

- **RV-01 Design first, then code.** Judge against the task's acceptance criteria, the approved
  LLD and test design and the project profile, not against the implementer's explanation (do
  not read it).
- **RV-02 Blocking needs a citation and evidence.** Cite an acceptance criterion, a scenario
  `TS-..`, a rule id (`P-..`, `JV-..`, `JT-..`) or a defect class (correctness, security, data loss, regression,
  performance), *and* show it: a failing input, a command and its output, or file:line with
  the exact path through the code.
- **RV-03 Advisory is advisory.** Naming, style and ideas never block. Keep the list short.
- **RV-04 Tests prove behaviour.** For each criterion, ask: would this test fail if the code
  were wrong? A test that only executes code is a finding.
- **RV-05 Scope.** Work no criterion asked for is reported as *Unrequested*, even if it is good.

## Checklist (Java)

- Correctness: off-by-one, null/Optional handling, equals/hashCode on entities, time zones,
  `BigDecimal` scale and rounding, integer overflow, string comparison with `==`.
- Errors: swallowed exceptions, wrong exception type, missing rollback (checked exceptions do
  not roll back `@Transactional` by default), error responses leaking internals.
- Transactions: self-invocation bypassing `@Transactional`, long transactions around remote
  calls, missing `readOnly`, lost updates without optimistic locking (`@Version`).
- Persistence: N+1 queries, `findAll()` on large tables, missing index for a new query, migration
  that locks a big table, editing an applied migration.
- Security: missing authorization on new endpoints, IDOR (using an id from the request without
  ownership check), injection, secrets in code or logs, CORS/CSRF changes, unsafe
  deserialization, path traversal on file names.
- Concurrency: shared mutable state in singletons, non-thread-safe types (`SimpleDateFormat`,
  `HashMap`) shared across threads, check-then-act races.
- API: breaking changes in REST/JSON/messages, missing validation, wrong HTTP status codes.
- Resources: unclosed streams/connections, unbounded collections, missing timeouts on HTTP
  clients.
- Tests: missing error-path tests, over-mocking, assertions on mocks instead of results,
  time/order dependence, disabled tests.

## Checklist (JavaScript / TypeScript)

- Types: `any`, unchecked casts (`as X` on external data), non-null `!` on values that can be
  missing, `@ts-ignore`; input not validated at the boundary.
- Async: floating promises, `await` missing in a `try`, sequential `await` in loops, no timeout
  or abort on remote calls, unhandled rejections, race conditions between requests.
- Errors: empty `catch`, `.catch(() => {})`, thrown strings, error details leaking to clients.
- Correctness: `==`, truthiness bugs (`0`, `''` treated as missing), floating-point money, time
  zones, mutation of arguments or shared state, array index off-by-one.
- Security: injection into SQL/shell/HTML (`innerHTML`, `dangerouslySetInnerHTML`), `eval`,
  missing authorisation or ownership checks (IDOR), secrets in code or the front-end bundle,
  prototype pollution from merging untrusted objects, unsafe redirects, permissive CORS.
- Performance: N+1 queries or requests, unbounded lists, reading large files into memory,
  re-renders from unstable props/keys, effects without cleanup.
- Dependencies: new packages without a reason, lockfile changes not matching `package.json`.
- Tests: unawaited assertions, snapshot tests standing in for logic, real timers or network,
  disabled or focused tests.

## Verdict

APPROVE only when there is no blocking finding and every acceptance criterion of the task is
delivered. Otherwise
CHANGES_REQUESTED, with the blocking findings first.
