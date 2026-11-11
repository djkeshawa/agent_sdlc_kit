package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.guard.FileRules;
import sdlc.util.Markdown;

/**
 * The document chain in Confluence: one pager (product owner), HLD (architect), test design
 * (QE), LLDs (developers, this team and others). Pages are found by the Jira key in their title,
 * following the issue's parents (sub-task -> story -> epic).
 */
public final class Docs {
    record Doc(String type, String key, String id, String title, String space, String version, String url, boolean ours) {}

    static final List<String> TYPES = List.of("testDesign", "lld", "hld", "onePager", "solution");

    private final Workflow w;

    Docs(Workflow w) {
        this.w = w;
    }

    List<String> keysFor(String key) {
        List<String> keys = new ArrayList<>(List.of(key));
        try {
            keys.addAll(w.issues.parents(key));
        } catch (RuntimeException e) {
            w.console.err("[sdlc] could not read Jira parents of " + key + ": " + e.getMessage());
        }
        return keys;
    }

    List<Doc> find(String key) {
        List<Doc> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> spaces = Ctx.cfg.list("confluence.searchSpaces");
        for (String type : TYPES) {
            String s = Ctx.cfg.str("confluence.publish." + type + ".space");
            if (!s.isBlank() && !spaces.contains(s)) spaces.add(s);
        }
        for (String k : keysFor(key)) {
            Pattern exact = Pattern.compile("(^|[^A-Z0-9-])" + Pattern.quote(k) + "([^0-9]|$)");
            for (Wiki.Ref r : w.wiki.titled(k, spaces)) {
                if (!exact.matcher(r.title()).find() || !seen.add(r.id())) continue;
                String type = classify(r.title());
                out.add(new Doc(type, k, r.id(), r.title(), r.space(), r.version(), r.url(), isOurs(type, k, r.title(), r.space())));
            }
        }
        return out;
    }

    static String classify(String title) {
        for (String type : TYPES) {
            if (Pattern.compile(Ctx.cfg.str("confluence.titlePatterns." + type, "(?!)")).matcher(title).find()) return type;
        }
        return "related";
    }

    static String title(String type, String key) {
        return Ctx.cfg.str("confluence.publish." + type + ".title", "{key} - " + type).replace("{key}", key)
                .replace("{team}", Ctx.cfg.str("project.team", "team")).replace("{project}", Ctx.cfg.str("project.name", ""));
    }

    private static boolean isOurs(String type, String key, String title, String space) {
        if (!type.equals("lld") && !type.equals("testDesign") && !type.equals("solution")) return false;
        return title.equalsIgnoreCase(title(type, key)) && space.equalsIgnoreCase(Ctx.cfg.str("confluence.publish." + type + ".space"));
    }

    /**
     * Our own published document of a type (the test design or our team's LLD), nearest key first.
     * Looked up by its exact title, not by search: a page published a minute ago is not in the
     * search index yet.
     */
    Doc own(String key, String type) {
        String space = Ctx.cfg.str("confluence.publish." + type + ".space");
        if (space.isBlank()) return null;
        for (String k : keysFor(key)) {
            Wiki.Ref r = w.wiki.byTitle(space, title(type, k));
            if (r != null) return new Doc(type, k, r.id(), r.title(), space, r.version(), r.url(), true);
        }
        return null;
    }

    public int printFind(String key) {
        List<Doc> docs = find(key);
        if (docs.isEmpty()) {
            w.console.out("No pages with " + key + " (or its parents) in their titles in spaces " + Ctx.cfg.list("confluence.searchSpaces"));
            return 1;
        }
        for (Doc d : docs) w.console.out(String.format("%-10s %-4s %-10s v%-4s %s  %s", d.type(), d.ours() ? "ours" : "", d.key(), d.version(), d.title(), d.url()));
        return 0;
    }

