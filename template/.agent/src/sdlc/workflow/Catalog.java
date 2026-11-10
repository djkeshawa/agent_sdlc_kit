package sdlc.workflow;

import java.util.Locale;
import java.util.Map;

import sdlc.core.Ctx;
import sdlc.core.Work;

/** The components of the organisation and the repositories they live in. */
public final class Catalog {
    private Catalog() {}

    /** The component name for a repository (owner/name): from github.relatedRepos, else the repository's own name. */
    public static String componentOfRepo(String repo) {
        String r = repo.strip();
        for (Map<String, Object> rel : Ctx.cfg.maps("github.relatedRepos")) {
            if (r.equalsIgnoreCase(Work.str(rel.get("repo"))) && !Work.str(rel.get("name")).isBlank()) return Work.str(rel.get("name")).strip().toLowerCase(Locale.ROOT);
        }
        return r.substring(r.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }
}
