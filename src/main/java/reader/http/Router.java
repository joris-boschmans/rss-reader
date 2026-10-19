package reader.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fourteen routes and one reader do not justify a framework. This is the whole web layer:
 * pattern matching, form decoding and a response writer.
 */
public final class Router implements HttpHandler {

    // ---- Request ---------------------------------------------------------

    public static final class Req {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        public final Map<String, String> form = new LinkedHashMap<>();
        public final Map<String, String> params = new LinkedHashMap<>();
        public byte[] file;
        public String fileName;

        private final Headers headers;

        Req(String method, String path, Map<String, String> query, Headers headers) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
        }

        /** Never null: an absent header reads as the empty string. */
        public String header(String name) {
            if (headers == null) return "";
            String value = headers.getFirst(name);
            return value == null ? "" : value;
        }

        public String q(String key, String fallback) {
            return query.getOrDefault(key, fallback);
        }

        public String f(String key) {
            return form.getOrDefault(key, "");
        }

        public long idParam() {
            try {
                return Long.parseLong(params.getOrDefault("id", "-1"));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    // ---- Response --------------------------------------------------------

    public static final class Resp {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public final Map<String, String> headers = new LinkedHashMap<>();

        private Resp(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public static Resp html(String body) {
            return html(200, body);
        }

        public static Resp html(int status, String body) {
            return new Resp(status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        }

        /** An action that changed something but should leave the page exactly where it is. */
        public static Resp nothingToShow() {
            return new Resp(204, "text/plain; charset=utf-8", new byte[0]);
        }

        /** POST then redirect, so a reload never repeats an action. */
        public static Resp seeOther(String location) {
            Resp r = new Resp(303, "text/plain; charset=utf-8", new byte[0]);
            r.headers.put("Location", location);
            return r;
        }

        public static Resp raw(int status, String contentType, byte[] body) {
            return new Resp(status, contentType, body);
        }
    }

    public interface Handler {
        Resp handle(Req req) throws Exception;
    }

    public interface ErrorPages {
        String notFound();

        String broke();
    }

    // ---- Routing ---------------------------------------------------------

    private record Route(String method, Pattern pattern, List<String> names, Handler handler) {
    }

    private final List<Route> routes = new ArrayList<>();
    private final ErrorPages errors;

    public Router(ErrorPages errors) {
        this.errors = errors;
    }

    public Router get(String pattern, Handler h) {
        return add("GET", pattern, h);
    }

    public Router post(String pattern, Handler h) {
        return add("POST", pattern, h);
    }

    public Router add(String method, String pattern, Handler handler) {
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        for (String segment : pattern.split("/", -1)) {
            if (segment.isEmpty()) continue;
            regex.append('/');
            if (segment.startsWith("{") && segment.endsWith("}")) {
                names.add(segment.substring(1, segment.length() - 1));
                regex.append("([^/]+)");
            } else if (segment.equals("*")) {
                names.add("rest");
                regex.append("(.*)");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        if (regex.length() == 1) regex.append('/');
        regex.append("/?$");
        routes.add(new Route(method, Pattern.compile(regex.toString()), names, handler));
        return this;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Resp response;
        try {
            response = dispatch(exchange);
        } catch (Exception e) {
            e.printStackTrace();
            response = Resp.html(500, errors.broke());
        }
        write(exchange, response);
    }

    private Resp dispatch(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        Req req = new Req(method, rawPath, decodeQuery(exchange.getRequestURI().getRawQuery()),
                exchange.getRequestHeaders());

        byte[] body = exchange.getRequestBody().readAllBytes();
        if ("POST".equals(method)) readForm(exchange, req, body);

        for (Route route : routes) {
            Matcher m = route.pattern().matcher(rawPath);
            if (!m.matches() || !route.method().equals(method)) continue;
            for (int i = 0; i < route.names().size(); i++) {
                req.params.put(route.names().get(i), decode(m.group(i + 1)));
            }
            return route.handler().handle(req);
        }
        return Resp.html(404, errors.notFound());
    }

    private static void write(HttpExchange exchange, Resp r) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", r.contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        r.headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        exchange.sendResponseHeaders(r.status, r.body.length == 0 ? -1 : r.body.length);
        if (r.body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(r.body);
            }
        }
        exchange.close();
    }

    // ---- Form decoding ---------------------------------------------------

    private static void readForm(HttpExchange exchange, Req req, byte[] body) {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null) return;
        if (type.startsWith("application/x-www-form-urlencoded")) {
            req.form.putAll(decodeQuery(new String(body, StandardCharsets.UTF_8)));
        } else if (type.startsWith("multipart/form-data")) {
            readMultipart(type, body, req);
        }
    }

    /** Just enough multipart to receive one file from a file picker alongside text fields. */
    private static void readMultipart(String contentType, byte[] body, Req req) {
        int at = contentType.indexOf("boundary=");
        if (at < 0) return;
        String boundary = contentType.substring(at + "boundary=".length()).trim();
        if (boundary.startsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1);

        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<int[]> parts = new ArrayList<>();
        int from = indexOf(body, marker, 0);
        while (from >= 0) {
            int start = from + marker.length;
            int next = indexOf(body, marker, start);
            if (next < 0) break;
            parts.add(new int[]{start, next});
            from = next;
        }

        for (int[] span : parts) {
            int start = span[0];
            int end = span[1];
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            if (headerEnd < 0 || headerEnd > end) continue;
            String headers = new String(body, start, headerEnd - start, StandardCharsets.ISO_8859_1);
            int contentStart = headerEnd + 4;
            int contentEnd = end - 2; // the CRLF that precedes the next boundary
            if (contentEnd < contentStart) continue;

            String name = between(headers, "name=\"", "\"");
            String filename = between(headers, "filename=\"", "\"");
            if (name == null) continue;
            if (filename != null) {
                req.file = java.util.Arrays.copyOfRange(body, contentStart, contentEnd);
                req.fileName = filename;
            } else {
                req.form.put(name, new String(body, contentStart, contentEnd - contentStart,
                        StandardCharsets.UTF_8));
            }
        }
    }

    private static String between(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) return null;
        int b = s.indexOf(close, a + open.length());
        return b < 0 ? null : s.substring(a + open.length(), b);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static Map<String, String> decodeQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
