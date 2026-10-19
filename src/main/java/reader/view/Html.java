package reader.view;

/** The whole templating layer: escape, a page shell, and the shared header. */
public final class Html {

    private Html() {
    }

    private static final String SHELL = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>%s</title>
            <link rel="stylesheet" href="/static/reader.css">
            %s</head>
            <body>
            %s
            <script src="/static/reader.js" defer></script>
            </body>
            </html>
            """;

    public static String page(String title, String body) {
        return page(title, "", body);
    }

    public static String page(String title, String extraHead, String body) {
        return SHELL.formatted(esc(title), extraHead, body);
    }

    /** Makes the page poll itself into its next state with no JavaScript and no spinner. */
    public static String refreshAfter(int seconds) {
        return "<meta http-equiv=\"refresh\" content=\"" + seconds + "\">\n";
    }

    /** Present everywhere except the reading view, which has no chrome at all. */
    public static String header(String active) {
        StringBuilder b = new StringBuilder();
        b.append("<header class=\"top\"><a class=\"brand\" href=\"/\">")
                .append(esc(Copy.APP_NAME)).append("</a><nav>");
        navLink(b, active, "saved", "/saved", Copy.NAV_SAVED);
        navLink(b, active, "feeds", "/feeds", Copy.NAV_FEEDS);
        navLink(b, active, "settings", "/settings", Copy.NAV_SETTINGS);
        return b.append("</nav></header>").toString();
    }

    private static void navLink(StringBuilder b, String active, String key, String href, String label) {
        b.append("<a href=\"").append(href).append('"');
        if (key.equals(active)) b.append(" aria-current=\"page\"");
        b.append('>').append(esc(label)).append("</a>");
    }

    public static String main(String inner) {
        return "<main>\n" + inner + "\n</main>";
    }

    public static String notice(String html) {
        return "<p class=\"notice\">" + html + "</p>";
    }

    /** A form whose only job is to POST one thing — the no-JavaScript button. */
    public static String postButton(String action, String label, String cssClass, String id) {
        return "<form class=\"act\" method=\"post\" action=\"" + action + "\""
                + (id == null ? "" : " id=\"" + id + "\"") + ">"
                + "<button class=\"" + cssClass + "\">" + esc(label) + "</button></form>";
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
