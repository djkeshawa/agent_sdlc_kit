---
name: java-coding-standards
description: 'Java coding rules for this repository (Java 17+, Spring Boot): design, naming, errors, logging, persistence, security and performance. Every rule has an id (JV-01...) that reviews cite. Use when writing, changing or reviewing Java production code.'
---

# Java coding standards

Match the surrounding code first; these rules decide when the code around you does not.

## Design

- **JV-01 One responsibility.** A class has one reason to change; a method does one thing.
  Methods over ~30 lines or with more than 3 levels of nesting get split.
- **JV-02 Layers.** Controller (HTTP, validation, mapping) -> service (business rules,
  transactions) -> repository (persistence). No business logic in controllers or entities; no
  HTTP types (`ResponseEntity`, `HttpServletRequest`) below the controller.
- **JV-03 Constructor injection**, fields `private final`. No field `@Autowired`, no
  `ApplicationContext.getBean`.
- **JV-04 Immutability.** Prefer records for DTOs and value objects, `List.copyOf` for
  defensive copies, no public setters on domain objects without a reason.
- **JV-05 No speculative generality.** No interfaces with one implementation "for later", no
  unused parameters, no dead or commented-out code.

## Naming

- **JV-06** Names say what, not how: `findOverdueInvoices`, not `getData2`. Booleans read as
  questions: `isActive`, `hasRefund`. No abbreviations except well-known ones (`id`, `url`).

## Errors

- **JV-07 Never swallow.** No empty `catch`. Catch the narrowest type; rethrow with context
  (`throw new PaymentFailedException(orderId, e)`) or handle fully.
- **JV-08 Domain exceptions** for business failures, mapped to HTTP in one
  `@RestControllerAdvice`. No stack traces in API responses.
- **JV-09 Null.** Finder methods return `Optional<T>`; collections are never `null`. Validate
  arguments with `Objects.requireNonNull` or Bean Validation at the boundary.

## Logging

- **JV-10** SLF4J with placeholders: `log.warn("Refund {} rejected: {}", id, reason)`. No string
  concatenation in log calls, no `System.out`, no `printStackTrace`.
- **JV-11 Never log** passwords, tokens, card numbers, full personal data or whole request bodies.

## Persistence and transactions

- **JV-12** `@Transactional` on service methods, not on controllers or repositories; read-only
  queries use `@Transactional(readOnly = true)`.
- **JV-13 No N+1.** Loading a list of entities with their relations uses a fetch join or
  `@EntityGraph`, not a query per row.
- **JV-14 SQL injection.** Only bound parameters. Never concatenate user input into JPQL, SQL,
  LDAP, or shell commands.
- **JV-15 Schema changes** only through migration scripts (Flyway/Liquibase), never by editing an
  applied migration.

## Security

- **JV-16 Authorization** is checked on every new endpoint (method security or the security
  config), with a test proving an unauthorized caller is refused.
- **JV-17 Input** is validated (`@Valid`, sizes, ranges, formats). Output is encoded by the
  framework; no hand-built HTML or JSON strings.
- **JV-18 Secrets** come from configuration/vault, never from code or committed files.
- **JV-19 Deserialization** only into known types; no Java native deserialization of external
  input.

## Performance and resources

- **JV-20** Close resources with try-with-resources. No unbounded caches, queues or `findAll()`
  on large tables; page instead.
- **JV-21** Pick data structures for the access pattern (`HashMap`/`HashSet` for lookups, not
  `List.contains` in loops). State the cost of anything worse than O(n log n) on large inputs.
- **JV-22 Concurrency.** Shared mutable state is avoided or guarded; prefer `java.util.concurrent`
  types. No `Thread.sleep` for coordination.

## Time and money

- **JV-23** `java.time` only (`Instant` for timestamps, `LocalDate` for dates, an injected
  `Clock` for testability). `BigDecimal` with an explicit `RoundingMode` for money.

## API changes

- **JV-24** Public REST and message contracts are backward compatible unless the LLD says
  otherwise: add fields, never rename or remove them silently.
