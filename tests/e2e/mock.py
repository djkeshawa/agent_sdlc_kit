"""Mock Jira Cloud, Confluence Cloud, Jenkins, SonarQube and a GitHub PR store for the kit's end-to-end test.
Run: python3 mock.py <port>. Test control goes through /__admin/* and the fake gh through /__gh/*."""
import base64
import json
import re
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

LOCK = threading.Lock()
USERS = {"bot@acme.test": ("bot-1", "sdlc bot")}


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000+0000")


S = {
    "issues": {
        "PAY-10": {"summary": "Refunds epic", "type": "Epic", "status": "To Do", "parent": None, "description": "Customers get refunds."},
        "PAY-100": {"summary": "Refunds for paid orders", "type": "Story", "status": "To Do", "parent": "PAY-10",
                    "description": "As a customer I can get a refund within 30 days of payment."},
    },
    "comments": {},  # key -> list
    "links": [],
    "next_issue": 101,
    "spaces": {"PROD": "1001", "ARCH": "1002", "QE": "1003", "PAYDEV": "1004", "ORDERSDEV": "1005"},
    "pages": {},  # id -> {title, spaceKey, version, storage}
    "next_page": 5000,
    "attachments": [],
    "ci_round": 0,  # which CI round the PR checks report
    "prs": {},
    "auth_seen": [],  # (path, auth header) seen by Jenkins/Sonar endpoints
    "requests": [],
}
next_comment = [9000]


def add_comment(key, body, account, name, edited=False):
    next_comment[0] += 1
    c = {"id": str(next_comment[0]), "body": body, "author": {"accountId": account, "displayName": name},
         "created": now(), "updated": now()}
    if edited:
        c["updated"] = "2099-01-01T00:00:00.000+0000"
    S["comments"].setdefault(key, []).append(c)
    return c


def issue_json(key):
    i = S["issues"][key]
    subs = [{"key": k, "fields": {"summary": v["summary"], "status": {"name": v["status"]}}} for k, v in S["issues"].items() if v.get("parent") == key and v["type"] == "Sub-task"]
    f = {"summary": i["summary"], "status": {"name": i["status"]}, "issuetype": {"name": i["type"]}, "priority": {"name": "Medium"},
         "assignee": None, "reporter": {"displayName": "Product Owner"}, "labels": [], "description": i.get("description"),
         "subtasks": subs, "issuelinks": [], "comment": {"comments": S["comments"].get(key, [])}}
    if i.get("parent"):
        f["parent"] = {"key": i["parent"]}
    return {"key": key, "fields": f}


def page_json(pid):
    p = S["pages"][pid]
    return {"id": pid, "title": p["title"], "spaceId": S["spaces"][p["space"]], "status": "current",
            "version": {"number": p["version"], "authorId": p.get("author", "")}, "body": {"storage": {"value": p["storage"], "representation": "storage"}},
            "_links": {"webui": "/spaces/%s/pages/%s" % (p["space"], pid)}}


def new_page(space, title, storage, author="po-1"):
    S["next_page"] += 1
    pid = str(S["next_page"])
    S["pages"][pid] = {"title": title, "space": space, "version": 1, "storage": storage, "author": author}
    return pid


