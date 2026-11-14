package sdlc.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs child processes. */
public record Proc(int code, String out, String err) {
    public static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");

    /** Variables added to the environment of every child process of this run. */
    public static final java.util.Map<String, String> ENV = new java.util.concurrent.ConcurrentHashMap<>();

    public static Proc run(Path dir, String... cmd) {
        return runWithInput(dir, null, 120, cmd);
    }

    public static Proc runWithInput(Path dir, String stdin, int timeoutSeconds, String... cmd) {
        return runWithEnv(dir, java.util.Map.of(), stdin, timeoutSeconds, cmd);
    }

    /**
     * The most one stream of a child may hold in memory. A command whose answer is read as a whole
     * (git) fails beyond it: a cut-off answer must not pass for the whole one.
     */
    public static final int MAX_OUTPUT = 64 << 20;
    /** What is kept of each end of a build's output, which is read only for its messages. */
    public static final int BUILD_OUTPUT_END = 2 << 20;
    /** The exit code of a run whose output was larger than MAX_OUTPUT. */
    public static final int TOO_MUCH_OUTPUT = 125;

    /** As runWithInput, with environment variables added to (an empty value removes one from) the child's environment. */
    public static Proc runWithEnv(Path dir, java.util.Map<String, String> env, String stdin, int timeoutSeconds, String... cmd) {
        return exec(dir, env, stdin, timeoutSeconds, MAX_OUTPUT, 0, cmd);
    }

    /**
     * Runs a build or a test run, whose output can be of any size and is read only for its
     * messages: the start and the end of each stream are kept and the middle is dropped, with a
     * line that says how much.
     */
    public static Proc runBuild(Path dir, java.util.Map<String, String> env, int timeoutSeconds, String... cmd) {
        return exec(dir, env, null, timeoutSeconds, BUILD_OUTPUT_END, BUILD_OUTPUT_END, cmd);
    }

    /**
     * Runs a command and keeps at most head bytes of the start of each output stream and tail bytes
     * of its end. With tail 0 a larger output is an error (TOO_MUCH_OUTPUT), not a shortened answer.
     */
    public static Proc exec(Path dir, java.util.Map<String, String> env, String stdin, int timeoutSeconds, int head, int tail, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.environment().putAll(ENV);
            env.forEach((k, v) -> {
                if (v == null || v.isEmpty()) pb.environment().remove(k);
                else pb.environment().put(k, v);
            });
            Process p = pb.start();
            // stdin on its own thread, so a large output cannot block the input
            Thread writer = new Thread(() -> {
                try (OutputStream o = p.getOutputStream()) {
                    if (stdin != null) o.write(stdin.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the process closed its input early
                }
            });
            Kept out = new Kept(head, tail);
            Kept err = new Kept(head, tail);
            Thread readErr = new Thread(() -> copy(p.getErrorStream(), err));
            Thread readOut = new Thread(() -> copy(p.getInputStream(), out)); // on its own thread: reading here would wait for ever on a process that hangs
            for (Thread t : List.of(writer, readErr, readOut)) t.setDaemon(true);
            writer.start();
            readErr.start();
            readOut.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Proc(124, out.text(), "timeout after " + timeoutSeconds + " s");
            }
            // a background child of the command may keep the pipes open after it exits: do not wait for it for ever
            readOut.join(5000);
            readErr.join(5000);
            writer.join(5000);
            if (tail == 0 && (out.dropped() > 0 || err.dropped() > 0)) {
                return new Proc(TOO_MUCH_OUTPUT, "", "its output is larger than " + (head >> 20) + " MB: " + String.join(" ", cmd));
            }
            return new Proc(p.exitValue(), out.text(), err.text());
        } catch (IOException e) {
            return new Proc(127, "", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Proc(130, "", "interrupted");
        }
    }

    /** Reads the stream to its end, also past what is kept: a child must never block on a full pipe. */
    private static void copy(InputStream in, Kept out) {
        byte[] buf = new byte[8192];
        try {
            for (int n; (n = in.read(buf)) >= 0;) out.write(buf, n);
        } catch (IOException ignored) {
            // the process ended
        }
    }

    /** The first bytes of a stream and its last ones, with a count of what lay between. */
    static final class Kept {
        private final int head;
        private final byte[] ring;
        private final ByteArrayOutputStream first = new ByteArrayOutputStream();
        private long total;

        Kept(int head, int tail) {
            this.head = head;
            this.ring = new byte[tail];
        }

        synchronized void write(byte[] buf, int n) {
            int at = 0;
            if (total < head) {
                at = (int) Math.min(n, head - total);
                first.write(buf, 0, at);
            }
            for (int i = at; i < n && ring.length > 0; i++) ring[(int) ((total + i - head) % ring.length)] = buf[i];
            total += n;
        }

        /** How many bytes are not in text(). */
        synchronized long dropped() {
            return Math.max(0, total - head - ring.length);
        }

        synchronized String text() {
            if (total <= head) return first.toString(StandardCharsets.UTF_8);
            long past = total - head;
            int kept = (int) Math.min(past, ring.length);
            byte[] last = new byte[kept];
            for (int i = 0; i < kept; i++) last[i] = ring[(int) ((past - kept + i) % ring.length)];
            String gap = dropped() == 0 ? "" : "\n... " + dropped() + " bytes of output left out here ...\n";
            return first.toString(StandardCharsets.UTF_8) + gap + new String(last, StandardCharsets.UTF_8);
        }
    }

    /** Runs a command line through the platform shell with the console attached. */
    public static int shell(Path dir, String line) {
        return inherit(dir, WINDOWS ? List.of("cmd", "/c", line) : List.of("sh", "-c", line));
    }

    static int inherit(Path dir, List<String> cmd) {
        try {
            return new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            System.err.println("cannot run " + cmd + ": " + e.getMessage());
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    public boolean ok() {
        return code == 0;
    }
}
