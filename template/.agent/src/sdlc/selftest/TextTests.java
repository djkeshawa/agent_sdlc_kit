package sdlc.selftest;

import static sdlc.selftest.Asserts.check;
import static sdlc.selftest.Asserts.throwsUsage;

import java.util.List;
import java.util.Map;

import sdlc.cli.AgentTools;
import sdlc.cli.HookFormat;
import sdlc.core.Ctx;
import sdlc.guard.FileRules;
import sdlc.guard.Guard;
import sdlc.util.Json;
import sdlc.util.Markdown;
import sdlc.util.ShellWords;
import sdlc.workflow.Approvals;
import sdlc.workflow.Checks;
import sdlc.workflow.Ci;
import sdlc.workflow.Gates;
import sdlc.workflow.Loops;
import sdlc.workflow.MindMap;
import sdlc.workflow.Tasks;

/** Self test: helpers that turn text into text: Markdown to wiki storage, task lists, CI digests, hook output. */
final class TextTests {
    private TextTests() {}

    static void run() {
        String md = "# Title\n\nSome **bold** and `src/main/**` and `src/test/**`.\n\n- [x] done\n- item\n  - nested *em*\n- back\n\n1. one\n2. two\n\n| A | B |\n|---|---|\n| 1 | x \\| y |\n\n```java\nint a = 1; // ]]>\n```\n\n![Mind map](mindmap.png)\n";
        Markdown.Converted c = Markdown.toStorage(md);
        String h = c.storage();
        check("md heading", h.contains("<h1>Title</h1>"));
        check("md code spans untouched", h.contains("<code>src/main/**</code> and <code>src/test/**</code>"));
        check("md nested list", h.contains("<li>item<ul><li>nested <em>em</em></li></ul></li><li>back</li></ul>"));
        check("md checkbox", h.contains("<li>&#9745; done"));
        check("md ordered", h.contains("<ol><li>one</li><li>two</li></ol>"));
        check("md table", h.contains("<th>A</th>") && h.contains("<td>x | y</td>"));
        check("md code macro", h.contains("ac:name=\"code\"") && h.contains("]]]]><![CDATA[>"));
        check("md image attachment", h.contains("ri:filename=\"mindmap.png\"") && c.images().equals(List.of("mindmap.png")));
        String back = Markdown.toMarkdown(h);
        check("storage back to md", back.contains("# Title") && back.contains("- item") && back.contains("```java") && back.contains("int a = 1; // ]]>"));
        check("sonar path with colon key", Ci.componentPath("com.acme:payments:src/A.java", "com.acme:payments").equals("src/A.java")
                && Ci.componentPath("other:src/B.java", "com.acme:payments").equals("src/B.java"));
        check("approval line in code block", Approvals.plain("{noformat}SDLC-APPROVED A-1 lld 1v2 abc{noformat}").equals("SDLC-APPROVED A-1 lld 1v2 abc")
                && Approvals.plain("{code:java}SDLC-APPROVED A-1 lld 1v2 abc{code}").equals("SDLC-APPROVED A-1 lld 1v2 abc") && Approvals.plain("  `SDLC-APPROVED A-1 lld 1v2 abc`").equals("SDLC-APPROVED A-1 lld 1v2 abc"));
        check("sonar measure shapes", Ci.measureValue(Json.parse("{\"periods\":[{\"index\":1,\"value\":\"42.1\"}]}")).equals("42.1")
                && Ci.measureValue(Json.parse("{\"period\":{\"value\":\"7\"}}")).equals("7") && Ci.measureValue(Json.parse("{\"value\":\"3\"}")).equals("3"));
        check("ci picks the error line", Ci.firstError(List.of("07:13 INFO  ------------- Check Quality Gate status", "07:13 ERROR QUALITY GATE STATUS: FAILED")).contains("ERROR QUALITY"));
        check("claude tool mapping", AgentTools.claudeTools(List.of("read", "edit", "execute")).equals(List.of("Read", "Grep", "Glob", "Edit", "Write", "MultiEdit", "Bash"))
                && AgentTools.claudeTools(List.of("read", "atlassian/*")) == null);
        check("agent front matter", AgentTools.fields(AgentTools.frontMatter("---\nname: x\ndescription: 'a: b'\ntools: ['read', 'edit']\n---\nbody\n")[0]).get("tools").equals("['read', 'edit']")
                && AgentTools.listValue("['read', 'edit']").equals(List.of("read", "edit")) && AgentTools.unquote("'a: b'").equals("a: b"));
        check("strict context shape", Json.write(HookFormat.of("codex").context("SessionStart", "x")).equals("{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":\"x\"}}"));
        check("copilot context shape", Json.write(HookFormat.of("copilot").context("SessionStart", "x"))
                .equals("{\"additionalContext\":\"x\",\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":\"x\"}}"));
        check("strict and copilot deny shape", Json.write(HookFormat.STRICT.permission("deny", "r")).equals("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"r\"}}")
                && Json.write(HookFormat.COPILOT.permission("ask", "r")).startsWith("{\"permissionDecision\":\"ask\",\"permissionDecisionReason\":\"r\",\"hookSpecificOutput\":{"));
        check("strict and copilot stop shape", Json.write(HookFormat.STRICT.block("r")).equals("{\"decision\":\"block\",\"reason\":\"r\"}")
                && Json.write(HookFormat.COPILOT.block("r")).contains("\"hookSpecificOutput\":{\"hookEventName\":\"Stop\""));
        Guard one = Gates.guard(null), two = Gates.guard(null);
        one.shell.check("cd src && ls");
        check("guards share no state", one.folders.cd() == null && two.folders.bases().equals(List.of(Ctx.root))
                && one.shell.check("cd .agent").denied() && !two.shell.check("ls").denied());
        check("argv join quotes", ShellWords.join(List.of("bash", "-lc", "git push --force")).equals("bash -lc 'git push --force'"));
        check("ext of dot folder file", Checks.ext(".agent/bin/sdlc").isEmpty() && Checks.ext("src/A.java").equals("java") && Checks.ext(".gitignore").isEmpty());
        String lld = "# LLD\n\n## Work breakdown\n\n```yaml\n# sdlc-tasks\ntasks:\n  - id: T1\n    title: Add refund endpoint\n    scope: [src/main/java/com/acme/refund/**, src/test/java/com/acme/refund/**]\n    scenarios: [TS-01]\n    acceptance:\n      - Given a paid order, when POST /refunds, then 201\n```\n";
        List<Map<String, Object>> tasks = Tasks.parse(lld);
        check("tasks block", tasks.size() == 1 && "T1".equals(tasks.get(0).get("id")));
        check("tasks from storage", Tasks.parseStorage(Markdown.toStorage(lld).storage()).size() == 1);
        check("tasks refuse broad scope", throwsUsage(() -> Tasks.fromYaml("# sdlc-tasks\ntasks:\n  - id: T1\n    title: x\n    scope: [src/**]\n    acceptance: [a]\n")));
        check("tasks refuse missing acceptance", throwsUsage(() -> Tasks.fromYaml("# sdlc-tasks\ntasks:\n  - id: T1\n    title: x\n    scope: [src/main/java/a/**]\n")));
        check("tasks refuse wildcard-first scope", throwsUsage(() -> Tasks.fromYaml("# sdlc-tasks\ntasks:\n  - id: T1\n    title: x\n    scope: [\"**/src/**\"]\n    acceptance: [a]\n")));
        check("tasks refuse the whole java source root", throwsUsage(() -> Tasks.fromYaml("# sdlc-tasks\ntasks:\n  - id: T1\n    title: x\n    scope: [src/main/java/**]\n    acceptance: [a]\n")));
        check("scope breadth", Tasks.tooBroad("{src,lib}/**") && Tasks.tooBroad("../x/**") && !Tasks.tooBroad("src/main/java/com/acme/refund/**") && !Tasks.tooBroad("pom.xml"));
        check("four eyes on approvals", Approvals.sameAccount("70121:abc", "70121:abc") && !Approvals.sameAccount("70121:abc", "") && !Approvals.sameAccount("", "") && !Approvals.sameAccount("a", "b"));
        check("patch targets", FileRules.diffTargets("--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n--- not a file\n+x\n--- /dev/null\n+++ b/src/New.java\n", 1).equals(List.of("src/A.java", "src/New.java")));
        MindMap.Node root = MindMap.parse("# PROJ-1 Refunds\n\n## Refund API\n\n### TS-01 paid order refunded\n### TS-02 unpaid refused\n\n## Security\n\n### TS-03 other user's order refused\n\n## Open questions\n");
        check("mindmap tree", root.children().size() == 2 && root.children().get(0).children().size() == 2);
        check("mindmap mermaid", MindMap.mermaid(root).startsWith("mindmap\n  root((PROJ-1 Refunds))"));
        check("review cites", "JV-07".equals(Loops.cites("F1 src/A.java:12 - swallowed exception - cites JV-07 - evidence: test")));
        check("review location", "src/A.java:12".equals(Loops.location("F1 src/A.java:12 - swallowed exception")));
        check("approval hash ignores whitespace", Approvals.hash("<p>a</p>\n  <p>b</p>").equals(Approvals.hash("<p>a</p><p>b</p>")));
        check("jenkins build url", Ci.buildUrl("https://ci.x.com/job/svc/job/PR-12/5/display/redirect").equals("https://ci.x.com/job/svc/job/PR-12/5/"));
    }
}
