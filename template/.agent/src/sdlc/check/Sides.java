package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import sdlc.core.UsageException;

final class Sides implements AutoCloseable {
    private final Change change;
    private final List<Runner> runners;
    private Scratch head;
    private Scratch base;
    private Scratch old;
    private final Map<Runner, Scratch> oldTests = new IdentityHashMap<>();

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
            for (String gone : change.paths("D")) if (runners.stream().anyMatch(r -> r.support(gone))) Files.deleteIfExists(base.dir.resolve(gone));
            base.write(change.head, support);
        } catch (IOException e) {
            throw new UsageException("cannot prepare the checkout of the base: " + e.getMessage());
        }
        return base;
    }

    Scratch old() {
        if (old == null) old = Scratch.of(change.base, "old");
        return old;
    }

    Scratch oldTests(Runner runner) {
        return oldTests.computeIfAbsent(runner, r -> Scratch.of(change.head, "old-tests"));
    }

    boolean madeOldTests() {
        return !oldTests.isEmpty();
    }

    boolean madeOldTests(Runner runner) {
        return oldTests.containsKey(runner);
    }

    @Override
    public void close() {
        if (head != null) head.close();
        if (base != null) base.close();
        if (old != null) old.close();
        for (Scratch s : oldTests.values()) s.close();
    }
}
