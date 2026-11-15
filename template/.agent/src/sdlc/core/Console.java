package sdlc.core;

public interface Console {
    void out(String line);

    void err(String line);

    Console TERMINAL = new Console() {
        public void out(String line) { System.out.println(line); }
        public void err(String line) { System.err.println(line); }
    };
}
