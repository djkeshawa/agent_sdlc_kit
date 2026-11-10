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

    /** Repositories (owner/name) with commits for any of the keys, with the number of commits each. */
    public static Map<String, Integer> reposFor(List<String> keys) {
        Map<String, Integer> repos = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String k : keys) {
            for (Commit c : commitsFor(k)) if (seen.add(c.repo() + "@" + c.sha())) repos.merge(c.repo(), 1, Integer::sum);
        }
        return repos;
    }

    /** The text of a file in another repository's default branch, or null when it has none. */
    public static String file(String repo, String path) {
        Proc r = Proc.run(Ctx.root, "gh", "api", "-H", "Accept: application/vnd.github.raw", "repos/" + repo + "/contents/" + path);
        return r.code() == 0 ? r.out() : null;
    }
}
