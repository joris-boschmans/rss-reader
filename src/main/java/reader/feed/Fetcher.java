package reader.feed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Conditional GET over the JDK client. No retries: a failure is reported, not fought. */
public final class Fetcher {

    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String AGENT = "QuietReader/1.0 (+local reading app)";

    public record Result(
            int status, byte[] body, String contentType,
            String etag, String lastModified, String finalUrl) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean unchanged() {
            return status == 304;
        }

        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private final HttpClient client;

    public Fetcher() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Result get(String address) throws IOException, InterruptedException {
        return get(address, null, null);
    }

    /**
     * @param etag         revalidation token from the last fetch, or null
     * @param lastModified revalidation date from the last fetch, or null
     */
    public Result get(String address, String etag, String lastModified)
            throws IOException, InterruptedException {

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address))
                .timeout(TIMEOUT)
                .header("User-Agent", AGENT)
                .header("Accept", "application/atom+xml, application/rss+xml, application/xml;q=0.9, "
                        + "text/xml;q=0.9, text/html;q=0.8, */*;q=0.5")
                .GET();
        if (etag != null && !etag.isBlank()) b.header("If-None-Match", etag);
        if (lastModified != null && !lastModified.isBlank()) b.header("If-Modified-Since", lastModified);

        HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Result(
                r.statusCode(),
                r.body() == null ? new byte[0] : r.body(),
                header(r, "content-type"),
                header(r, "etag"),
                header(r, "last-modified"),
                r.uri().toString());
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }
}
