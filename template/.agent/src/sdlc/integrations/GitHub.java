package sdlc.integrations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sdlc.core.Ctx;
import sdlc.core.UsageException;
import sdlc.util.Json;
import sdlc.util.Proc;

public final class GitHub {
    private GitHub() {}

    public record Commit(String repo, String sha, String subject) {}

    public static List<Commit> commitsFor(String key) {
        String org = Ctx.cfg.str("github.org");
        String q = "\"" + key + "\"" + (org.isBlank() ? "" : " org:" + org);
        Proc r = Proc.run(Ctx.root, "gh", "api", "-X", "GET", "search/commits", "-f", "q=" + q, "-f", "per_page=100");
        if (r.code() != 0) throw new UsageException("gh api search/commits failed for " + key + ": " + (r.out() + r.err()).strip());
        List<Commit> out = new ArrayList<>();
        java.util.regex.Pattern exact = java.util.regex.Pattern.compile("(^|[^A-Z0-9-])" + java.util.regex.Pattern.quote(key) + "([^0-9]|$)");
        for (Object item : Json.list(Json.at(Json.parse(r.out()), "items"))) {
            String message = Json.str(item, "commit.message");
            if (!exact.matcher(message).find()) continue;
            out.add(new Commit(Json.str(item, "repository.full_name"), Json.str(item, "sha"), message.lines().findFirst().orElse("")));
        }
        return out;
    }

    public static Map<String, Integer> reposFor(List<String> keys, List<String> knownRepos) {
        Map<String, Integer> repos = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String k : keys) {
            List<Commit> found = new ArrayList<>();
            try {
                found.addAll(commitsFor(k));
            } catch (UsageException e) {
            }
            java.util.regex.Pattern exact = java.util.regex.Pattern.compile("(^|[^A-Z0-9-])" + java.util.regex.Pattern.quote(k) + "([^0-9]|$)");
            for (String repo : knownRepos) for (Commit c : commitsIn(repo)) if (exact.matcher(c.subject()).find()) found.add(c);
            for (Commit c : found) if (seen.add(c.repo() + "@" + c.sha())) repos.merge(c.repo(), 1, Integer::sum);
        }
        return repos;
    }

    private static final Map<String, List<Commit>> LISTED = new LinkedHashMap<>();

    static List<Commit> commitsIn(String repo) {
        List<Commit> cached = LISTED.get(repo);
        if (cached != null) return cached;
        List<Commit> out = new ArrayList<>();
        Set<String> shas = new LinkedHashSet<>();
        Proc b = Proc.run(Ctx.root, "gh", "api", "repos/" + repo + "/branches?per_page=100");
        if (b.code() == 0) {
            int pages = Math.max(1, Ctx.cfg.num("github.commitPages", 3));
            int branches = 0;
            for (Object br : Json.list(Json.parse(b.out()))) {
                if (++branches > 50) break;
                String name = Json.str(br, "name");
                for (int page = 1; page <= pages; page++) {
                    Proc r = Proc.run(Ctx.root, "gh", "api", "repos/" + repo + "/commits?sha=" + sdlc.util.Http.enc(name) + "&per_page=100&page=" + page);
                    if (r.code() != 0) break;
                    List<Object> items = Json.list(Json.parse(r.out()));
                    for (Object item : items) {
                        String sha = Json.str(item, "sha");
                        if (shas.add(sha)) out.add(new Commit(repo, sha, Json.str(item, "commit.message").lines().findFirst().orElse("")));
                    }
                    if (items.size() < 100) break;
                }
            }
        }
        LISTED.put(repo, out);
        return out;
    }

    public static String originRepo() {
        Proc r = Proc.run(Ctx.root, "git", "remote", "get-url", "origin");
        if (r.code() != 0) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[:/]([^/:]+/[^/]+?)(\\.git)?/?$").matcher(r.out().strip());
        return m.find() && !r.out().strip().startsWith("/") && !r.out().strip().startsWith("file:") ? m.group(1) : "";
    }

    public static String file(String repo, String path) {
        Proc r = Proc.run(Ctx.root, "gh", "api", "-H", "Accept: application/vnd.github.raw", "repos/" + repo + "/contents/" + path);
        return r.code() == 0 ? r.out() : null;
    }
}
