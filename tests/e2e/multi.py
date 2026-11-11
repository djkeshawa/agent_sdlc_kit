# Multi-component checks, run by flow.py in its namespace (PROJ, sdlc, hook, bash, admin, state, check, write ...).
# The repository is payments-service on feature/PAY-100-refunds after the single-repo flow.

# ---------- Phase 0: scoping eval ----------
admin("/__gh/commits", {"commits": [
    {"repo": "acme/payments-service", "sha": "a1", "message": "feat(PAY-100): refund endpoint"},
    {"repo": "acme/orders-service", "sha": "b1", "message": "feat(PAY-101): show refund status (carries a sub-task's key)"},
    {"repo": "acme/orders-service", "sha": "b2", "message": "fix(PAY-102): status label"},
    {"repo": "acme/unrelated", "sha": "c1", "message": "chore(PAY-1000): not this epic"},
]})
code, out = sdlc("eval", "collect", "PAY-10")
truth = PROJ / ".sdlc/hidden/eval/truth/PAY-10.yml"
case = PROJ / "docs/agent/eval/scoping/cases/PAY-10.md"
check("eval collect: truth from commits by the epic's, its stories' and their sub-tasks' keys", code == 0 and truth.exists()
      and "components: [orders-service, payments-service]" in truth.read_text() and "unrelated" not in truth.read_text(), out)
check("eval collect: case input has the epic's Jira text", case.exists() and "Refunds epic" in case.read_text(), out)
d, why = hook("read_file", {"filePath": str(truth)})
check("eval: agent cannot read the ground truth", d == "deny", why)
d, why = bash("grep -r components .sdlc/hidden/eval")
check("eval: nor grep it", d == "deny", why)
d, why = bash("java .agent/Sdlc.java eval score")
check("eval: agents cannot run the scorer (its report shows the truth)", d == "deny", why)
agent_write(".sdlc/eval/inbox/run1/PAY-10.yml", "components:\n  - name: payments-service\n  - name: orders-service\n")
agent_write(".sdlc/eval/inbox/run2/PAY-10.yml", "components: [payments-service]\n")
code, out = sdlc("eval", "score")
check("eval score: pass@k, pass^k, recall per case", code == 0 and "| pass@k (some run exactly right) | 1.00 |" in out
      and "| pass^k (every run exactly right) | 0.00 |" in out and "| Recall (mean) | 0.75 |" in out
      and (PROJ / ".sdlc/hidden/eval/report.md").exists(), out)

# ---------- Phase 1: catalog, solution design, stories, lanes ----------
OPENAPI = """openapi: 3.0.3
paths:
  /orders/{orderId}/refunds:
    post:
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [amount]
              properties:
                amount: {type: string}
                reason: {type: string, enum: [DAMAGED, OTHER]}
      responses:
        "201": {description: created}
        "409": {description: window closed}
"""
CATALOG = """apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: payments-service
  annotations:
    sdlc/owns: "payment, refund"
    sdlc/invariants: "refunds never exceed the captured amount"
spec:
  type: service
  owner: group:team-payments
  providesApis: [payments-api]
  consumesApis: [orders-api]
---
apiVersion: backstage.io/v1alpha1
kind: API
metadata:
  name: payments-api
spec:
  type: openapi
  owner: group:team-payments
  definition:
    $text: ./api/openapi.yaml
"""
ORDERS = """apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: orders-service
  annotations:
    sdlc/owns: "order"
spec:
  owner: group:team-orders
  providesApis: [orders-api]
---
apiVersion: backstage.io/v1alpha1
kind: API
metadata:
  name: orders-api
spec:
  type: openapi
  definition:
    $text: ./api/orders.yaml
"""
yml = (PROJ / ".agent/sdlc.yml").read_text()
yml = yml.replace("  relatedRepos: []\n", "  relatedRepos:\n    - {name: orders-service, repo: acme/orders-service, team: orders, purpose: owns orders}\n", 1)
yml = yml.replace("  enabled: false                  # true = tasks create", "  enabled: true                   # true = tasks create", 1)
(PROJ / ".agent/sdlc.yml").write_text(yml)
check("config with lanes and a related repository is valid", sdlc("config", "check")[0] == 0)

d, why = hook("create_file", {"filePath": str(PROJ / "catalog-info.yaml")})
check("catalog-info.yaml is not agent-editable", d == "deny", why)
write("catalog-info.yaml", CATALOG)
write("api/openapi.yaml", OPENAPI)
write("src/main/resources/application.yml", "orders:\n  url: http://orders-service.internal\n")
write("src/main/java/com/acme/payments/orders/OrdersClient.java", 'package com.acme.payments.orders;\n\n@FeignClient(name = "orders", url = "${orders.url}")\n'
      'interface OrdersClient {\n    @GetMapping("/orders/{id}")\n    String get(String id);\n}\n')
code, out = sdlc("catalog", "check")
check("catalog check: complete catalog-info.yaml", code == 0 and "component payments-service" in out, out)
admin("/__gh/files", {"files": {"acme/orders-service:catalog-info.yaml": ORDERS,
                                "acme/orders-service:api/orders.yaml": "openapi: 3.0.3\npaths:\n  /orders/{id}:\n    get:\n      responses:\n        \"200\": {description: ok}\n"}})
