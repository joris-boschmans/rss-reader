package reader.feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reader.TestDb;
import reader.store.Settings;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweeperTest {

    @TempDir
    Path dir;
    TestDb t;
    Sweeper sweeper;

    @BeforeEach
    void setUp() {
        t = new TestDb(dir);
        sweeper = new Sweeper(t.items, t.settings);
    }

    @AfterEach
    void tearDown() {
        t.close();
    }

    @Test
    void nothingIsDeletedUnlessYouChooseIt() {
        t.addPost("ancient", Instant.now().minus(400, ChronoUnit.DAYS));
        assertEquals(0, sweeper.sweep(), "the default must never remove anything");
        assertEquals(1, t.items.countUnread());
    }

    @Test
    void forgettingTwoWeeksRemovesOnlyOldUnreadPosts() {
        long old = t.addPost("old-unread", Instant.now().minus(20, ChronoUnit.DAYS));
        long recent = t.addPost("recent-unread", Instant.now().minus(3, ChronoUnit.DAYS));
        long kept = t.addPost("old-saved", Instant.now().minus(30, ChronoUnit.DAYS));
        t.items.setSaved(kept, true);
        long read = t.addPost("old-read", Instant.now().minus(30, ChronoUnit.DAYS));
        t.items.markRead(read);

        t.settings.save(20, Settings.NEWEST, "2w");
        assertEquals(1, sweeper.sweep());

        assertTrue(t.items.byId(old).isEmpty(), "the old unread post goes");
        assertTrue(t.items.byId(recent).isPresent(), "a recent one stays");
        assertTrue(t.items.byId(kept).isPresent(), "a saved post survives whatever its age");
        assertTrue(t.items.byId(read).isPresent(), "an already-read post is not unread");
    }

    @Test
    void aPostCaughtUpInAnOpenSittingIsNotPulledOutFromUnderYou() {
        long old = t.addPost("old-unread", Instant.now().minus(20, ChronoUnit.DAYS));
        t.sittings.begin().orElseThrow();
        t.settings.save(20, Settings.NEWEST, "1w");

        assertEquals(0, sweeper.sweep());
        assertTrue(t.items.byId(old).isPresent());
    }
}
