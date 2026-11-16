#!/usr/bin/env python3
"""The kit against a REAL Jira and Confluence: the part of the flow that the mock (flow.py) cannot prove.

    python3 tests/e2e/live.py            (from the kit's root; `sh verify/run.sh live` runs it)

It makes one story and its pages in a test project and a test space, runs the tool against them, and
looks at what the services then hold through their own REST API. Never point it at a project or a
space that people work in. It is not part of the default layers and never runs in CI.

Settings, all from the environment (README.md here has an example):
  SDLC_LIVE_SITE     https://yourtest.atlassian.net
  SDLC_LIVE_PROJECT  the key of the Jira test project, e.g. SKE
  SDLC_LIVE_SPACE    the key of the Confluence test space
  SDLC_LIVE_PARENT   the id of the page that the test pages go under
  SDLC_LIVE_ENV      a file with JIRA_USER and JIRA_TOKEN of the bot account (the format of .agent/local.env)
  SDLC_LIVE_ROLES    optional: a file with QELEAD_USER, QELEAD_TOKEN, TECHLEAD_USER, TECHLEAD_TOKEN. With
                     it the run also approves as those two people and goes on to the Jira sub-tasks.
                     Without it the run stops at the gates and checks that they stay closed.

Exit code: 0 every check passed, 1 a check failed, 2 the settings are missing or the services refuse
the bot (nothing was tested). Options: --keep leaves the test project on disk (without its secrets);
--template DIR tests another copy of the template."""
import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import NoReturn

HERE = Path(__file__).resolve().parent
LABEL = "sdlc-live-run"
RESULTS = []
SECRETS = []


def clean(text):
    for s in SECRETS:
        text = text.replace(s, "***")
    return text


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok or not detail else "\n      " + clean(str(detail)).strip().replace("\n", "\n      ")[:1500]))


def stop(why) -> NoReturn:
    print("live: " + why, file=sys.stderr)
    sys.exit(2)


def read_env(path, wanted):
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError as e:
        stop("cannot read %s (%s)" % (path, e.strerror))
    out = {}
    for line in lines:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    missing = [k for k in wanted if not out.get(k)]
    if missing:
        stop("%s has no value for %s" % (path, ", ".join(missing)))
    for k, v in out.items():
        if k.endswith("TOKEN") and v:
            SECRETS.append(v)
            SECRETS.append(base64.b64encode(v.encode()).decode())
    return out


class Account:
    """One person or bot on the site. Its login goes to that site only, and a redirect is not followed."""

    def __init__(self, site, user, token):
        self.site = site
        self.auth = "Basic " + base64.b64encode(("%s:%s" % (user, token)).encode()).decode()
        SECRETS.append(self.auth[6:])
        self.id = self.name = ""

    def call(self, method, path, data=None, ok=(200, 201, 202, 204)):
        headers = {"Authorization": self.auth, "Accept": "application/json", "X-Atlassian-Token": "no-check"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.site + path, method=method, headers=headers, data=json.dumps(data).encode() if data is not None else None)
        try:
            with OPENER.open(req, timeout=60) as r:
                code, body = r.status, r.read()
        except urllib.error.HTTPError as e:
            code, body = e.code, e.read()
        if code not in ok:
            raise RuntimeError("%s %s -> %s %s" % (method, path.split("?")[0], code, clean(body.decode("utf-8", "replace"))[:300]))
        try:
            return json.loads(body) if body else {}
        except ValueError:
            return {}

    def login(self):
        me = self.call("GET", "/rest/api/2/myself")
        self.id, self.name = me.get("accountId") or me.get("name") or "", me.get("displayName", "")
        return self


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def settings():
    names = ["SDLC_LIVE_SITE", "SDLC_LIVE_PROJECT", "SDLC_LIVE_SPACE", "SDLC_LIVE_PARENT", "SDLC_LIVE_ENV"]
    missing = [n for n in names if not os.environ.get(n, "").strip()]
    if missing:
        stop("not set: %s. See tests/e2e/README.md, part 'The live run'." % ", ".join(missing))
    s = {n[len("SDLC_LIVE_"):].lower(): os.environ[n].strip() for n in names}
    s["site"] = s["site"].rstrip("/")
    if not re.fullmatch(r"https://[A-Za-z0-9.-]+(:[0-9]+)?", s["site"]):
        stop("SDLC_LIVE_SITE must be https://host with nothing after it")
    if not re.fullmatch(r"[A-Z][A-Z0-9]+", s["project"]) or not re.fullmatch(r"[A-Za-z0-9~]+", s["space"]) or not re.fullmatch(r"[0-9]+", s["parent"]):
        stop("SDLC_LIVE_PROJECT is a Jira key (PAY), SDLC_LIVE_SPACE a space key, SDLC_LIVE_PARENT a page id of digits")
    s["roles"] = os.environ.get("SDLC_LIVE_ROLES", "").strip()
    return s