code, out = sdlc("catalog", "sync")
check("catalog sync: related repository's catalog and API file through gh", code == 0 and "component orders-service, 1 API definition(s)" in out
      and (PROJ / ".sdlc/catalog/apis/orders-api.yaml").exists(), out)
code, out = sdlc("catalog", "scan", "--write")
check("catalog scan: the Feign call resolves to orders-service", code == 0 and "GET /orders/{id}" in out and "orders-service" in out
      and (PROJ / "docs/agent/catalog-edges.json").exists(), out)
code, out = sdlc("catalog", "graph")
check("catalog graph: page for agents", code == 0 and (PROJ / ".sdlc/catalog/graph.md").exists() and "| orders-service | acme/orders-service |" in out, out)

SOLUTION = """# PAY-10 - Solution design

## 1. Requirements

- R-01: When a paid order is less than 30 days old, the payments service shall accept a refund request.
- R-02: When a refund is requested, the orders service shall show the refund status on the order.

## 8. The block the tool reads

```yaml
# sdlc-solution
requirements: [R-01, R-02]
components:
  - name: payments-service
    role: provider
    change: refund endpoint
    requirements: [R-01]
  - name: orders-service
    role: consumer
    change: show refund status
    requirements: [R-02]
ruledOut: []
disputed: [%s]
contracts:
  - {provider: payments-service, api: payments-api, add: ["POST /orders/{orderId}/refunds"], compatibility: additive}
order: [payments-service, orders-service]
```
"""
agent_write(".sdlc/PAY-10/drafts/solution.md", SOLUTION % "notification-service")
code, out = sdlc("docs", "publish", "PAY-10", "solution", "--file", ".sdlc/PAY-10/drafts/solution.md")
check("solution publish refused while a component is disputed", code != 0 and "disputed" in out, out)
agent_write(".sdlc/PAY-10/drafts/solution.md", SOLUTION % "")
code, out = sdlc("docs", "publish", "PAY-10", "solution", "--file", ".sdlc/PAY-10/drafts/solution.md")
check("solution design published to ARCH with an approval request on the epic", code == 0 and "Published Solution design v1" in out
      and any(p["title"] == "PAY-10 - Solution design" and p["space"] == "ARCH" for p in state()["pages"].values()), out)
code, out = sdlc("stories", "create", "PAY-10")
check("stories refused before the architect approves", code != 0 and "NOT approved" in out, out)
line = next(l.strip() for l in out.splitlines() if l.strip().startswith("SDLC-APPROVED PAY-10 solution"))
admin("/__admin/comment", {"key": "PAY-10", "body": line, "accountId": "architect-1", "name": "Architect"})
code, out = sdlc("stories", "create", "PAY-10")
st = state()
stories = {v["summary"].split("]")[0][1:]: k for k, v in st["issues"].items() if v.get("parent") == "PAY-10" and v["type"] == "Story" and v["summary"].startswith("[")}
check("one Jira story per component under the epic", code == 0 and set(stories) == {"payments-service", "orders-service"}, out)
PAY_STORY = stories.get("payments-service", "PAY-0")
code, out = sdlc("stories", "create", "PAY-10")
check("stories create again creates nothing", code == 0 and "0 created" in out, out)
code, out = sdlc("docs", "pull", PAY_STORY)
slice_file = PROJ / (".sdlc/%s/sources/solution-slice.md" % PAY_STORY)
check("docs pull of the component story writes its solution slice", code == 0 and slice_file.exists() and "solutionSlice:" in slice_file.read_text(), out)
code, out = sdlc("classify", PAY_STORY)
check("classify: a story under a solution is cross-component", code == 0 and "Lowest lane for %s: cross-component" % PAY_STORY in out, out)
code, out = sdlc("feature", "status", "PAY-10")
check("feature status: approved solution, stories without LLDs", code == 1 and "APPROVED by Architect" in out and "no LLD yet" in out and "0 of 2" in out, out)

# ---------- Phase 2: locked acceptance tests, contract policy, hold-out results ----------
ACC = "src/test/java/com/acme/payments/refund/RefundAcceptanceTest.java"
agent_write(".sdlc/PAY-100/drafts/acceptance/" + ACC, "package com.acme.payments.refund;\n\npublic class RefundAcceptanceTest {\n"
            "    public static void main(String[] a) { System.out.println(\"TS-01 acceptance ok\"); }\n}\n")