def checks(round_no):
    j = "http://127.0.0.1:%d/job/payments/job/PR-1/%d/display/redirect" % (PORT, round_no)
    if round_no == 1:
        return [{"name": "continuous-integration/jenkins/pr-merge", "state": "FAILURE", "bucket": "fail", "link": j, "description": "This commit cannot be built"},
                {"name": "SonarCloud Code Analysis", "state": "FAILURE", "bucket": "fail", "link": "https://sonarcloud.io/x", "description": "Quality Gate failed"},
                {"name": "evil-status", "state": "FAILURE", "bucket": "fail", "link": "https://evil.example/job/x/1/", "description": "posted by someone else"}]
    return [{"name": "continuous-integration/jenkins/pr-merge", "state": "SUCCESS", "bucket": "pass", "link": j, "description": "ok"},
            {"name": "SonarCloud Code Analysis", "state": "SUCCESS", "bucket": "pass", "link": "https://sonarcloud.io/x", "description": "Quality Gate passed"}]


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, obj, code=200, text=None):
        body = (text if text is not None else json.dumps(obj)).encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain" if text is not None else "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def user(self):
        a = self.headers.get("Authorization", "")
        if a.startswith("Basic "):
            u = base64.b64decode(a[6:]).decode().split(":")[0]
            return USERS.get(u, ("unknown", u))
        return ("anon", "anon")

    def handle_any(self, method):
        u = urlparse(self.path)
        path, q = u.path, {k: v[0] for k, v in parse_qs(u.query).items()}
        raw = self.body()
        with LOCK:
            S["requests"].append(method + " " + path)
            try:
                return self.route(method, path, q, raw)
            except KeyError as e:
                return self.send({"errorMessages": ["not found: %s" % e]}, 404)

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")

    def do_PUT(self):
        self.handle_any("PUT")

    def route(self, m, path, q, raw):
        data = json.loads(raw) if raw and raw[:1] in (b"{", b"[") else {}
        # ---------------- admin + fake gh ----------------
        if path == "/__admin/state":
            return self.send({k: v for k, v in S.items() if k != "requests"} | {"requests": S["requests"][-400:]})
        if path == "/__admin/comment":
            return self.send(add_comment(data["key"], data["body"], data["accountId"], data["name"], data.get("edited", False)))
        if path == "/__admin/page":
            return self.send({"id": new_page(data["space"], data["title"], data["storage"])})
        if path == "/__admin/edit":
            p = S["pages"][data["id"]]
            p["version"] += 1
            p["storage"] += data["append"]
            p["author"] = data.get("author", "editor-1")
            return self.send({"version": p["version"]})
        if path == "/__admin/ci":
            S["ci_round"] = data.get("round", S["ci_round"])
            S["ci_auto"] = data.get("auto", False)  # auto: each new pushed head is the next CI round
            S["ci_heads"] = []
            return self.send({})
        if path == "/__gh/pr":
            if m == "POST":
                S["prs"][data["branch"]] = {"number": 1, "url": "https://github.test/acme/payments/pull/1", "state": "OPEN", "title": data["title"], "headRefOid": data["head"], "isDraft": False}
            elif m == "PUT":
                S["prs"][data["branch"]].update(data["fields"])
            return self.send(S["prs"].get(q.get("branch", data.get("branch", "")), {}))
        if path == "/__gh/checks":
            head = q.get("head", "")
            if S.get("ci_auto") and head and S["prs"]:
                if head not in S["ci_heads"]:
                    S["ci_heads"].append(head)
                S["ci_round"] = S["ci_heads"].index(head) + 1
            return self.send(checks(S["ci_round"]) if S["ci_round"] else [])
        # ---------------- Jira ----------------
        if path == "/rest/api/2/myself":
            acc, name = self.user()
            return self.send({"accountId": acc, "displayName": name})
        mt = re.match(r"^/rest/api/2/issue/([^/]+)(/(comment|transitions|remotelink))?$", path)
        if mt:
            key, sub = unquote(mt.group(1)), mt.group(3)
            if key not in S["issues"]:
                return self.send({"errorMessages": ["Issue does not exist"]}, 404)
            if sub is None:
                return self.send(issue_json(key))
            if sub == "comment" and m == "GET":
                cs = S["comments"].get(key, [])
                start = int(q.get("startAt", 0))
                return self.send({"comments": cs[start:start + 100], "total": len(cs), "startAt": start})
            if sub == "comment":
                acc, name = self.user()
                return self.send(add_comment(key, data["body"], acc, name), 201)
            if sub == "transitions" and m == "GET":
                return self.send({"transitions": [{"id": str(i), "name": n, "to": {"name": n}} for i, n in enumerate(["To Do", "In Progress", "In Review", "Done"], 11)]})
            if sub == "transitions":
                tid = int(data["transition"]["id"])
                S["issues"][key]["status"] = ["To Do", "In Progress", "In Review", "Done"][tid - 11]
                return self.send(None, 204, text="")
            if sub == "remotelink":
                S["links"].append({"key": key, "url": data["object"]["url"]})
                return self.send({"id": 1}, 201)
        if path == "/rest/api/2/issue" and m == "POST":
            f = data["fields"]
            key = "PAY-%d" % S["next_issue"]
            S["next_issue"] += 1
            S["issues"][key] = {"summary": f["summary"], "type": "Sub-task", "status": "To Do", "parent": f["parent"]["key"], "description": f.get("description")}
            return self.send({"id": key, "key": key}, 201)
        if path in ("/rest/api/3/search/jql", "/rest/api/2/search"):
            return self.send({"issues": [issue_json(k) for k in S["issues"]]})
        # ---------------- Confluence ----------------
        if path == "/wiki/rest/api/search":
            mt2 = re.search(r'title ~ "\\"([^\\"]+)\\""', q["cql"])
            if not mt2:
                return self.send({"results": []})
            want = mt2.group(1)
            res = [{"content": {"id": pid, "title": p["title"], "space": {"key": p["space"]}, "version": {"number": p["version"]},
                                "_links": {"webui": "/spaces/%s/pages/%s" % (p["space"], pid)}}} for pid, p in S["pages"].items() if want in p["title"]]
            return self.send({"results": res})
        if path == "/wiki/api/v2/spaces":
            k = q["keys"]
            return self.send({"results": [{"id": S["spaces"][k], "key": k}] if k in S["spaces"] else []})
        ms = re.match(r"^/wiki/api/v2/spaces/(\d+)$", path)
        if ms:
            key = next(k for k, v in S["spaces"].items() if v == ms.group(1))
            return self.send({"id": ms.group(1), "key": key})
        if path == "/wiki/api/v2/pages" and m == "GET":
            space = next(k for k, v in S["spaces"].items() if v == q["space-id"])
            res = [page_json(pid) for pid, p in S["pages"].items() if p["space"] == space and p["title"] == q["title"]]
            return self.send({"results": res})
        if path == "/wiki/api/v2/pages" and m == "POST":
            space = next(k for k, v in S["spaces"].items() if v == data["spaceId"])
            return self.send(page_json(new_page(space, data["title"], data["body"]["value"], self.user()[0])))
        mp = re.match(r"^/wiki/api/v2/pages/(\d+)$", path)
        if mp and m == "GET":
            return self.send(page_json(mp.group(1)))
        if mp and m == "PUT":
            p = S["pages"][mp.group(1)]
            assert data["version"]["number"] == p["version"] + 1, "version conflict"
            p.update(version=data["version"]["number"], storage=data["body"]["value"], title=data["title"], author=self.user()[0])
            return self.send(page_json(mp.group(1)))
        ma = re.match(r"^/wiki/rest/api/content/(\d+)/child/attachment$", path)
        if ma:
            name = re.search(rb'filename="([^"]+)"', raw).group(1).decode()
            S["attachments"].append({"page": ma.group(1), "file": name, "bytes": len(raw)})
            return self.send({"results": [{"id": "att1", "title": name}]})
        # ---------------- Jenkins ----------------
        if path == "/api/json":
            return self.send({"mode": "NORMAL"})
        if path == "/api/authentication/validate":
            return self.send({"valid": True})
        mj = re.match(r"^/job/payments/job/PR-1/(\d+)/(testReport/api/json|consoleText)$", path)
        if mj:
            S["auth_seen"].append(["jenkins", path, self.headers.get("Authorization", "")[:12]])
            if mj.group(1) == "1" and mj.group(2) == "consoleText":
                return self.send(None, text="[Pipeline] { (Build and test)\n[INFO] Tests run: 4, Failures: 0\n[Pipeline] { (SonarQube)\n[ERROR] Quality Gate failed: new_coverage 30.0 < 80\nERROR: script returned exit code 1\n")
            if mj.group(2) == "consoleText":
                return self.send(None, text="[Pipeline] { (Build and test)\nFinished: SUCCESS\n")
            return self.send(None, 404, text="")
        # ---------------- SonarQube ----------------
        if path.startswith("/api/"):
            S["auth_seen"].append(["sonar", path, self.headers.get("Authorization", "")[:12]])
            red = S["ci_round"] == 1
            if path == "/api/qualitygates/project_status":
                return self.send({"projectStatus": {"status": "ERROR" if red else "OK", "conditions": [
                    {"status": "ERROR" if red else "OK", "metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "30.0" if red else "92.0"}]}})
            if path == "/api/issues/search":
                return self.send({"issues": [] if S.get("ci_auto") else ([{"component": "acme:payments:src/main/java/com/acme/payments/refund/api/RefundController.java", "line": 21,
                                              "severity": "MAJOR", "type": "CODE_SMELL", "rule": "java:S1186", "message": "Add a nested comment explaining why this method is empty"}] if red else [])})
            if path == "/api/measures/component_tree":
                return self.send({"components": ([{"path": "src/main/java/com/acme/payments/discount/service/DiscountService.java",
                                                  "measures": [{"metric": "new_uncovered_lines", "period": {"value": "4"}}, {"metric": "new_coverage", "period": {"value": "30.0"}}]}] if S.get("ci_auto") else
                                                  [{"path": "src/main/java/com/acme/payments/refund/api/RefundController.java",
                                                  "measures": [{"metric": "new_uncovered_lines", "period": {"value": "11"}}, {"metric": "new_coverage", "period": {"value": "30.0"}}]}]) if red else []})
        return self.send({"errorMessages": ["no mock for %s %s" % (m, path)]}, 404)


if __name__ == "__main__":
    PORT = int(sys.argv[1])
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
