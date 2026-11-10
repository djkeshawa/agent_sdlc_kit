# {KEY} {Feature name} - Test scenarios

- Jira: {KEY}
- Sources: one pager {link + version}, HLD {link + version}, repository at commit {sha}
- Author: QE (drafted by the qe-designer agent) - status: Draft until approved in Jira

## Scope

What this test design covers and what it does not (performance is tested in the performance
environment; list it under "Out of scope" with a pointer if the one pager states a budget).

## Requirements

| Id | Requirement (from the one pager / HLD, quoted) | Source |
| --- | --- | --- |
| R-01 | "Customers can refund a paid order within 30 days" | One pager §2 |
| R-02 | "Refunds over 1000 EUR need a second approval" | HLD §4.3 |

<!-- The mind map is generated from the "## area" and "### TS-.." headings below. -->

## Refund API

### TS-01 Paid order is refunded

- Requirements: R-01
- Level: integration          <!-- unit | integration | e2e | contract | manual | performance -->
- Components: payments-service <!-- cross-component features: the component(s) whose team owns it -->
- Hold-out: no                <!-- yes = QE keeps this test in its own repository, hidden from the implementer -->
- Priority: P1                <!-- P1 must pass for release, P2 should, P3 nice to have -->
- Type: functional            <!-- functional | negative | boundary | security | regression | data -->
- Given an order paid 3 days ago
- When the customer requests a full refund
- Then the API returns 201, the refund is stored with status PENDING and an event is published
- Test data: order in state PAID, amount 50.00 EUR

### TS-02 Refund on day 31 is refused

- Requirements: R-01
- Level: unit
- Priority: P1
- Type: boundary
- Given an order paid 31 days ago
- When a refund is requested
- Then 409 with error code REFUND_WINDOW_CLOSED; nothing is stored

## Security

### TS-03 Another customer's order cannot be refunded

- Requirements: R-01
- Level: integration
- Priority: P1
- Type: security
- Given order O1 belongs to customer A
- When customer B requests a refund of O1
- Then 404 (not 403, to avoid leaking existence); nothing is stored

## Traceability

| Requirement | Scenarios |
| --- | --- |
| R-01 | TS-01, TS-02, TS-03 |
| R-02 | (gap - see open questions) |

## Out of scope

- Performance (perf environment)

## Assumptions

## Open questions

1. R-02: who is the second approver? Suggested: a finance role from the HLD.