d, why = bash("java .agent/Sdlc.java tests lock PAY-100")
check("agents cannot lock acceptance tests (QE's approval)", d == "deny", why)
code, out = sdlc("tests", "lock", "PAY-100")
check("QE locks the drafted acceptance test into the repository", code == 0 and (PROJ / ACC).exists() and (PROJ / "docs/test-locks/PAY-100.json").exists(), out)
d, why = hook("replace_string_in_file", {"filePath": str(PROJ / ACC)})
check("agent edit of a locked acceptance test refused", d == "deny" and "locked by QE" in why, why)
d, why = bash("sed -i s/ok/weakened/ " + ACC)
check("shell edit of a locked acceptance test refused", d == "deny", why)
check("lock committed with the test", commit("test(PAY-100): lock acceptance tests")[0] == 0)
locked = (PROJ / ACC).read_text()
write(ACC, locked.replace("acceptance ok", "weakened"))
git("add", ACC)
code, out = git("commit", "-m", "test(PAY-100): weaken the acceptance test")
check("pre-commit refuses a change to a locked test", code != 0 and "Locked acceptance test" in out, out)
code, out = sdlc("verify")
check("verify refuses while a locked test is changed", code != 0 and "locked acceptance tests changed" in out, out)
code, out = sdlc("check-pr")
check("check-pr fails on a changed locked test", code != 0 and "FAIL  Locked acceptance tests unchanged" in out, out)
git("reset", "-q", "HEAD", ACC)
write(ACC, locked)
code, out = sdlc("tests", "status", "PAY-100")
check("tests status: lock intact again", code == 0 and ACC in out and "BROKEN" not in out, out)

git("switch", "-q", "-c", "feature/PAY-100-contract")
git("add", "catalog-info.yaml", "api/openapi.yaml")
commit("feat(PAY-100): refund contract")
code, out = sdlc("check-pr")
check("check-pr fails a contract change with no recorded lane", code != 0 and "Contract api/openapi.yaml changed, but PAY-100 has no PAY-100.json" in out, out)
git("switch", "-q", "feature/PAY-100-refunds")
git("checkout", "feature/PAY-100-contract", "--", "catalog-info.yaml", "api/openapi.yaml")
git("reset", "-q", "HEAD", "catalog-info.yaml", "api/openapi.yaml")

yml = (PROJ / ".agent/sdlc.yml").read_text()
(PROJ / ".agent/sdlc.yml").write_text(yml.replace("holdout:\n  jenkinsJob: \"\"", "") + "\nholdout:\n  jenkinsJob: http://127.0.0.1:%s/job/qe-holdout\n" % PORT)
code, out = sdlc("holdout", "status", "PAY-100")
check("holdout status: per-scenario results from QE's job, with titles", code == 1 and "| TS-02 |" in out and "FAIL" in out and "| TS-01 |" in out and "PASS" in out
      and "1 of 2 scenario(s) pass" in out, out)
digest = sorted((PROJ / ".sdlc/PAY-100/rounds").glob("holdout-*.md"))
check("holdout digest never shows the hidden test's inputs", "SECRET-INPUT-42" not in out and digest and "SECRET-INPUT-42" not in digest[-1].read_text(), out)
check("holdout: Jenkins token sent to the configured job", any(a[0] == "holdout" and a[2].startswith("Basic") for a in state()["auth_seen"]))

# ---------- Phase 3: co-change, runtime traces, pinned kit version ----------
admin("/__gh/commits", {"commits": [
    {"repo": "acme/payments-service", "sha": "a1", "message": "feat(PAY-100): refund endpoint"},
    {"repo": "acme/orders-service", "sha": "b9", "message": "feat(PAY-10): on a feature branch, not in the search index", "searchable": False},
]})
code, out = sdlc("catalog", "cochange", "PAY-10")
check("cochange: commits the search misses are found in the known repositories' branches; pairs explained by the catalog", code == 0 and "orders-service + payments-service" in out
      and "declared in the catalog" in out and (PROJ / ".sdlc/catalog/cochange.md").exists(), out)
deps = WORK / "jaeger-deps.json"
deps.write_text(json.dumps({"data": [{"parent": "payments-service", "child": "ledger-service", "callCount": 7}]}))
code, out = sdlc("catalog", "import-traces", "--file", str(deps))
check("import-traces: Jaeger dependencies", code == 0 and "1 service-to-service call(s) imported" in out, out)
code, out = sdlc("catalog", "graph")
check("graph shows runtime calls and co-change", code == 0 and "runtime host ledger-service" in out and "Components that change together" in out, out)

d, why = bash("java .agent/Sdlc.java upgrade --from /tmp/kit")
check("agents cannot upgrade the kit", d == "deny", why)
code, out = sdlc("upgrade", "--from", str(TEMPLATE), "--check")
check("upgrade --check: the project matches the kit it was installed from", code == 0 and "up to date" in out, out)
hookfile = PROJ / ".githooks/pre-commit"
original = hookfile.read_text()
hookfile.write_text(original + "\n# local edit\n")
code, out = sdlc("upgrade", "--from", str(TEMPLATE), "--check")
check("upgrade --check: a drifted kit file is reported", code == 1 and "changed  .githooks/pre-commit" in out, out)
code, out = sdlc("upgrade", "--from", str(TEMPLATE), "--version", "r4")
check("upgrade: restores the kit files and pins the version", code == 0 and hookfile.read_text() == original
      and json.loads((PROJ / ".agent/kit.version").read_text())["version"] == "r4", out)
code, out = sdlc("config", "check")
check("config check shows the pinned kit", code == 0 and "Kit: r4" in out, out)
