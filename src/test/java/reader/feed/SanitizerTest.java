package reader.feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Feed HTML is untrusted input rendered into a browser, so it gets its own hostile-input test. */
class SanitizerTest {

    private static final String HOSTILE = """
            <p>Real text that should survive.</p>
            <script>alert('nope')</script>
            <p onclick="steal()">Handler should go.</p>
            <iframe src="https://evil.example/frame"></iframe>
            <img src="https://tracker.example/pixel.gif" width="1" height="1" alt="">
            <a href="/relative/post">A relative link</a>
            <img src="/pictures/cat.jpg" alt="A cat">
            """;

    @Test
    void removesScriptsHandlersFramesAndTrackingPixels() {
        String html = Sanitizer.clean(HOSTILE, "https://example.com/posts/1").html();

        assertFalse(html.contains("<script"), "scripts must not survive");
        assertFalse(html.contains("alert("), "script bodies must not survive");
        assertFalse(html.contains("onclick"), "event handlers must not survive");
        assertFalse(html.contains("<iframe"), "frames must not survive");
        assertFalse(html.contains("pixel.gif"), "a 1x1 tracking image must be dropped entirely");
        assertTrue(html.contains("Real text that should survive."));
    }

    @Test
    void absolutisesRelativeLinksAgainstThePost() {
        String html = Sanitizer.clean(HOSTILE, "https://example.com/posts/1").html();
        assertTrue(html.contains("https://example.com/relative/post"),
                "relative links must be resolved: " + html);
    }

    @Test
    void everyLinkOpensInANewTabSoYouNeverLoseYourPlace() {
        String html = Sanitizer.clean("<a href=\"https://example.com\">x</a>", "https://example.com").html();
        assertTrue(html.contains("target=\"_blank\""));
        assertTrue(html.contains("rel=\"noopener\""));
    }

    @Test
    void imagesBecomePlaceholdersSoNoneAreFetchedOnLoad() {
        String html = Sanitizer.clean(HOSTILE, "https://example.com/posts/1").html();

        assertFalse(html.contains("<img"), "no image element may remain: " + html);
        assertTrue(html.contains("deferred-image"));
        assertTrue(html.contains("https://example.com/pictures/cat.jpg"),
                "the real address is kept for when it is asked for");
        assertTrue(html.contains("A cat"), "the alt text carries the meaning in the meantime");
    }

    @Test
    void anImageInsideALinkBecomesASpanSoTheMarkupStaysValid() {
        String html = Sanitizer.clean(
                "<a href=\"https://example.com/post\"><img src=\"https://example.com/a.png\" alt=\"A\"></a>",
                "https://example.com").html();
        assertTrue(html.contains("<span class=\"deferred-image\""), html);
    }

    @Test
    void reportsTextLengthSoSummaryOnlyFeedsCanBeNamedHonestly() {
        Sanitizer.Clean thin = Sanitizer.clean("<p>Three words only.</p>", "https://example.com");
        assertTrue(thin.textLength() < Sanitizer.SUMMARY_THRESHOLD);
        assertEquals(thin.textLength(), Sanitizer.textLength(thin.html()));

        String long_ = "<p>" + "word ".repeat(200) + "</p>";
        assertTrue(Sanitizer.clean(long_, "https://example.com").textLength()
                >= Sanitizer.SUMMARY_THRESHOLD);
    }

    @Test
    void emptyContentIsNotAFailure() {
        Sanitizer.Clean clean = Sanitizer.clean(null, "https://example.com");
        assertEquals("", clean.html());
        assertEquals("", clean.excerpt());
        assertEquals(0, clean.textLength());
    }

    @Test
    void excerptIsTrimmedAtAWordBoundary() {
        String excerpt = Sanitizer.clean("<p>" + "alpha ".repeat(100) + "</p>", "https://example.com")
                .excerpt();
        assertTrue(excerpt.endsWith("…"));
        assertTrue(excerpt.length() <= 205, "excerpt was " + excerpt.length() + " characters");
    }
}
