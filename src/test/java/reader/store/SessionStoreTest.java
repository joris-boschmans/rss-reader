package reader.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reader.TestDb;
import reader.model.Item;
import reader.model.Sitting;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The finite sitting is the load-bearing idea in this app, so it gets the most tests. */
class SessionStoreTest {

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

    private List<Long> fiftyPosts() {
        Instant base = Instant.now().minus(60, ChronoUnit.DAYS);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(t.addPost("post-" + i, base.plus(i, ChronoUnit.DAYS)));
        }
        return ids;
    }

    @Test
    void aSittingIsExactlyAsLongAsTheSettingSaysAndStartsWithTheNewest() {
        List<Long> ids = fiftyPosts();
        Sitting s = t.sittings.begin().orElseThrow();

        assertEquals(20, s.total());
        assertEquals(0, s.position());
        assertEquals(ids.get(49), t.sittings.currentItem().orElseThrow().id(),
                "newest first is the default");
    }

    @Test
    void postsArrivingMidSittingCannotJoinIt() {
        fiftyPosts();
        t.sittings.begin().orElseThrow();

        for (int i = 0; i < 10; i++) t.addPost("fresh-" + i, Instant.now());

        assertEquals(20, t.sittings.current().orElseThrow().total(),
                "without a snapshot, done would just be a scroll position that refills");
    }

    @Test
    void theOldestFirstSettingIsHonouredWhenChoosingAndWhenShowing() {
        List<Long> ids = fiftyPosts();
        t.settings.save(10, Settings.OLDEST, Settings.NEVER);

        Sitting s = t.sittings.begin().orElseThrow();
        assertEquals(10, s.total());
        assertEquals(ids.get(0), t.sittings.currentItem().orElseThrow().id());
    }

    @Test
    void steppingForwardMarksThePostReadAndSteppingBackUndoesIt() {
        fiftyPosts();
        t.sittings.begin().orElseThrow();

        long first = t.sittings.currentItem().orElseThrow().id();
        assertFalse(t.sittings.advance());
        assertNotNull(t.readAt(first), "passing a post marks it read");

        long second = t.sittings.currentItem().orElseThrow().id();
        t.sittings.back();

        assertEquals(first, t.sittings.currentItem().orElseThrow().id(), "back returns the same post");
        assertNull(t.readAt(first), "and un-marks it, so a mis-click costs nothing");
        assertEquals(0, t.sittings.current().orElseThrow().position());
        assertNull(t.readAt(second));
    }

    @Test
    void backAtTheVeryStartDoesNothingAtAll() {
        fiftyPosts();
        t.sittings.begin().orElseThrow();
        long first = t.sittings.currentItem().orElseThrow().id();

        t.sittings.back();

        assertEquals(0, t.sittings.current().orElseThrow().position());
        assertEquals(first, t.sittings.currentItem().orElseThrow().id());
    }

    @Test
    void passingTheLastPostEndsTheSitting() {
        for (int i = 0; i < 3; i++) t.addPost("post-" + i, Instant.now().minusSeconds(i));
        t.settings.save(10, Settings.NEWEST, Settings.NEVER);
        t.sittings.begin().orElseThrow();

        assertFalse(t.sittings.advance());
        assertFalse(t.sittings.advance());
        assertTrue(t.sittings.advance(), "the third step finishes it");

        assertTrue(t.sittings.current().isEmpty(), "and leaves nothing open");
        assertEquals(0, t.items.countUnread());
    }

    @Test
    void leavingEarlyLeavesEverythingNotYetSeenUnread() {
        fiftyPosts();
        t.sittings.begin().orElseThrow();
        long first = t.sittings.currentItem().orElseThrow().id();
        t.sittings.advance();
        long second = t.sittings.currentItem().orElseThrow().id();

        t.sittings.finish();

        assertTrue(t.sittings.current().isEmpty());
        assertNotNull(t.readAt(first));
        assertNull(t.readAt(second), "a post you never saw must stay unread");
        assertEquals(49, t.items.countUnread());
    }

    @Test
    void aRestartResumesAtTheSamePostWithTheSameHistory() {
        fiftyPosts();
        t.sittings.begin().orElseThrow();
        t.sittings.advance();
        t.sittings.advance();
        long expected = t.sittings.currentItem().orElseThrow().id();

        t = t.reopen();

        Sitting resumed = t.sittings.current().orElseThrow();
        assertEquals(2, resumed.position());
        assertEquals(20, resumed.total());
        assertEquals(expected, t.sittings.currentItem().orElseThrow().id());
        assertEquals(48, t.items.countUnread(), "exactly the two posts already passed are read");
    }

    @Test
    void startingOverEndsWhateverWasOpen() {
        fiftyPosts();
        long firstSitting = t.sittings.begin().orElseThrow().id();
        t.sittings.advance();

        long secondSitting = t.sittings.begin().orElseThrow().id();

        assertTrue(secondSitting != firstSitting);
        assertEquals(0, t.sittings.current().orElseThrow().position());
        assertEquals(20, t.sittings.current().orElseThrow().total());
    }

    @Test
    void thereIsNothingToBeginWhenNothingIsUnread() {
        assertTrue(t.sittings.begin().isEmpty());
        assertTrue(t.sittings.current().isEmpty());
    }

    @Test
    void savingWhileReadingKeepsThePostInFront() {
        t.addPost("one", Instant.now());
        t.sittings.begin().orElseThrow();

        t.sittings.toggleSaveOnCurrent();
        Item saved = t.sittings.currentItem().orElseThrow();
        assertTrue(saved.isSaved());

        t.sittings.toggleSaveOnCurrent();
        assertFalse(t.sittings.currentItem().orElseThrow().isSaved());
    }
}