def until(what, seconds=90, every=3):
    """The search of Confluence and the comments of Jira lag behind a write: ask again for a while."""
    end = time.time() + seconds
    while True:
        got = what()
        if got or time.time() > end:
            return got
        time.sleep(every)


def main():
    ap = argparse.ArgumentParser(description="The kit against a real Jira and Confluence test project.")
    ap.add_argument("--template", default=str(HERE.parent.parent / "template"))
    ap.add_argument("--keep", action="store_true", help="keep the test project (without its secrets)")
    args = ap.parse_args()
    if sys.platform.startswith("win"):
        stop("this test drives the kit through sh; run it on Linux, macOS or WSL")
    template = Path(args.template).resolve()
    if not (template / ".agent" / "Sdlc.java").is_file():
        stop("no template at %s" % template)
    s = settings()
    env_file = read_env(s["env"], ["JIRA_USER", "JIRA_TOKEN"])
    site, project, space = s["site"], s["project"], s["space"]
    try:
        bot = Account(site, env_file["JIRA_USER"], env_file["JIRA_TOKEN"]).login()
        wiki_bot = Account(site, env_file.get("CONFLUENCE_USER") or env_file["JIRA_USER"], env_file.get("CONFLUENCE_TOKEN") or env_file["JIRA_TOKEN"])
        types = bot.call("GET", "/rest/api/2/issue/createmeta/%s/issuetypes" % project)
        types = types.get("issueTypes") or types.get("values") or []
        story = next((t["name"] for t in types if t["name"] == "Story"), None) or next((t["name"] for t in types if not t.get("subtask") and t.get("hierarchyLevel", 0) == 0), None)
        sub = next((t["name"] for t in types if t.get("subtask")), None)
        parent = wiki_bot.call("GET", "/wiki/rest/api/content/%s?expand=space" % s["parent"])
    except RuntimeError as e:
        stop("the site refuses the bot account, or the project or the parent page is not there: %s" % e)
    if not story or not sub:
        stop("project %s needs a story type and a sub-task type" % project)
    if (parent.get("space") or {}).get("key") != space:
        stop("page %s is not in space %s" % (s["parent"], space))
    leads = {}
    if s["roles"]:
        roles = read_env(s["roles"], ["QELEAD_USER", "QELEAD_TOKEN", "TECHLEAD_USER", "TECHLEAD_TOKEN"])
        try:
            leads = {r: Account(site, roles[r.upper() + "_USER"], roles[r.upper() + "_TOKEN"]).login() for r in ("qelead", "techlead")}
        except RuntimeError as e:
            stop("the site refuses a lead account of SDLC_LIVE_ROLES: %s" % e)
        if len({bot.id, leads["qelead"].id, leads["techlead"].id}) != 3:
            stop("the bot, the QE lead and the tech lead must be three accounts")

    run_id = time.strftime("%Y%m%d-%H%M%S")
    work = Path(tempfile.mkdtemp(prefix="sdlc-live-"))
    proj = work / "payments"
    env = {k: v for k, v in os.environ.items() if not k.startswith("SDLC_LIVE_") and k not in ("SDLC_BASE_BRANCH", "GITHUB_BASE_REF", "CHANGE_TARGET")}
    env.update(PATH=str(HERE / "bin") + os.pathsep + os.environ["PATH"], GIT_AUTHOR_NAME="Dev", GIT_AUTHOR_EMAIL="dev@acme.test",
               GIT_COMMITTER_NAME="Dev", GIT_COMMITTER_EMAIL="dev@acme.test")
    made = {"issues": [], "pages": []}

    def run(*cmd, inp=None):
        r = subprocess.run(list(cmd), cwd=proj, env=env, input=inp, capture_output=True, text=True, timeout=900)
        return r.returncode, r.stdout + r.stderr

    def sdlc(*a):
        return run("sh", ".agent/bin/sdlc", *a)

    def write(rel, text):
        p = proj / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def page_by_title(title):
        q = urllib.parse.urlencode({"spaceKey": space, "title": title, "expand": "version,ancestors,body.storage"})
        found = wiki_bot.call("GET", "/wiki/rest/api/content?" + q).get("results", [])
        return found[0] if found else None

    def comments(key):
        return bot.call("GET", "/rest/api/2/issue/%s/comment?maxResults=100" % key).get("comments", [])

    def issue(key, fields="status,parent,issuetype,labels,summary"):
        return bot.call("GET", "/rest/api/2/issue/%s?fields=%s" % (key, fields))["fields"]

    def line_of(key, kind):
        return sdlc("approval", "line", key, kind)[1].strip().splitlines()[-1]

    def approve(who, key, kind):
        who.call("POST", "/rest/api/2/issue/%s/comment" % key, {"body": line_of(key, kind)})

    def approved(key, kind, want):
        # a comment shows up in the list a moment after it is posted
        got = until(lambda: (lambda r: r if (r[0] == 0) == want else None)(sdlc("approval", "check", key, kind)), seconds=30)
        return got or sdlc("approval", "check", key, kind)

    try:
        # A parent page of this run alone. The pages of the run must land under it: under the page of
        # SDLC_LIVE_PARENT they could be there by chance, as a page with no parent goes to the top of the space.
        folder = wiki_bot.call("POST", "/wiki/rest/api/content", {"type": "page", "title": "Live run %s" % run_id, "space": {"key": space}, "ancestors": [{"id": s["parent"]}],
                               "body": {"storage": {"representation": "storage", "value": "<p>Made by tests/e2e/live.py of the Agentic SDLC kit. Safe to delete.</p>"}}})["id"]
        made["pages"].append(folder)
        proj.mkdir(parents=True)
        run("git", "init", "-q", "-b", "develop")
        write("README.md", "# payments-service\n")
        write("pom.xml", "<project><!-- stand-in --></project>\n")
        run("git", "add", "-A")
        run("git", "commit", "-q", "-m", "chore(%s-1): initial service" % project)
        for item in template.iterdir():
            if item.is_dir():
                shutil.copytree(item, proj / item.name, ignore=shutil.ignore_patterns(".build"))
            else:
                shutil.copy2(item, proj / item.name)
        yml = (proj / ".agent/sdlc.yml").read_text()
        approvers = ("    testDesign: [\"%s\"]\n    lld: [\"%s\"]\n" % (leads["qelead"].id, leads["techlead"].id)) if leads else "    testDesign: []\n    lld: []\n"
        for old, new in [("url: https://yourcompany.atlassian.net\n", "url: %s\n" % site), ("projectKeys: [PAY]", "projectKeys: [%s]" % project),
                         ("    task: Sub-task ", "    task: %s " % sub), ("    story: Story ", "    story: %s " % story),
                         ("url: https://yourcompany.atlassian.net/wiki", "url: %s/wiki" % site),
                         ("searchSpaces: [PROD, ARCH, QE, PAYDEV, ORDERSDEV]", "searchSpaces: [%s]" % space),
                         ("      space: QE\n      parentId: \"\"", "      space: %s\n      parentId: \"%s\"" % (space, folder)),
                         ("      space: PAYDEV\n      parentId: \"\"", "      space: %s\n      parentId: \"%s\"" % (space, folder)),
                         ("      space: ARCH\n      parentId: \"\"", "      space: %s\n      parentId: \"%s\"" % (space, folder)),
                         ("url: https://jenkins.yourcompany.com", "url: \"\""), ("url: https://sonar.yourcompany.com", "url: \"\""),
                         ("    testDesign: []\n    lld: []\n", approvers), ("  botAccounts: []", "  botAccounts: [\"%s\"]" % bot.id)]:
            if old not in yml:
                stop("the template's sdlc.yml has no line '%s': live.py must follow the template" % old.strip())
            yml = yml.replace(old, new, 1)
        (proj / ".agent/sdlc.yml").write_text(yml)
        code, out = run("java", ".agent/Sdlc.java", "setup")
        check("setup in the test project", code == 0 and "SELFTEST PASSED" in out, out[-600:])
        secret = proj / ".agent/local.env"
        secret.write_text("".join("%s=%s\n" % (k, v) for k, v in env_file.items() if k in ("JIRA_USER", "JIRA_TOKEN", "CONFLUENCE_USER", "CONFLUENCE_TOKEN") and v))
        secret.chmod(0o600)
        code, out = sdlc("config", "check")
        check("config check with the real site", code == 0, out)
        code, out = sdlc("doctor")
        check("doctor logs in to the real Jira as the bot", any(ln.startswith("OK") and "Jira login" in ln and bot.id in ln for ln in out.splitlines()), out)
        check("doctor reaches the real Confluence", any(ln.startswith("OK") and "Confluence" in ln for ln in out.splitlines()), out)

        created = bot.call("POST", "/rest/api/2/issue", {"fields": {
            "project": {"key": project}, "issuetype": {"name": story}, "labels": [LABEL],
            "summary": "[live run %s] Refunds for paid orders" % run_id,
            "description": "Made by tests/e2e/live.py of the Agentic SDLC kit. Safe to delete.\n\nCustomers can refund a paid order within 30 days."}})
        key = created["key"]
        made["issues"].append(key)
        print("      story %s, run %s" % (key, run_id))
        one = wiki_bot.call("POST", "/wiki/rest/api/content", {"type": "page", "title": "%s Refunds - One Pager" % key, "space": {"key": space},
                            "ancestors": [{"id": folder}], "body": {"storage": {"representation": "storage",
                            "value": "<h2>Goal</h2><p>Customers can refund a paid order within 30 days.</p><p>Made by live run %s. Safe to delete.</p>" % run_id}}})
        made["pages"].append(one["id"])

        def pulled():
            code, out = sdlc("docs", "pull", key)
            src = proj / (".sdlc/%s/sources" % key)
            names = sorted(p.name for p in src.iterdir()) if src.exists() else []
            return (code, out, names) if code == 0 and any(n.startswith("onePager-") for n in names) else None

        got = until(pulled, seconds=180, every=10)
        check("docs pull: the story from Jira and the one pager found by the search of Confluence", got and ("jira-%s.md" % key) in got[2], "not found in 180 s" if not got else got[1])
        text = (proj / (".sdlc/%s/sources/jira-%s.md" % (key, key))).read_text() if got else ""
        check("the pulled story holds the real summary and description", "live run %s" % run_id in text and "within 30 days" in text, text[:600])
        text = next((p.read_text() for p in (proj / (".sdlc/%s/sources" % key)).glob("onePager-*")), "") if got else ""
        check("the pulled one pager holds the real page text", "refund a paid order within 30 days" in text, text[:600])

        write(".sdlc/%s/drafts/test-design.md" % key, TEST_DESIGN.replace("KEY", key))
        code, out = sdlc("docs", "publish", key, "test-design", "--file", ".sdlc/%s/drafts/test-design.md" % key)
        td = page_by_title("%s - Test scenarios" % key)
        if td:
            made["pages"].append(td["id"])
        check("test design published as a real page, directly under the parent page of the run", code == 0 and td and [a["id"] for a in td.get("ancestors", [])][-1:] == [folder],
              out + " ancestors: " + json.dumps([a["id"] for a in (td or {}).get("ancestors", [])]))
        body = (td or {}).get("body", {}).get("storage", {}).get("value", "")
        check("the page holds the scenarios and the requirement table", "TS-02" in body and "<table" in body and "R-01" in body, body[:600])
        att = wiki_bot.call("GET", "/wiki/rest/api/content/%s/child/attachment" % td["id"]).get("results", []) if td else []
        check("the mind map is attached as a real image", any(a["title"] == "mindmap.png" and int(a.get("extensions", {}).get("fileSize", 0)) > 500 for a in att),
              json.dumps([(a["title"], a.get("extensions", {}).get("fileSize")) for a in att]))
        check("a draft image that points at local.env is not uploaded", not any("local.env" in a["title"] for a in att) and "not uploading" in out, out)
        said = until(lambda: [c for c in comments(key) if "SDLC-APPROVED %s test-design" % key in c["body"]], seconds=30)
        check("the bot comments on the real story with the approval line", said and said[0]["author"].get("accountId") == bot.id, json.dumps([c["body"][:120] for c in comments(key)]))
        code, out = sdlc("approval", "check", key, "test-design")
        check("not approved: the comment that the tool posts for the bot is not an approval", code != 0 and out.lstrip().startswith("NOT APPROVED: no approval comment yet"), out)
        bot.call("POST", "/rest/api/2/issue/%s/comment" % key, {"body": line_of(key, "test-design")})
        code, out = approved(key, "test-design", False)
        check("the exact approval line posted by the bot account does not count", code != 0 and "bot account" in out, out)

        write(".sdlc/%s/drafts/lld.md" % key, LLD.replace("KEY", key))
        code, out = sdlc("docs", "publish", key, "lld", "--file", ".sdlc/%s/drafts/lld.md" % key)
        lld = page_by_title("%s - LLD - payments" % key)
        if lld:
            made["pages"].append(lld["id"])
        check("LLD published as a real page, directly under the parent page of the run", code == 0 and lld and [a["id"] for a in lld.get("ancestors", [])][-1:] == [folder], out)
        run("git", "switch", "-q", "-c", "feature/%s-refunds" % key)
        code, out = sdlc("tasks", "create", key)
        subs = issue(key, "subtasks").get("subtasks", [])
        check("tasks create refused while the LLD is not approved, and Jira has no sub-task", code != 0 and not subs, out + json.dumps([x["key"] for x in subs]))

        if leads:
            approve(leads["techlead"], key, "test-design")
            code, out = approved(key, "test-design", False)
            check("approval of the test design by the tech lead does not count", code != 0 and "not in gates.approvers" in out, out)
            approve(leads["qelead"], key, "test-design")
            code, out = approved(key, "test-design", True)
            check("QE lead approval counts", code == 0 and "APPROVED" in out, out)
            approve(leads["techlead"], key, "lld")
            code, out = approved(key, "lld", True)
            check("tech lead approval of the LLD counts", code == 0, out)
            now = page_by_title("%s - LLD - payments" % key)
            wiki_bot.call("PUT", "/wiki/rest/api/content/%s" % now["id"], {"type": "page", "title": now["title"], "version": {"number": now["version"]["number"] + 1},
                          "body": {"storage": {"representation": "storage", "value": now["body"]["storage"]["value"] + "<p>late edit in Confluence</p>"}}})
            code, out = approved(key, "lld", False)
            check("a real edit of the page after the approval voids it", code != 0 and "another version" in out, out)
            approve(leads["techlead"], key, "lld")
            code, out = approved(key, "lld", True)
            check("approved again after the edit", code == 0, out)
            code, out = sdlc("tasks", "create", key)
            subs = until(lambda: (lambda x: x if len(x) == 2 else None)(issue(key, "subtasks").get("subtasks", [])), seconds=30) or []
            made["issues"] += [x["key"] for x in subs]
            check("tasks create: two real sub-tasks under the story", code == 0 and len(subs) == 2 and all(x["fields"]["issuetype"]["subtask"] for x in subs), out)
            if len(subs) == 2:
                t1, t2 = sorted((x["key"] for x in subs), key=lambda k: int(k.split("-")[1]))
                code, out = sdlc("task", "start", t2)
                check("task start refused while its dependency is not done", code != 0, out)
                code, out = sdlc("task", "start", t1)
                check("task start moves the real sub-task and the story to In Progress", code == 0 and issue(t1)["status"]["name"] == "In Progress" and issue(key)["status"]["name"] == "In Progress",
                      out + " | " + issue(t1)["status"]["name"] + " | " + issue(key)["status"]["name"])
        else:
            print("      SDLC_LIVE_ROLES is not set: the approvals by people and the sub-tasks were not tested")
    finally:
        left = tidy(bot, wiki_bot, made)
        (proj / ".agent" / "local.env").unlink(missing_ok=True)
        passed = sum(1 for _, ok in RESULTS if ok)
        if args.keep or passed != len(RESULTS):
            print("test project kept at %s (its local.env is removed)" % proj)
        else:
            shutil.rmtree(work, ignore_errors=True)
    if left:
        print("left on the site, for a person to delete: " + ", ".join(left))
    print("\n%d/%d live checks passed%s" % (passed, len(RESULTS), "" if leads else " (gates only: no SDLC_LIVE_ROLES)"))
    sys.exit(0 if RESULTS and passed == len(RESULTS) else 1)


