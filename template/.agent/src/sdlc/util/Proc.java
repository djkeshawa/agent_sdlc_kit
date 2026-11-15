package sdlc.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public record Proc(int code, String out, String err) {
    public static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");

    public static final java.util.Map<String, String> ENV = new java.util.concurrent.ConcurrentHashMap<>();

    public static Proc run(Path dir, String... cmd) {
        return runWithInput(dir, null, 120, cmd);
    }

    public static Proc runWithInput(Path dir, String stdin, int timeoutSeconds, String... cmd) {
        return runWithEnv(dir, java.util.Map.of(), stdin, timeoutSeconds, cmd);
    }

    public static final int MAX_OUTPUT = 64 << 20;
    public static volatile int maxOutput = MAX_OUTPUT;
    public static final int BUILD_OUTPUT_END = 2 << 20;
    public interface Lines {
        void accept(String text, boolean endsLine);
    }

    public static final int TOO_MUCH_OUTPUT = 125;

    public static Proc runWithEnv(Path dir, java.util.Map<String, String> env, String stdin, int timeoutSeconds, String... cmd) {
        return exec(dir, env, stdin, timeoutSeconds, maxOutput, 0, null, null, cmd);
    }

    public static Proc runBuild(Path dir, java.util.Map<String, String> env, int timeoutSeconds, Lines outLines, Lines errLines, String... cmd) {
        return exec(dir, env, null, timeoutSeconds, BUILD_OUTPUT_END, BUILD_OUTPUT_END, outLines, errLines, cmd);
    }

    public static Proc exec(Path dir, java.util.Map<String, String> env, String stdin, int timeoutSeconds, int head, int tail,
            Lines outLines, Lines errLines, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.environment().putAll(ENV);
            env.forEach((k, v) -> {
                if (v == null || v.isEmpty()) pb.environment().remove(k);
                else pb.environment().put(k, v);
            });
            Process p = pb.start();
            Thread writer = new Thread(() -> {
                try (OutputStream o = p.getOutputStream()) {
                    if (stdin != null) o.write(stdin.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            });
            Kept out = new Kept(head, tail, outLines);
            Kept err = new Kept(head, tail, errLines);
            Thread readErr = new Thread(() -> copy(p.getErrorStream(), err));
            Thread readOut = new Thread(() -> copy(p.getInputStream(), out));
            for (Thread t : List.of(writer, readErr, readOut)) t.setDaemon(true);
            writer.start();
            readErr.start();
            readOut.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Proc(124, out.text(), "timeout after " + timeoutSeconds + " s");
            }
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

    // Reads past what is kept: a child must never block on a full pipe.
    private static void copy(InputStream in, Kept out) {
        byte[] buf = new byte[8192];
        try {
            for (int n; (n = in.read(buf)) >= 0;) out.write(buf, n);
        } catch (IOException ignored) {
        }
        out.end();
    }

    static final class Kept {
        private final int head;
        private final byte[] ring;
        private final ByteArrayOutputStream first = new ByteArrayOutputStream();
        private long total;
        private final Lines listener;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Kept(int head, int tail, Lines listener) {
            this.head = head;
            this.ring = new byte[tail];
            this.listener = listener;
        }

        private void lines(byte[] buf, int n) {
            for (int i = 0; i < n; i++) {
                if (buf[i] != '\n') {
                    line.write(buf[i]);
                    if (line.size() >= 16384) {
                        byte[] piece = line.toByteArray();
                        listener.accept(new String(piece, StandardCharsets.UTF_8), false);
                        line.reset();
                        line.write(piece, piece.length - 512, 512);
                    }
                    continue;
                }
                listener.accept(line.toString(StandardCharsets.UTF_8), true);
                line.reset();
            }
        }

        synchronized void end() {
            if (listener != null && line.size() > 0) {
                listener.accept(line.toString(StandardCharsets.UTF_8), true);
                line.reset();
            }
        }

        synchronized void write(byte[] buf, int n) {
            int at = 0;
            if (total < head) {
                at = (int) Math.min(n, head - total);
                first.write(buf, 0, at);
            }
            for (int i = at; i < n && ring.length > 0; i++) ring[(int) ((total + i - head) % ring.length)] = buf[i];
            if (listener != null) lines(buf, n);
            total += n;
        }

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
