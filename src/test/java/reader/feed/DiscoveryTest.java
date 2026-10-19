package reader.feed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryTest {

    private static final String NONE = """
            <!doctype html><html><head><title>A plain page</title></head>
            <body><p>Nothing to follow here.</p></body></html>
            """;

    private static final String ONE = """
            <!doctype html><html><head>
            <link rel="alternate" type="application/rss+xml" title="The Verge" href="/rss/index.xml">
            </head><body></body></html>
            """;

    private static final String MANY = """
            <!doctype html><html><head>
            <link rel="alternate" type="application/rss+xml" title="The Verge — All Posts" href="/rss/index.xml">
            <link rel="alternate" type="application/atom+xml" title="The Verge — Tech" href="/tech/rss">
            <link rel="alternate" type="text/html" title="Not a feed" href="/other">
            <link rel="stylesheet" href="/style.css">
            </head><body></body></html>
            """;

    @Test
    void findsNothingOnAPageThatOffersNothing() {
        assertTrue(Discovery.linksIn(NONE, "https://example.com/").isEmpty());
    }

    @Test
    void findsASingleFeedAndResolvesItsAddress() {
        List<Discovery.Candidate> found = Discovery.linksIn(ONE, "https://www.theverge.com/");
        assertEquals(1, found.size());
        assertEquals("https://www.theverge.com/rss/index.xml", found.get(0).address());
        assertEquals("The Verge", found.get(0).title());
    }

    @Test
    void offersEveryChoiceByNameAndIgnoresLinksThatAreNotFeeds() {
        List<Discovery.Candidate> found = Discovery.linksIn(MANY, "https://www.theverge.com/");
        assertEquals(2, found.size());
        assertEquals("The Verge — All Posts", found.get(0).title());
        assertEquals("The Verge — Tech", found.get(1).title());
    }

    @Test
    void theSameFeedListedTwiceIsOfferedOnce() {
        String twice = """
                <link rel="alternate" type="application/rss+xml" title="A" href="/feed">
                <link rel="alternate" type="application/rss+xml" title="B" href="/feed">
                """;
        assertEquals(1, Discovery.linksIn(twice, "https://example.com/").size());
    }

    @Test
    void aBareDomainIsTriedSecurelyFirst() {
        assertEquals(List.of("https://stratechery.com", "http://stratechery.com"),
                Discovery.attempts(Discovery.normalise("  stratechery.com ")));
        assertEquals(List.of("https://example.com/feed"),
                Discovery.attempts(Discovery.normalise("https://example.com/feed")));
        assertEquals(List.of("https://example.com/feed"),
                Discovery.attempts(Discovery.normalise("feed://example.com/feed")));
    }
}
