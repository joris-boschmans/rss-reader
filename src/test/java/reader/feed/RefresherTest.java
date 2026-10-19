package reader.feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reader.TestDb;
import reader.model.Feed;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefresherTest {

    @TempDir
    Path dir;
    TestDb t;
    LocalSite site;
    Refresher refresher;

    @BeforeEach
    void setUp() throws IOException {
        t = new TestDb(dir);
        site = new LocalSite();
        refresher = new Refresher(new Fetcher(), t.feeds, t.items, t.settings);
    }

    @AfterEach
    void tearDown() {
        refresher.close();
        site.close();
        t.close();
    }

    @Test
    void aHealthyFeedBringsInItsPostsOnce() {
        Feed feed = t.feeds.add(site.at("/feed"), null, null);

        assertEquals(2, refresher.refresh(feed));
        assertEquals(2, t.items.countUnread());

        Feed after = t.feeds.byId(feed.id()).orElseThrow();
        assertEquals("The Example Review", after.title(), "the feed names itself");
        assertNull(after.fetchError());

        assertEquals(0, refresher.refresh(after), "nothing changed, so nothing is added");
        assertEquals(2, t.items.countUnread());
        assertEquals(1, site.bodiesSent.get());
    }

    @Test
    void oneBrokenFeedDoesNotStopTheOthers() {
        t.feeds.add(site.at("/feed"), null, null);
        t.feeds.add(site.at("/broken"), "A bad day", null);
        t.feeds.add(site.at("/nonsense"), "Unreadable", null);
        t.feeds.add("http://127.0.0.1:1/feed", "Nowhere", null);

        assertEquals(2, refresher.refreshAll(), "the healthy feed still delivers");

        assertTrue(broken("A bad day"), "a failing server is recorded against its own feed");
        assertTrue(broken("Unreadable"), "unreadable content is recorded against its own feed");
        assertTrue(broken("Nowhere"), "an unreachable address is recorded against its own feed");
    }

    @Test
    void theHealthyFeedIsNotMarkedBrokenByItsNeighbours() {
        Feed healthy = t.feeds.add(site.at("/feed"), null, null);
        t.feeds.add(site.at("/broken"), "A bad day", null);

        refresher.refreshAll();

        assertFalse(t.feeds.byId(healthy.id()).orElseThrow().broken());
        assertNotNull(t.feeds.byId(healthy.id()).orElseThrow().lastFetchedAt());
    }

    @Test
    void aCheckIsRemembered() {
        t.feeds.add(site.at("/feed"), null, null);
        refresher.refreshAll();

        assertNotNull(t.settings.lastCheckedAt());
        assertEquals(2, t.settings.lastNewCount());
    }

    private boolean broken(String title) {
        return t.feeds.all().stream()
                .filter(f -> title.equals(f.title()))
                .anyMatch(Feed::broken);
    }
}
