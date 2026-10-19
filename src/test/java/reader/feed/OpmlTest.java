package reader.feed;

import org.junit.jupiter.api.Test;
import reader.Fixtures;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpmlTest {

    @Test
    void readsAFileExportedFromAnotherReader() {
        Opml.Parsed parsed = Opml.parse(Fixtures.bytes("subscriptions.opml"));

        List<String> titles = parsed.entries().stream().map(Opml.Entry::title).toList();
        assertEquals(List.of("The Verge", "Stratechery", "Atom Notes"), titles,
                "feeds inside folders count, and the same feed twice counts once");
        assertEquals("https://stratechery.com/feed/", parsed.entries().get(1).address());
    }

    @Test
    void namesTheEntriesItCouldNotRead() {
        Opml.Parsed parsed = Opml.parse(Fixtures.bytes("subscriptions.opml"));
        assertEquals(List.of("A broken entry"), parsed.skipped());
    }

    @Test
    void aFileThatIsNotAnExportIsAnEmptyResultRatherThanAFailure() {
        Opml.Parsed parsed = Opml.parse("not a file we understand".getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.entries().isEmpty());
    }
}
