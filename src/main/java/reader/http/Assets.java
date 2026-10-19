package reader.http;

import java.io.InputStream;

/** Serves the two static files from inside the jar. Nothing is built, bundled or minified. */
public final class Assets {

    private Assets() {
    }

    public static Router.Resp serve(String relativePath, String notFoundPage) {
        if (relativePath == null || relativePath.isBlank()
                || relativePath.contains("..") || relativePath.startsWith("/")) {
            return Router.Resp.html(404, notFoundPage);
        }
        try (InputStream in = Assets.class.getResourceAsStream("/static/" + relativePath)) {
            if (in == null) return Router.Resp.html(404, notFoundPage);
            Router.Resp r = Router.Resp.raw(200, contentType(relativePath), in.readAllBytes());
            // Served from this machine, so caching buys nothing and only risks leaving a
            // browser on a stale copy after the program is updated.
            r.headers.put("Cache-Control", "no-store");
            return r;
        } catch (Exception e) {
            return Router.Resp.html(404, notFoundPage);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