def tidy(bot, wiki_bot, made):
    """Takes away what the run made, as far as the bot may. Returns what stays."""
    left = []
    for pid in reversed(made["pages"]):
        try:
            wiki_bot.call("DELETE", "/wiki/rest/api/content/%s" % pid)
        except RuntimeError:
            try:
                wiki_bot.call("POST", "/wiki/rest/api/content/archive", {"pages": [{"id": int(pid)}]})
                left.append("page %s (archived)" % pid)
            except RuntimeError:
                left.append("page %s" % pid)
    for key in sorted(made["issues"], key=lambda k: -int(k.split("-")[1])):
        try:
            bot.call("DELETE", "/rest/api/2/issue/%s" % key)
        except RuntimeError:
            left.append(key)
    return left


TEST_DESIGN = """# KEY Refunds - Test scenarios

## Requirements

| Id | Requirement | Source |
| --- | --- | --- |
| R-01 | "Customers can refund a paid order within 30 days" | One pager |

## Refund window

### TS-01 Paid order inside the window is refunded

- Requirements: R-01
- Level: integration
- Priority: P1
- Type: functional
- Given an order paid 3 days ago
- When a refund is requested
- Then the refund is accepted

### TS-02 Refund on day 31 is refused

- Requirements: R-01
- Level: unit
- Priority: P1
- Type: boundary
- Given an order paid 31 days ago
- When a refund is requested
- Then REFUND_WINDOW_CLOSED

![sneaky](../../../.agent/local.env)
"""

LLD = """# KEY - LLD - payments

## 9. Work breakdown

```yaml
# sdlc-tasks
tasks:
  - id: T1
    title: Refund window policy
    scope:
      - src/main/java/com/acme/payments/refund/policy/**
      - src/test/java/com/acme/payments/refund/policy/**
    scenarios: [TS-02]
    acceptance:
      - Given an order paid 31 days ago, when checked, then REFUND_WINDOW_CLOSED (TS-02)
  - id: T2
    title: Refund API
    dependsOn: [T1]
    scope:
      - src/main/java/com/acme/payments/refund/api/**
      - src/test/java/com/acme/payments/refund/api/**
    scenarios: [TS-01]
    acceptance:
      - Given a paid order within 30 days, when refunded, then accepted (TS-01)
```
"""

if __name__ == "__main__":
    main()
