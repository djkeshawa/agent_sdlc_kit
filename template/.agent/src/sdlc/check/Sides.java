package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import sdlc.core.UsageException;

/**
 * The two throw-away checkouts of a change: after it, and before it. "Before" is the base commit
 * with the change's tests, fixtures and helpers laid over it, so the same tests can be run on
 * both sides. Made when first asked for; removed when closed.
 */
final class Sides implements AutoCloseable {
    private final Change change;
    private final List<Runner> runners;
    private Scratch head;
    private Scratch base;

    Sides(Change change, List<Runner> runners) {
        this.change = change;
        this.runners = runners;
    }

    Scratch head() {
        if (head == null) head = Scratch.of(change.head, "after");
        return head;
    }

    Scratch base() {
        if (base != null) return base;
        base = Scratch.of(change.base, "before");
        try {
            List<String> support = new ArrayList<>();
            for (String path : change.paths("AM")) if (runners.stream().anyMatch(r -> r.support(path))) support.add(path);
            base.write(change.head, support);
            // a fixture or helper the change deletes is gone before the change too, as the tests now expect
            for (String gone : change.paths("D")) if (runners.stream().anyMatch(r -> r.support(gone))) Files.deleteIfExists(base.dir.resolve(gone));
        } catch (IOException e) {
            throw new UsageException("cannot prepare the checkout of the base: " + e.getMessage());
        }
        return base;
    }

    @Override
    public void close() {
        if (head != null) head.close();
        if (base != null) base.close();
    }
}
