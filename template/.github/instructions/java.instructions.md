---
applyTo: "**/*.java"
description: "Java rules for production and test code in this repository"
---

# Java files

- Follow the `java-coding-standards` skill (rule ids `JV-..`). Reviews cite those ids.
- Match the package layout, naming and style of the surrounding code. Do not reformat lines
  you did not change; Spotless checks only changed files.
- Constructor injection only (no field `@Autowired`). Make fields `final`.
- No `System.out` / `printStackTrace`; use the class logger (SLF4J) with placeholders:
  `log.info("Order {} paid", orderId)`. Never log secrets or personal data.
- Catch the narrowest exception you can handle. Never swallow one: rethrow with context or
  translate to a domain exception.
- Validate input at the boundary (controller, message listener). Return `Optional` instead of
  `null` from finder methods. Never return `null` collections.
- Use `java.time` (`Instant`, `LocalDate`), never `Date` or `Calendar`. Use `BigDecimal` for
  money.
- SQL only through parameters (JPA, `JdbcTemplate` with `?`/named parameters). Never build SQL
  from strings.
- New public classes and methods have Javadoc that says *why* and *what for*, not *how*.
