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

/**
 * Read-only GitHub queries through the gh CLI (it already holds the person's login, so the
 * tool needs no GitHub token of its own). Used for cross-repository facts: which repositories
 * had commits for a Jira key, and the content of a file in another repository.
 */
public final class GitHub {
    private GitHub() {}

    /** One commit found by search: the repository (owner/name), the sha and the first line of its message. */
    public record Commit(String repo, String sha, String subject) {}

    /** Commits whose message mentions the key, in the configured organisation (github.org) when set. */
    public static List<Commit> commitsFor(String key) {
        String org = Ctx.cfg.str("github.org");
        String q = "\"" + key + "\"" + (org.isBlank() ? "" : " org:" + org);
        Proc r = Proc.run(Ctx.root, "gh", "api", "-X", "GET", "search/commits", "-f", "q=" + q, "-f", "per_page=100");
        if (r.code() != 0) throw new UsageException("gh api search/commits failed for " + key + ": " + (r.out() + r.err()).strip());
        List<Commit> out = new ArrayList<>();
        java.util.regex.Pattern exact = java.util.regex.Pattern.compile("(^|[^A-Z0-9-])" + java.util.regex.Pattern.quote(key) + "([^0-9]|$)");
        for (Object item : Json.list(Json.at(Json.parse(r.out()), "items"))) {
            String message = Json.str(item, "commit.message");
            if (!exact.matcher(message).find()) continue; // the search is fuzzy: PAY-10 also finds PAY-100
            out.add(new Commit(Json.str(item, "repository.full_name"), Json.str(item, "sha"), message.lines().findFirst().orElse("")));
        }
        return out;
    }

    /**
     * Repositories (owner/name) with commits for any of the keys, with the number of commits each.
     * Two sources, because GitHub's commit search covers default branches only and often has
     * nothing for private repositories: the search, and the commit lists of every branch of the
     * known repositories (this one, github.relatedRepos, catalog.repos).
     */
    public static Map<String, Integer> reposFor(List<String> keys, List<String> knownRepos) {
        Map<String, Integer> repos = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String k : keys) {
            List<Commit> found = new ArrayList<>();
            try {
                found.addAll(commitsFor(k));
            } catch (UsageException e) {
                // the search may be unavailable (rate limit, GitHub Enterprise without it): the listing below still answers
            }
            java.util.regex.Pattern exact = java.util.regex.Pattern.compile("(^|[^A-Z0-9-])" + java.util.regex.Pattern.quote(k) + "([^0-9]|$)");
            for (String repo : knownRepos) for (Commit c : commitsIn(repo)) if (exact.matcher(c.subject()).find()) found.add(c);
            for (Commit c : found) if (seen.add(c.repo() + "@" + c.sha())) repos.merge(c.repo(), 1, Integer::sum);
        }
        return repos;
    }

    private static final Map<String, List<Commit>> LISTED = new LinkedHashMap<>();

    /** The commits of every branch of a repository (first line of each message), newest github.commitPages x 100 per branch. */
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

    /** owner/name of this repository's origin on the configured GitHub host, or "" when it has none. */
    public static String originRepo() {
        Proc r = Proc.run(Ctx.root, "git", "remote", "get-url", "origin");
        if (r.code() != 0) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[:/]([^/:]+/[^/]+?)(\\.git)?/?$").matcher(r.out().strip());
        return m.find() && !r.out().strip().startsWith("/") && !r.out().strip().startsWith("file:") ? m.group(1) : "";
    }

    /** The text of a file in another repository's default branch, or null when it has none. */
    public static String file(String repo, String path) {
        Proc r = Proc.run(Ctx.root, "gh", "api", "-H", "Accept: application/vnd.github.raw", "repos/" + repo + "/contents/" + path);
        return r.code() == 0 ? r.out() : null;
    }
}
