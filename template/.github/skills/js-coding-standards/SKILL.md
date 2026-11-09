---
name: js-coding-standards
description: 'JavaScript and TypeScript coding rules for this repository (Node.js services and web front ends): types, design, errors, async, logging, security and performance. Every rule has an id (JS-01...) that reviews cite. Use when writing, changing or reviewing JS or TS production code.'
---

# JavaScript and TypeScript coding standards

Match the surrounding code and the project profile first; these rules decide when the code
around you does not. The linter and the type checker are part of `java .agent/Sdlc.java verify`;
a rule they enforce is not repeated here.

## Types (TypeScript)

- **JS-01 Strict types.** Code compiles with `strict: true`. No `any`: use `unknown` and narrow,
  or a precise type. No `@ts-ignore` / `@ts-nocheck`; `@ts-expect-error` only with a reason
  comment and a test that shows why.
- **JS-02 Types at the boundary.** Data from HTTP, queues, files, env and third-party APIs is
  `unknown` until validated by a schema (zod, valibot, ajv, class-validator - whatever the project
  uses). Inside, trust the types; do not re-validate everywhere.
- **JS-03 Model states, not flags.** Discriminated unions (`{status: 'paid', paidAt} |
  {status: 'open'}`) over optional fields that must be set together. `readonly` for data that
  must not change.
- **JS-04 No non-null assertions** (`x!`) on values that can really be missing; handle the
  missing case.

## Design

- **JS-05 One responsibility.** A module exports one concept; a function does one thing.
  Functions over ~30 lines or with more than 3 levels of nesting get split.
- **JS-06 Layers.** Route/handler (HTTP, validation, mapping) -> service (business rules) ->
  repository/client (persistence, remote calls). No business logic in handlers, components or ORM
  models; no `req`/`res` objects below the handler. Front ends: components render, hooks/stores
  hold state, API clients talk to the server.
- **JS-07 Dependencies are passed in** (function parameters, constructor, a small factory) so
  tests can replace them; no hidden module-level singletons that hold connections or clocks.
- **JS-08 Pure where possible.** No mutation of arguments; return new objects/arrays. Module
  top level does no I/O.
- **JS-09 No speculative generality.** No abstractions with one user "for later", no unused
  exports, parameters or dead code. Named exports over default exports.

## Naming and style

- **JS-10** Names say what, not how: `findOverdueInvoices`, not `getData2`. Booleans read as
  questions (`isActive`, `hasRefund`). `camelCase` values and functions, `PascalCase` types and
  components, `UPPER_SNAKE` only for true constants.
- **JS-11** `const` by default, `let` when reassigned, never `var`. `===` always. No `==` tricks.

## Errors and async

- **JS-12 Never swallow.** No empty `catch`, no `.catch(() => {})`. Catch where you can handle;
  rethrow with context (`throw new RefundError(orderId, {cause: e})`).
- **JS-13 Every promise is awaited or returned.** No floating promises (the linter's
  `no-floating-promises`); `void` only with a comment saying why. Use `Promise.all` for
  independent work, not sequential `await` in a loop.
- **JS-14 Errors are `Error` objects** (or subclasses with a `code`), never strings. HTTP errors
  map in one place (error middleware / exception filter); no stack traces in responses.
- **JS-15 Timeouts and cancellation** on every remote call (`AbortSignal.timeout(...)` or the
  client's option). Retries only for idempotent calls, with backoff.

## Logging and observability

- **JS-16** The project's logger (pino, winston, ...) with structured fields:
  `log.warn({orderId, reason}, 'refund rejected')`. No `console.log` in production code. Never
  log secrets, tokens, passwords or personal data; log ids.

## Security

- **JS-17 Injection.** Parameterised queries / the ORM's bound parameters; never build SQL, shell
  commands (`child_process.exec`), paths or HTML from input. No `eval`, `new Function`,
  `dangerouslySetInnerHTML` / `innerHTML` with input.
- **JS-18 Authorisation** on every new route, checked in the service against the resource owner
  (no IDOR: an id from the request is checked for ownership).
- **JS-19 Secrets** come from env/config at startup, never literals. Front-end code holds no
  secrets at all: everything in the bundle is public.
- **JS-20 Dependencies.** No new package without a reason in the LLD; prefer the platform
  (`fetch`, `crypto.randomUUID`, `structuredClone`). Lockfile changes are reviewed.

## Data and performance

- **JS-21 Money and time.** Integer minor units (cents) or a decimal library for money, never
  floating point. Dates in UTC (`Date`/Temporal/ISO strings), time zones only at the edges;
  inject a clock for "now".
- **JS-22 No N+1.** Batch database and HTTP calls; paginate lists; stream large files instead of
  reading them into memory.
- **JS-23 Front end:** no layout-thrashing loops, memoise only measured hot spots, lists have
  stable `key`s, effects clean up (subscriptions, timers, abort controllers).
- **JS-24 Contracts.** Public REST/GraphQL/event payloads stay backward compatible unless the LLD
  says otherwise: add fields, never rename or remove them silently.
