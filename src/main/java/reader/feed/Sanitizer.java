package reader.feed;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Feed HTML is untrusted input that ends up in your browser, so it is cleaned once at
 * ingest rather than trusted at render.
 *
 * <p>Three things happen here beyond the allowlist: relative addresses are resolved
 * against the post, every link is set to open in a new tab so you never lose your place,
 * and every image is replaced by a muted placeholder. That last one is why no image
 * request fires while you read — the real image is only fetched when you ask for it.
 */
public final class Sanitizer {

    /** Below this many characters of text, a post is a summary rather than the real thing. */
    public static final int SUMMARY_THRESHOLD = 400;

    private static final int EXCERPT_CHARS = 200;

    public record Clean(String html, String excerpt, int textLength) {
    }

    private Sanitizer() {
    }

    public static Clean clean(String html, String baseUrl) {
        if (html == null || html.isBlank()) return new Clean("", "", 0);

        Document dirty = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        Document clean = new Cleaner(safelist()).clean(dirty);

        for (Element img : clean.select("img")) {
            deferImage(img);
        }
        for (Element a : clean.select("a[href]")) {
            a.attr("target", "_blank");
            a.attr("rel", "noopener");
        }

        String text = normalise(clean.body().text());
        return new Clean(clean.body().html(), excerptOf(text), text.length());
    }

    public static int textLength(String cleanedHtml) {
        if (cleanedHtml == null || cleanedHtml.isBlank()) return 0;
        return normalise(Jsoup.parse(cleanedHtml).text()).length();
    }

    private static Safelist safelist() {
        // Relaxed, minus nothing and plus nothing: it already excludes script, iframe,
        // every on* handler, style and form controls, and it absolutises what it keeps.
        return Safelist.relaxed();
    }

    /** Tracking pixels go entirely; everything else becomes a placeholder you can open. */
    private static void deferImage(Element img) {
        if (isTrackingPixel(img)) {
            img.remove();
            return;
        }
        String src = img.attr("abs:src");
        if (src.isBlank()) src = img.attr("src");
        if (src.isBlank()) {
            img.remove();
            return;
        }
        String alt = img.attr("alt").trim();
        String label = alt.isEmpty() ? "Image" : "Image — " + alt;

        // An <a> inside an <a> is invalid, so a wrapped image becomes a plain span.
        boolean insideLink = img.closest("a") != null;
        Element placeholder = new Element(insideLink ? "span" : "a");
        placeholder.addClass("deferred-image");
        placeholder.attr("data-src", src);
        if (!insideLink) {
            placeholder.attr("href", src);
            placeholder.attr("target", "_blank");
            placeholder.attr("rel", "noopener");
        }
        placeholder.text(label);
        img.replaceWith(placeholder);
    }

    private static boolean isTrackingPixel(Element img) {
        return small(img.attr("width")) && small(img.attr("height"));
    }

    private static boolean small(String dimension) {
        if (dimension == null || dimension.isBlank()) return false;
        try {
            return Integer.parseInt(dimension.replaceAll("[^0-9]", "").isEmpty()
                    ? "999" : dimension.replaceAll("[^0-9]", "")) <= 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String normalise(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String excerptOf(String text) {
        if (text.length() <= EXCERPT_CHARS) return text;
        int cut = text.lastIndexOf(' ', EXCERPT_CHARS);
        if (cut < EXCERPT_CHARS / 2) cut = EXCERPT_CHARS;
        return text.substring(0, cut).trim() + "…";
    }
}
