package sdlc.workflow;

import java.util.List;

/**
 * What the workflow needs from the wiki that holds the one pagers, HLDs, test designs and
 * LLDs. Confluence is the implementation; the workflow classes know only this interface.
 */
public interface Wiki {
    /** A page with its body in the wiki's storage format. author: who saved this version; empty when the wiki does not say. */
    record Page(String id, String title, String space, int version, String storage, String url, String author) {}

    /** A page as a search finds it: no body. */
    record Ref(String id, String title, String space, String version, String url) {}

    Page page(String id);

    /** Pages whose title contains the words, in the given spaces (every space when the list is empty). */
    List<Ref> titled(String words, List<String> spaces);

    /** The page with exactly this title in the space, or null. Not a search: a page published a minute ago is found. */
    Ref byTitle(String space, String title);

    /** Creates the page, or a new version of the page with the same title in the space. */
    Page publish(String space, String parentId, String title, String storage);

    /** Adds or replaces an attachment (the mind map image). */
    void attach(String pageId, String fileName, byte[] data, String mime);

    /** Throws when the wiki cannot be reached with the configured account. */
    void ping();
}
