# Multi-component checks, run by flow.py in its namespace (PROJ, sdlc, hook, bash, admin, state, check, write ...).
# The repository is payments-service on feature/PAY-100-refunds after the single-repo flow.

# ---------- Phase 0: scoping eval ----------
admin("/__gh/commits", {"commits": [
    {"repo": "acme/payments-service", "sha": "a1", "message": "feat(PAY-100): refund endpoint"},
    {"repo": "acme/orders-service", "sha": "b1", "message": "feat(PAY-10): show refund status"},
    {"repo": "acme/orders-service", "sha": "b2", "message": "fix(PAY-10): status label"},
    {"repo": "acme/unrelated", "sha": "c1", "message": "chore(PAY-1000): not this epic"},
]})
code, out = sdlc("eval", "collect", "PAY-10")
truth = PROJ / "docs/agent/eval/scoping/truth/PAY-10.yml"
case = PROJ / "docs/agent/eval/scoping/cases/PAY-10.md"
check("eval collect: truth from commits by the epic's and its stories' keys", code == 0 and truth.exists()
      and "components: [orders-service, payments-service]" in truth.read_text() and "unrelated" not in truth.read_text(), out)
check("eval collect: case input has the epic's Jira text", case.exists() and "Refunds epic" in case.read_text(), out)
d, why = hook("read_file", {"filePath": str(truth)})
check("eval: agent cannot read the ground truth", d == "deny", why)
d, why = bash("grep -r components docs/agent/eval/scoping/truth")
check("eval: nor grep it", d == "deny", why)
agent_write(".sdlc/eval/inbox/run1/PAY-10.yml", "components:\n  - name: payments-service\n  - name: orders-service\n")
agent_write(".sdlc/eval/inbox/run2/PAY-10.yml", "components: [payments-service]\n")
code, out = sdlc("eval", "score")
check("eval score: pass@k, pass^k, recall per case", code == 0 and "| pass@k (some run exactly right) | 1.00 |" in out
      and "| pass^k (every run exactly right) | 0.00 |" in out and "| Recall (mean) | 0.75 |" in out
      and (PROJ / ".sdlc/eval/report.md").exists(), out)
