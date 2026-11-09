## PROJ-000: <summary>

**Story:** PROJ-000   **Tasks:** PROJ-001, PROJ-002
**LLD:** <Confluence link, version> (approved in Jira by: <name>)
**Test design:** <Confluence link, version> (approved in Jira by: <name>)
**Test links:** `docs/test-links/PROJ-000.md`

### What changed
-

### How it was verified
- [ ] `java .agent/Sdlc.java verify` passed for the pushed commit (paste the last line)
- [ ] `java .agent/Sdlc.java trace PROJ-000` passed
- [ ] Every task approved by the blind reviewer after at least 2 rounds (`java .agent/Sdlc.java status`)
- [ ] QA scenarios (integration / contract / e2e) passed, if the test design has them

### Agent disclosure
- Written by: <human | Copilot agents + human>
- Review / QA / CI rounds: <from `java .agent/Sdlc.java retro PROJ-000`>
- Anything not verified or left open:

### Reviewer checklist (human)
- [ ] The change does what the approved LLD and test design ask, nothing more
- [ ] Tests would fail if the code were wrong
- [ ] No secrets, no disabled tests, no skipped or weakened checks
- [ ] Jenkins green, SonarQube quality gate passed
