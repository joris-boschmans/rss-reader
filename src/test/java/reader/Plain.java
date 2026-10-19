package reader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** The first rule, as an assertion: no jargon ever reaches the screen. */
public final class Plain {

    public static final List<String> JARGON =
            List.of("session", "rss", "guid", "xml", "http", "url", "304", "stack trace");

    private Plain() {
    }

    public static void assertPlain(String where, String text) {
        String lower = text.toLowerCase();
        for (String word : JARGON) {
            assertFalse(lower.contains(word),
                    where + " says \"" + word + "\" to the reader: " + text);
        }
    }
}
