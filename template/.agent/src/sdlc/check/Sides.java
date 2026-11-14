package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import sdlc.core.UsageException;

/**
 * The throw-away checkouts of a change: after it, and before it. "Before" is the base commit
 * with the change's tests, fixtures and helpers laid over it, so the same tests can be run on
 * both sides. A third, "old", is the base commit as it is: there the test files the change edits
 * run as they were, to learn which tests they had. A fourth, "old-tests", holds the tree after the
 * change with old test files laid over it (see oldTests). Each is made when first asked for; all
 * are removed when closed.
 */
final class Sides implements AutoCloseable {
    private final Change change;
    private final List<Runner> runners;
    private Scratch head;
    private Scratch base;
    private Scratch old;
    private Scratch oldTests;

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
            // a fixture or helper the change deletes is gone before the change too, as the tests now expect.
            // First, so that a file the change turned into a folder is out of the way of what is written next.
            for (String gone : change.paths("D")) if (runners.stream().anyMatch(r -> r.support(gone))) Files.deleteIfExists(base.dir.resolve(gone));
            base.write(change.head, support);
        } catch (IOException e) {
            throw new UsageException("cannot prepare the checkout of the base: " + e.getMessage());
        }
        return base;
    }

    /** The base commit with nothing laid over it: the old version of each edited test file, on the code it was written for. */
    Scratch old() {
        if (old == null) old = Scratch.of(change.base, "old");
        return old;
    }

    /**
     * The fourth checkout: the tree after the change, over which the witness lays the old version
     * of the edited test files it needs, to run old tests on the code after the change. Made only
     * when a test is gone, switched off or runs fewer times. It is a checkout of its own, so the
     * checkout after the change is never touched for this.
     */
    Scratch oldTests() {
        if (oldTests == null) oldTests = Scratch.of(change.head, "old-tests");
        return oldTests;
    }

    /** True when the fourth checkout was made. */
    boolean madeOldTests() {
        return oldTests != null;
    }

    @Override
    public void close() {
        if (head != null) head.close();
        if (base != null) base.close();
        if (old != null) old.close();
        if (oldTests != null) oldTests.close();
    }
}
