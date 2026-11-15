package sdlc.workflow;

import java.util.List;

public interface Wiki {
    record Page(String id, String title, String space, int version, String storage, String url, String author) {}

    record Ref(String id, String title, String space, String version, String url) {}

    Page page(String id);

    List<Ref> titled(String words, List<String> spaces);

    Ref byTitle(String space, String title);

    Page publish(String space, String parentId, String title, String storage);

    void attach(String pageId, String fileName, byte[] data, String mime);

    void ping();
}
