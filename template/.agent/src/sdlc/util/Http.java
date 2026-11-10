package sdlc.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** HTTP calls to Jira, Confluence, Jenkins and SonarQube with readable errors. */
public final class Http {
    private static HttpClient client;
    /** The proxy to use ("host:port" or a URL), or blank for the system's. Set once at start, before the first call. */
    public static java.util.function.Supplier<String> proxy = () -> "";

    private Http() {}

    public static final class ApiException extends RuntimeException {
        public final int status;

        public ApiException(int status, String m) {
            super(m);
            this.status = status;
        }
    }

    static HttpClient client() {
        if (client == null) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL);
            String via = proxy.get();
            if (!via.isBlank()) {
                URI u = URI.create(via.contains("://") ? via : "http://" + via);
                b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 8080)));
            } else {
                b.proxy(ProxySelector.getDefault());
            }
            client = b.build();
        }
        return client;
    }

    public static String basic(String user, String token) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    public static Object json(String method, String url, String auth, Object body) {
        String text = text(method, url, auth, body == null ? null : Json.write(body), "application/json");
        return text == null || text.isBlank() ? null : Json.parse(text);
    }

    public static String text(String method, String url, String auth, String body, String contentType) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                .header("Accept", "application/json").header("X-Atlassian-Token", "no-check");
        if (auth != null && !auth.isBlank()) rb.header("Authorization", auth);
        if (body == null) rb.method(method, HttpRequest.BodyPublishers.noBody());
        else rb.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return send(rb.build(), url);
    }

    /** multipart/form-data upload of one file (Confluence attachments). */
    public static String upload(String method, String url, String auth, String fileName, byte[] data, String mime) {
        String boundary = "sdlc" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"minorEdit\"\r\n\r\ntrue\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + data.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(data, 0, all, head.length, data.length);
        System.arraycopy(tail, 0, all, head.length + data.length, tail.length);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                .header("Authorization", auth).header("X-Atlassian-Token", "no-check").header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(all)).build();
        return send(req, url);
    }

    private static String send(HttpRequest req, String url) {
        HttpResponse<String> r;
        try {
            r = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (javax.net.ssl.SSLHandshakeException e) {
            throw new ApiException(0, "TLS certificate not trusted for " + host(url) + ". Your company uses its own certificate authority: see the troubleshooting guide (PKIX).");
        } catch (IOException e) {
            throw new ApiException(0, "cannot reach " + host(url) + ": " + e.getMessage() + " (proxy? VPN? set HTTPS_PROXY in .agent/local.env)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted");
        }
        int s = r.statusCode();
        String where = shortUrl(url);
        String detail = r.body() == null || r.body().isBlank() ? "" : " (server says: " + abbreviate(r.body().replaceAll("\\s+", " "), 200) + ")";
        if (s == 401) throw new ApiException(s, "401 Unauthorized at " + where + ": the token or user is wrong or expired, or a scoped token lacks this scope." + detail);
        if (s == 403) throw new ApiException(s, "403 Forbidden at " + where + ": the account lacks permission for this, or the site is restricted." + detail);
        if (s == 404) throw new ApiException(s, "404 Not Found at " + where + ": check the key/id and the base URL (Confluence Cloud ends in /wiki).");
        if (s >= 300) throw new ApiException(s, s + " at " + where + ": " + abbreviate(r.body(), 500));
        return r.body();
    }

    public static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    public static String abbreviate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sb.length() == 0 ? "?" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    public static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
