package reader.feed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import reader.Fixtures;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** A throwaway site on a free port: one healthy feed, and several ways of going wrong. */
public final class LocalSite implements AutoCloseable {

    public static final String TOKEN = "\"v1\"";

    private final HttpServer server;
    private final boolean offersAFeed;

    public final AtomicInteger feedRequests = new AtomicInteger();
    public final AtomicInteger bodiesSent = new AtomicInteger();

    public LocalSite() throws IOException {
        this(true);
    }

    public LocalSite(boolean offersAFeed) throws IOException {
        this.offersAFeed = offersAFeed;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/feed", exchange -> {
            if (!offersAFeed) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            feedRequests.incrementAndGet();
            if (TOKEN.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = Fixtures.bytes("rss2.xml");
            bodiesSent.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.getResponseHeaders().set("ETag", TOKEN);
            exchange.sendResponseHeaders(200, body.length);
            send(exchange, body);
        });

        server.createContext("/broken", exchange -> {
            byte[] body = "the server is having a bad day".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            send(exchange, body);
        });

        server.createContext("/nonsense", exchange -> {
            byte[] body = "<rss><this is not".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            send(exchange, body);
        });

        server.createContext("/page", exchange -> html(exchange, offersAFeed
                ? "<!doctype html><html><head><title>A site</title>"
                + "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed\">"
                + "</head><body>Hello</body></html>"
                : "<!doctype html><html><head><title>A plain page</title></head>"
                + "<body>Nothing to follow here.</body></html>"));

        server.createContext("/multi", exchange -> html(exchange,
                "<!doctype html><html><head>"
                        + "<link rel=\"alternate\" type=\"application/rss+xml\" "
                        + "title=\"Everything\" href=\"/feed\">"
                        + "<link rel=\"alternate\" type=\"application/atom+xml\" "
                        + "title=\"Just the long pieces\" href=\"/feed-long\">"
                        + "</head><body>Hello</body></html>"));

        server.setExecutor(null);
        server.start();
    }

    private static void html(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        send(exchange, bytes);
    }

    private static void send(HttpExchange exchange, byte[] body) throws IOException {
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }

    public String at(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public String host() {
        return "127.0.0.1";
    }

    public boolean offersAFeed() {
        return offersAFeed;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
