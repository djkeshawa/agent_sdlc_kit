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

/** Entry point of the sdlc tool. Run it as: java .agent/Sdlc.java <command> */
public final class Main {
    static final class UsageException extends RuntimeException {
        UsageException(String m) {
            super(m);
        }
    }

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
        String stdin = hook && (args[0].equals("hook") || args.length > 1 && args[1].equals("pre-push")) ? readStdin() : "";
        int code;
        try {
            Ctx.init(startDir(args, stdin));
            code = dispatch(args, stdin);
        } catch (UsageException e) {
            if (args[0].equals("hook") && args.length > 1 && args[1].equals("pre-tool-use")) {
                code = Hooks.emit(Decision.deny("the guard could not decide (" + e.getMessage() + ")."));
            } else {
                System.err.println(e.getMessage());
                code = 3;
            }
        } catch (Http.ApiException e) {
            System.err.println("Remote call failed: " + e.getMessage());
            code = 4;
        } catch (Exception e) {
            if (args[0].equals("hook") && args.length > 1 && args[1].equals("pre-tool-use")) {
                // fail closed: a broken guard must not wave tool calls through
                code = Hooks.emit(Decision.deny("the guard failed (" + e + "). Ask a human to run: java .agent/Sdlc.java selftest"));
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

    private static Path startDir(String[] args, String stdin) {
        if (args[0].equals("hook") && !stdin.isBlank()) {
            try {
                if (Json.obj(Json.parse(stdin)).get("cwd") instanceof String s && !s.isBlank()) return Path.of(s);
            } catch (RuntimeException ignored) {
                // use the process directory
            }
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
        return switch (c0) {
            case "hook" -> Hooks.run(c1, stdin, opt.getOrDefault("for", "copilot"));
            case "git-hook" -> GitHooks.run(c1, Arrays.copyOfRange(a, 2, a.length), stdin);
            case "setup" -> Setup.setup();
            case "doctor" -> Setup.doctor();
            case "selftest" -> SelfTest.run();
            case "config" -> c1.equals("apply") ? Setup.configApply() : Setup.configCheck();
            case "status" -> Status.run();
            case "analyse", "analyze" -> Analyse.facts();
            case "verify" -> Verify.run();
            case "trace" -> Trace.run(pos(a, 1));
            case "check-pr" -> Checks.printPolicy(Checks.prPolicy(opt.get("title"), true));
            case "docs" -> switch (c1) {
                case "find" -> Docs.printFind(need(a, 2, "KEY"));
                case "pull" -> Docs.pull(need(a, 2, "KEY"));
                case "publish" -> Docs.publish(need(a, 2, "KEY"), Approvals.fromCli(need(a, 3, "test-design|lld")), file(opt));
                default -> usage();
            };
            case "approval" -> Approvals.cli(c1, need(a, 2, "KEY"), Approvals.fromCli(need(a, 3, "test-design|lld")));
            case "mindmap" -> MindMap.cli(file(opt), Path.of(opt.getOrDefault("out", file(opt).resolveSibling("mindmap.png").toString())));
            case "repos" -> Repos.sync();
            case "tasks" -> c1.equals("create") ? Tasks.create(need(a, 2, "STORY")) : Tasks.list(pos(a, 2));
            case "task" -> c1.equals("start") ? Tasks.start(need(a, 2, "TASK")) : Tasks.finish();
            case "review" -> Loops.recordReview(need(a, 2, "TASK"), file(opt));
            case "qa" -> Loops.recordQa(a.length > 2 && !a[2].startsWith("--") ? a[2] : "", file(opt));
            case "ci" -> switch (c1) {
                case "wait" -> Ci.waitFor(opt);
                case "failures" -> Ci.failures(opt);
                default -> Ci.status(opt);
            };
            case "sync" -> Sync.run(pos(a, 1), false);
            case "retro", "mistakes" -> Mistakes.retro(a.length > 1 && !a[1].startsWith("--") ? a[1] : Work.currentStory(), opt.containsKey("comment"));
            case "jira" -> jira(c1, a, opt);
            case "confluence" -> {
                Confluence.Page p = Confluence.page(need(a, 2, "PAGE_ID"));
                System.out.println(opt.containsKey("storage") ? p.storage() // the raw storage format, to debug the Markdown conversion
                        : "# " + p.title() + "  (v" + p.version() + ", " + p.url() + ")\n\n" + Markdown.toMarkdown(p.storage()));
                yield 0;
            }
            default -> usage();
        };
    }

    private static int jira(String c1, String[] a, Map<String, String> opt) throws IOException {
        switch (c1) {
            case "get" -> System.out.println(Jira.text(need(a, 2, "KEY"), Integer.parseInt(opt.getOrDefault("comments", "10"))));
            case "search" -> {
                for (Map<String, Object> i : Jira.search(need(a, 2, "JQL"), Integer.parseInt(opt.getOrDefault("max", "20")))) {
                    System.out.printf("%-12s %-14s %-10s %s%n", Json.str(i, "key"), Json.str(i, "fields.status.name"), Json.str(i, "fields.issuetype.name"), Json.str(i, "fields.summary"));
                }
            }
            case "comment" -> {
                String body = opt.containsKey("file") ? Files.readString(FileRules.safeInput(opt.get("file"))) : opt.getOrDefault("text", "");
                if (body.isBlank()) throw new UsageException("give --file <path> or --text \"...\"");
                Jira.comment(need(a, 2, "KEY"), body, true);
                System.out.println("Comment added to " + a[2]);
            }
            case "transitions" -> {
                for (Map<String, Object> t : Jira.transitions(need(a, 2, "KEY"))) System.out.println(t.get("id") + "  " + t.get("name") + "  ->  " + Json.str(t, "to.name"));
            }
            case "transition" -> {
                if (!Jira.transition(need(a, 2, "KEY"), need(a, 3, "STATUS"), true)) throw new UsageException("no transition to '" + a[3] + "' from the current status (see: jira transitions " + a[2] + ")");
                System.out.println(a[2] + " moved to " + a[3]);
            }
            case "link" -> {
                String url = need(a, 3, "URL");
                if (!url.startsWith("http")) throw new UsageException("URL must start with http(s)://");
                Jira.link(need(a, 2, "KEY"), url, opt.getOrDefault("title", "Link"));
                System.out.println("Linked " + url);
            }
            default -> {
                return usage();
            }
        }
        return 0;
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
        return FileRules.safeInput(f);
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
