package reader.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reader.TestDb;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStoreTest {

    @TempDir
    Path dir;
    TestDb t;

    @BeforeEach
    void setUp() {
        t = new TestDb(dir);
    }

    @AfterEach
    void tearDown() {
        t.close();
    }

    private ItemStore.Incoming post(String guid) {
        return new ItemStore.Incoming(guid, "https://example.com/a", "A post", null,
                Instant.now(), "<p>Body</p>", "Body");
    }

    @Test
    void theSamePostSeenTwiceIsStoredOnce() {
        assertTrue(t.items.insertIfNew(t.feedId, post("same")));
        assertFalse(t.items.insertIfNew(t.feedId, post("same")),
                "a second sighting of the same post must add nothing");
        assertEquals(1, t.items.countUnread());
    }

    @Test
    void twoFeedsMayShareAnIdentityWithoutColliding() {
        long other = t.feeds.add("https://other.example/feed", "Other", null).id();
        assertTrue(t.items.insertIfNew(t.feedId, post("shared")));
        assertTrue(t.items.insertIfNew(other, post("shared")));
        assertEquals(2, t.items.countUnread());
    }

    @Test
    void unreadPostsComeBackInTheChosenOrder() {
        Instant now = Instant.now();
        t.addPost("oldest", now.minus(3, ChronoUnit.DAYS));
        long middle = t.addPost("middle", now.minus(2, ChronoUnit.DAYS));
        long newest = t.addPost("newest", now.minus(1, ChronoUnit.DAYS));

        assertEquals(List.of(newest, middle), t.items.unreadIds(2, true));
        assertEquals(2, t.items.unreadIds(2, false).size());
        assertEquals("oldest", t.items.byId(t.items.unreadIds(1, false).get(0)).orElseThrow().title());
    }

    @Test
    void readingAPostTakesItOutOfTheUnreadCountAndPuttingItBackRestoresIt() {
        long id = t.addPost("one", Instant.now());
        t.items.markRead(id);
        assertEquals(0, t.items.countUnread());

        t.items.clearRead(id);
        assertEquals(1, t.items.countUnread());
    }

    @Test
    void savingIsReversible() {
        long id = t.addPost("one", Instant.now());
        t.items.setSaved(id, true);
        assertEquals(1, t.items.saved().size());

        t.items.setSaved(id, false);
        assertTrue(t.items.saved().isEmpty());
    }
}
