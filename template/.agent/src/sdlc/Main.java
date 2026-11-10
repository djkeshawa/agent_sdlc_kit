package sdlc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.cli.Analyse;
import sdlc.cli.GitHooks;
import sdlc.cli.HookFormat;
import sdlc.cli.Hooks;
import sdlc.cli.Repos;
import sdlc.cli.Setup;
import sdlc.cli.Status;
import sdlc.core.Console;
import sdlc.core.Ctx;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.guard.Decision;
import sdlc.integrations.Confluence;
import sdlc.integrations.Jira;
import sdlc.selftest.SelfTest;
import sdlc.util.Http;
import sdlc.util.Markdown;
import sdlc.workflow.Approvals;
import sdlc.workflow.Checks;
import sdlc.workflow.Ci;
import sdlc.workflow.Gates;
import sdlc.workflow.IssueTracker;
import sdlc.workflow.Loops;
import sdlc.workflow.MindMap;
import sdlc.workflow.Mistakes;
import sdlc.workflow.Tasks;
import sdlc.workflow.Trace;
import sdlc.workflow.Verify;
import sdlc.workflow.Wiki;
import sdlc.workflow.Workflow;

/** Entry point of the sdlc tool. Run it as: java .agent/Sdlc.java <command> */
public final class Main {
    private static final String USAGE = """
            usage: java .agent/Sdlc.java <command>      (fast: sh .agent/bin/sdlc | .agent\\bin\\sdlc.cmd)

            setup                                one-time per clone: git hooks, ignores, MCP config, self test
            doctor | selftest | config check     check tools and connections | built-in tests | validate sdlc.yml
            config apply                         write agent models into .github/agents, regenerate .vscode/mcp.json
            status                               branch, story, task, scope, loops, PR gate
            analyse                              collect repository facts for /analyse

            docs find|pull <KEY>                 one pager, HLD, test design, LLDs (this and other teams) in Confluence
            docs publish <KEY> test-design|lld --file <draft.md>
            approval line|check <KEY> test-design|lld
            mindmap --file <test-design.md> [--out mindmap.png]
            repos sync                           local read-only copies of related repositories

            tasks create|list <STORY>            Jira sub-tasks from the approved LLD's work breakdown
            task start <TASK> | task finish      scope edits to the task, move Jira
            verify | trace [<STORY>] | check-pr [--title "..."]
            review record <TASK> --file <report> | qa record [<STORY>] --file <report>
            ci status|failures|wait [--pr N]     read the PR's Jenkins and SonarQube results
            sync [<STORY>]                       move Jira tickets to match the work
            retro [<STORY>] [--comment]          mistakes found and fixed, loops, hook refusals

            jira get <KEY> [--comments N] | jira search "<JQL>" | jira comment <KEY> --file F | --text T
            jira transitions <KEY> | jira transition <KEY> "<Status>" | jira link <KEY> <URL>
            confluence get <PAGE_ID> [--storage]  (--storage: raw page markup)
            """;

    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.println(USAGE);
            System.exit(args.length == 0 ? 3 : 0);
        }
        Integer fresh = rerunIfStale(args);
        if (fresh != null) System.exit(fresh);
        boolean hook = args[0].equals("hook") || args[0].equals("git-hook");
        boolean guard = args[0].equals("hook") && args.length > 1 && args[1].equals("pre-tool-use");
        if (guard) refuseWhenSlow(args);
        String stdin = hook && (args[0].equals("hook") || args.length > 1 && args[1].equals("pre-push")) ? readStdin() : "";
        int code;
        try {
            Ctx.init(home());
            code = dispatch(args, stdin);
        } catch (UsageException e) {
            if (guard) {
                code = Hooks.emit(Decision.deny("the guard could not decide (" + e.getMessage() + ")."), hookFormat(args));
            } else {
                System.err.println(e.getMessage());
                code = 3;
            }
        } catch (Http.ApiException e) {
            System.err.println("Remote call failed: " + e.getMessage());
            code = 4;
        } catch (Throwable e) { // also StackOverflowError and OutOfMemoryError: a tool call built to crash the guard is refused
            if (guard) {
                // fail closed: a broken guard must not wave tool calls through
                code = Hooks.emit(Decision.deny("the guard failed (" + e + "). Ask a human to run: java .agent/Sdlc.java selftest"), hookFormat(args));
            } else if (hook) {
                System.err.println("[sdlc] hook error (ignored): " + e);
                code = args[0].equals("git-hook") ? 1 : 0;
            } else {
                System.err.println("error: " + e);
                code = 3;
            }
        }
        System.exit(code);
    }

    /** hook ... --for claude | codex | copilot: the host whose answer format is wanted. */
    private static HookFormat hookFormat(String[] args) {
        return HookFormat.of(Checks.options(args, 1).getOrDefault("for", "copilot"));
    }

    /** Seconds the guard may take for one tool call. The hosts give a hook 30 s and let the call through when it runs out. */
    private static final int GUARD_SECONDS = 20;

    /** A guard that hangs must not let the call through: after GUARD_SECONDS it refuses and stops. */
    private static void refuseWhenSlow(String[] args) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(GUARD_SECONDS * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            Hooks.emit(Decision.deny("the guard could not decide within " + GUARD_SECONDS + " s. Run the command in smaller, plainer steps."), hookFormat(args));
            Runtime.getRuntime().halt(2);
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * The repository this copy of the tool belongs to: the folder that holds .agent. Never the
     * folder a hook payload names. The agent chooses that folder (cd /tmp), and rules read from
     * there would be the rules of another repository, or none.
     */
    private static Path home() {
        try {
            var source = Main.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path agent = Path.of(source.getLocation().toURI()).getParent(); // <repo>/.agent/.build -> <repo>/.agent
                if (agent != null && agent.getParent() != null && String.valueOf(agent.getFileName()).equals(".agent")) return agent.getParent();
            }
        } catch (Exception e) {
            // not started from .agent/.build: use the process directory
        }
        return Path.of("").toAbsolutePath();
    }

    private static String readStdin() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static int dispatch(String[] a, String stdin) throws Exception {
        String c0 = a[0];
        String c1 = a.length > 1 ? a[1] : "";
        Map<String, String> opt = Checks.options(a, 1);
        Workflow w = new Workflow(new Jira(Ctx.cfg), new Confluence(Ctx.cfg), Console.TERMINAL); // the one place that says the tracker is Jira and the wiki is Confluence
        return switch (c0) {
            case "hook" -> Hooks.run(c1, stdin, hookFormat(a), w.sync);
            case "git-hook" -> GitHooks.run(c1, Arrays.copyOfRange(a, 2, a.length), stdin);
            case "setup" -> Setup.setup(Main::selfTest);
            case "doctor" -> Setup.doctor(w.issues, w.wiki);
            case "selftest" -> SelfTest.run();
            case "config" -> c1.equals("apply") ? Setup.configApply() : Setup.configCheck();
            case "status" -> Status.run();
            case "analyse", "analyze" -> Analyse.facts();
            case "verify" -> Verify.run();
            case "trace" -> Trace.run(pos(a, 1));
            case "check-pr" -> Checks.printPolicy(Checks.prPolicy(opt.get("title"), true));
            case "docs" -> switch (c1) {
                case "find" -> w.docs.printFind(need(a, 2, "KEY"));
                case "pull" -> w.docs.pull(need(a, 2, "KEY"));
                case "publish" -> w.docs.publish(need(a, 2, "KEY"), Approvals.fromCli(need(a, 3, "test-design|lld")), file(opt));
                default -> usage();
            };
            case "approval" -> w.approvals.cli(c1, need(a, 2, "KEY"), Approvals.fromCli(need(a, 3, "test-design|lld")));
            case "mindmap" -> MindMap.cli(file(opt), Path.of(opt.getOrDefault("out", file(opt).resolveSibling("mindmap.png").toString())));
            case "repos" -> Repos.sync();
            case "tasks" -> c1.equals("create") ? w.tasks.create(need(a, 2, "STORY")) : w.tasks.list(pos(a, 2));
            case "task" -> c1.equals("start") ? w.tasks.start(need(a, 2, "TASK")) : w.tasks.finish();
            case "review" -> Loops.recordReview(need(a, 2, "TASK"), file(opt));
            case "qa" -> Loops.recordQa(a.length > 2 && !a[2].startsWith("--") ? a[2] : "", file(opt));
            case "ci" -> switch (c1) {
                case "wait" -> Ci.waitFor(w.issues, opt);
                case "failures" -> Ci.failures(w.issues, opt);
                default -> Ci.status(w.issues, opt);
            };
            case "sync" -> w.sync.run(pos(a, 1), false);
            case "retro", "mistakes" -> Mistakes.retro(w.issues, a.length > 1 && !a[1].startsWith("--") ? a[1] : Work.currentStory(), opt.containsKey("comment"));
            case "jira" -> jira(w.issues, c1, a, opt);
            case "confluence" -> {
                Wiki.Page p = w.wiki.page(need(a, 2, "PAGE_ID"));
                System.out.println(opt.containsKey("storage") ? p.storage() // the raw storage format, to debug the Markdown conversion
                        : "# " + p.title() + "  (v" + p.version() + ", " + p.url() + ")\n\n" + Markdown.toMarkdown(p.storage()));
                yield 0;
            }
            default -> usage();
        };
    }

    private static int jira(IssueTracker issues, String c1, String[] a, Map<String, String> opt) throws IOException {
        switch (c1) {
            case "get" -> System.out.println(issues.text(need(a, 2, "KEY"), Integer.parseInt(opt.getOrDefault("comments", "10"))));
            case "search" -> {
                for (IssueTracker.Issue i : issues.search(need(a, 2, "JQL"), Integer.parseInt(opt.getOrDefault("max", "20")))) {
                    System.out.printf("%-12s %-14s %-10s %s%n", i.key(), i.status(), i.type(), i.summary());
                }
            }
            case "comment" -> {
                String body = opt.containsKey("file") ? Files.readString(Gates.guard(null).files.safeInput(opt.get("file"))) : opt.getOrDefault("text", "");
                if (body.isBlank()) throw new UsageException("give --file <path> or --text \"...\"");
                issues.comment(need(a, 2, "KEY"), body, true);
                System.out.println("Comment added to " + a[2]);
            }
            case "transitions" -> {
                for (IssueTracker.Transition t : issues.transitions(need(a, 2, "KEY"))) System.out.println(t.id() + "  " + t.name() + "  ->  " + t.to());
            }
            case "transition" -> {
                if (!issues.transition(need(a, 2, "KEY"), need(a, 3, "STATUS"), true)) throw new UsageException("no transition to '" + a[3] + "' from the current status (see: jira transitions " + a[2] + ")");
                System.out.println(a[2] + " moved to " + a[3]);
            }
            case "link" -> {
                String url = need(a, 3, "URL");
                if (!url.startsWith("http")) throw new UsageException("URL must start with http(s)://");
                issues.link(need(a, 2, "KEY"), url, opt.getOrDefault("title", "Link"));
                System.out.println("Linked " + url);
            }
            default -> {
                return usage();
            }
        }
        return 0;
    }

    private static int selfTest() {
        try {
            return SelfTest.run();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static int usage() {
        System.err.println(USAGE);
        return 3;
    }

    private static String need(String[] a, int i, String what) {
        if (i >= a.length || a[i].startsWith("--") || a[i].isBlank()) throw new UsageException("missing " + what + "\n" + USAGE);
        return a[i];
    }

    private static String pos(String[] a, int i) {
        return i < a.length && !a[i].startsWith("--") ? a[i] : "";
    }

    private static Path file(Map<String, String> opt) {
        String f = opt.get("file");
        if (f == null || f.equals("true")) throw new UsageException("missing --file <path>");
        return Gates.guard(null).files.safeInput(f);
    }

    /**
     * When started from the compiled copy (.agent/.build) and a source file is newer (after a git
     * pull), re-run through the bootstrap, which recompiles. Rules are never out of date.
     */
    private static Integer rerunIfStale(String[] args) {
        if (Boolean.getBoolean("sdlc.bootstrapped")) return null;
        try {
            var source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null) return null;
            Path build = Path.of(source.getLocation().toURI());
            if (!Files.isDirectory(build) || !build.getFileName().toString().equals(".build")) return null;
            Path stamp = build.resolve(".stamp");
            Path src = build.getParent().resolve("src");
            if (!Files.exists(stamp) || !Files.isDirectory(src)) return null;
            long built = Files.getLastModifiedTime(stamp).toMillis();
            boolean stale;
            try (Stream<Path> files = Files.walk(src)) {
                stale = files.filter(p -> p.toString().endsWith(".java")).anyMatch(p -> p.toFile().lastModified() > built);
            }
            if (!stale) return null;
            List<String> cmd = new ArrayList<>(List.of(Ctx.javaBinary(), build.getParent().resolve("Sdlc.java").toString()));
            cmd.addAll(List.of(args));
            return new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (Exception e) {
            return null;
        }
    }
}