    /** Copies every document (and the Jira issues) into .sdlc/<KEY>/sources/ for agents to read. */
    public int pull(String key) throws IOException {
        Path dir = Work.storyDir(key).resolve("sources");
        Files.createDirectories(dir);
        StringBuilder index = new StringBuilder("# Sources for " + key + "\n\nPulled by `sdlc docs pull " + key + "`. Read-only copies; the originals are in Confluence and Jira.\n\n");
        for (String k : keysFor(key)) {
            try {
                Path f = dir.resolve("jira-" + k + ".md");
                Store.writeText(f, w.issues.text(k, 20));
                index.append("- Jira ").append(k).append(": `").append(Ctx.rel(f)).append("`\n");
            } catch (RuntimeException e) {
                index.append("- Jira ").append(k).append(": could not read (").append(e.getMessage()).append(")\n");
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Doc d : find(key)) {
            Wiki.Page p = w.wiki.page(d.id());
            boolean otherTeam = !d.ours() && (d.type().equals("lld") || d.type().equals("testDesign")); // one pager and HLD are shared inputs
            String name = d.type() + (otherTeam ? "-other" : "") + "-" + d.id() + ".md";
            Path f = dir.resolve(name);
            String approval = d.ours() ? w.approvals.statusLine(d.key(), d.type()) : "";
            Store.writeText(f, "<!-- " + d.type() + " | " + p.title() + " | space " + p.space() + " | version " + p.version() + " | " + p.url() + " -->\n"
                    + (approval.isEmpty() ? "" : "<!-- " + approval + " -->\n") + "# " + p.title() + "\n\n" + Markdown.toMarkdown(p.storage()));
            counts.merge(d.type(), 1, Integer::sum);
            index.append("- ").append(label(d.type())).append(d.ours() ? " (ours)" : "").append(": ").append(p.title()).append(" v").append(p.version())
                    .append(approval.isEmpty() ? "" : " - " + approval).append(" -> `").append(Ctx.rel(f)).append("`\n");
        }
        for (String type : List.of("onePager", "hld")) if (!counts.containsKey(type)) index.append("- ").append(label(type)).append(": NOT FOUND\n");
        index.append(solutionSlice(key, dir));
        if (!Ctx.cfg.maps("github.relatedRepos").isEmpty()) {
            index.append("\nRelated repositories (read with the GitHub MCP tools, or `sdlc repos sync` for local copies in .sdlc/repos/):\n");
            for (Map<String, Object> r : Ctx.cfg.maps("github.relatedRepos")) index.append("- ").append(r.get("name")).append(" (").append(r.getOrDefault("repo", r.get("url"))).append(") team ").append(r.getOrDefault("team", "?")).append(": ").append(r.getOrDefault("purpose", "")).append('\n');
        }
        Store.writeText(dir.resolve("index.md"), index.toString());
        w.console.out(index.toString());
        return 0;
    }

    /** The solution design that governs this issue (its own, or a parent's), or null. */
    Doc solution(String key) {
        Doc own = own(key, "solution");
        if (own != null) return own;
        for (Doc d : find(key)) if (d.type().equals("solution")) return d;
        return null;
    }

    /**
     * Writes sources/solution-slice.md when a parent epic has a solution design that names this
     * repository's component: what the team designs from, and the hash its LLD must carry.
     */
    private String solutionSlice(String key, Path dir) {
        Doc d;
        try {
            d = solution(key);
        } catch (RuntimeException e) {
            return "- Solution design: could not look it up (" + e.getMessage() + ")\n";
        }
        if (d == null) return "";
        Wiki.Page p = w.wiki.page(d.id());
        Solution s;
        try {
            s = Solution.parseStorage(p.storage());
        } catch (UsageException e) {
            return "- Solution design " + p.title() + ": " + e.getMessage() + "\n";
        }
        String self = Catalog.selfName();
        String approval = w.approvals.statusLine(d.key(), "solution");
        if (!s.components.containsKey(self)) {
            return "- Solution design " + p.title() + " v" + p.version() + " (" + approval + "): this repository's component '" + self
                    + "' is NOT in it. Its components: " + s.components.keySet() + ". Ask the architect before designing anything here.\n";
        }
        String hash = s.sliceHash(self);
        Path f = dir.resolve("solution-slice.md");
        Store.writeText(f, "<!-- solution-slice " + hash + " | " + p.title() + " v" + p.version() + " | " + p.url() + " | " + approval + " -->\n"
                + "# Solution slice for " + self + "\n\nYour component's part of the approved solution design. Design only this. "
                + "Your LLD's `# sdlc-tasks` block must carry:\n\n```yaml\nlane: cross-component\nsolutionSlice: " + hash + "\n```\n\n"
                + "Contracts: add or remove only what `provides` lists. Anything else needs a solution change request "
                + "(.sdlc/" + key + "/inbox/scr-1.md) and a new solution version.\n\n```yaml\n" + s.slice(self) + "```\n");
        return "- Solution slice for " + self + " (from " + p.title() + " v" + p.version() + ", " + approval + "), hash " + hash + " -> `" + Ctx.rel(f) + "`\n";
    }

    static String label(String type) {
        return switch (type) {
            case "onePager" -> "One pager";
            case "hld" -> "HLD";
            case "lld" -> "LLD";
            case "testDesign" -> "Test design";
            case "solution" -> "Solution design";
            default -> "Related page";
        };
    }

    private static final Pattern IMAGE_FILE = Pattern.compile("(?i).+\\.(png|jpe?g|gif|svg)$");

    /**
     * The image a draft references, or null when it is not an image file inside the draft's own
     * folder. An agent writes the draft, so a reference like ../../.agent/local.env must never be
     * read and uploaded.
     */
    public static Path attachable(Path draftDir, String img) {
        if (!IMAGE_FILE.matcher(img).matches()) return null;
        try {
            Path dir = draftDir.toRealPath();
            Path p = draftDir.resolve(img).normalize();
            if (!p.startsWith(draftDir) || !Files.isRegularFile(p)) return null;
            Path real = p.toRealPath();
            FileRules files = Gates.guard(null).files;
            if (!real.startsWith(dir) || files.isSecret(p.toString()) || files.isSecret(real.toString())) return null;
            return p;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String mime(String img) {
        String l = img.toLowerCase(java.util.Locale.ROOT);
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** Publishes a local Markdown draft as the test design or our LLD, then asks for approval. */
    public int publish(String key, String type, Path file) throws IOException {
        if (!type.equals("testDesign") && !type.equals("lld") && !type.equals("solution")) throw new UsageException("type must be test-design, lld or solution");
        String space = Ctx.cfg.str("confluence.publish." + type + ".space");
        if (space.isBlank()) throw new UsageException("confluence.publish." + type + ".space is not set in .agent/sdlc.yml");
        String md = Files.readString(file);
        if (type.equals("lld")) Tasks.parse(md); // refuse an LLD whose tasks block is broken
        if (type.equals("solution")) {
            List<String> problems = Solution.parse(md).problems(Catalog.load());
            if (!problems.isEmpty()) throw new UsageException("the solution design is not ready to publish:\n- " + String.join("\n- ", problems));
        }
        if (type.equals("testDesign") && Ctx.cfg.bool("confluence.mindmapImage", true)) md = MindMap.addImage(md, file);
        Markdown.Converted c = Markdown.toStorage(md, Ctx.cfg.str("confluence.mermaidMacro"));
        String title = title(type, key);
        Wiki.Page page = w.wiki.publish(space, Ctx.cfg.str("confluence.publish." + type + ".parentId"), title, c.storage());
        Path draftDir = file.toAbsolutePath().normalize().getParent();
        for (String img : c.images()) {
            Path p = attachable(draftDir, img);
            if (p == null) {
                w.console.err("[sdlc] not uploading " + img + ": only image files (png, jpg, gif, svg) inside the draft's folder are attached.");
                continue;
            }
            try {
                w.wiki.attach(page.id(), p.getFileName().toString(), Files.readAllBytes(p), mime(img));
            } catch (RuntimeException e) {
                w.console.err("[sdlc] could not attach " + img + " (" + e.getMessage() + "); the page has the list version.");
            }
        }
        Store.write(Work.storyDir(key).resolve("published-" + type + ".json"), Store.map("id", page.id(), "version", page.version(), "url", page.url(), "title", title));
        String line = Approvals.line(key, type, w.wiki.page(page.id()));
        w.console.out("Published " + label(type) + " v" + page.version() + ": " + page.url());
        String gate = Ctx.cfg.str("gates." + type, "required");
        if (!gate.equals("off")) {
            w.issues.comment(key, Approvals.BOT_MARK + " " + label(type) + " v" + page.version() + " published: [" + title + "|" + page.url() + "]\n"
                    + "Approver: review the page (edit it in Confluence if needed, then ask for a new approval line), and approve this exact version by posting this line as a NEW comment:\n{noformat}" + line + "{noformat}", false);
            w.console.out("\nApproval (" + gate + "): the approver posts this line as a NEW Jira comment on " + key + ":\n\n    " + line + "\n");
            w.console.out("A comment with the line was added to " + key + " for the approver. Any later page edit needs a new approval.");
        }
        return 0;
    }
}
