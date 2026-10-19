package reader.feed;

import org.junit.jupiter.api.Test;
import reader.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    @Test
    void readsAnOlderStyleFeed() throws Exception {
        Parser.Parsed parsed = Parser.parse(Fixtures.bytes("rss2.xml"), "application/xml");

        assertEquals("The Example Review", parsed.title());
        assertEquals("https://example.com/", parsed.siteUrl());
        assertEquals(2, parsed.items().size());

        Parser.ParsedItem first = parsed.items().get(0);
        assertEquals("On quiet software", first.title());
        assertEquals("https://example.com/quiet", first.url());
        assertEquals("tag:example.com,2026:quiet", first.guid());
        assertNotNull(first.publishedAt());
        assertTrue(first.contentHtml().contains("without demanding attention"));
    }

    @Test
    void readsTheOtherDialectToo() throws Exception {
        Parser.Parsed parsed = Parser.parse(Fixtures.bytes("atom.xml"), "application/atom+xml");

        assertEquals("Atom Notes", parsed.title());
        assertEquals(2, parsed.items().size());
        assertEquals("The first note", parsed.items().get(0).title());
        assertTrue(parsed.items().get(0).contentHtml().contains("Content of the first note"));
        assertTrue(parsed.items().get(1).contentHtml().contains("Only a summary here"),
                "a summary stands in when there is no full content");
    }

    @Test
    void postsWithNoIdentityOfTheirOwnStillGetAStableOne() throws Exception {
        Parser.Parsed first = Parser.parse(Fixtures.bytes("noguid.xml"), "application/xml");
        Parser.Parsed second = Parser.parse(Fixtures.bytes("noguid.xml"), "application/xml");

        String a = first.items().get(0).guid();
        String b = first.items().get(1).guid();
        assertNotEquals(a, b, "two different posts must not collide");
        assertEquals(a, second.items().get(0).guid(),
                "the same post must get the same identity every time, or it would arrive twice");
    }

    @Test
    void somethingThatIsNotAFeedIsRejectedRatherThanGuessed() {
        byte[] page = "<!doctype html><html><body><h1>Just a page</h1></body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> Parser.parse(page, "text/html"));
    }
}
