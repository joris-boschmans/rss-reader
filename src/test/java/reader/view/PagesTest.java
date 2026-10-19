package reader.view;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reader.Plain;
import reader.TestDb;
import reader.feed.Fetcher;
import reader.feed.Refresher;
import reader.feed.Scheduler;
import reader.feed.Sweeper;
import reader.store.ItemStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PagesTest {

    @TempDir
    Path dir;
    TestDb t;
    Refresher refresher;
    Scheduler scheduler;
    Pages pages;

    @BeforeEach
    void setUp() {
        t = new TestDb(dir);
        refresher = new Refresher(new Fetcher(), t.feeds, t.items, t.settings);
        scheduler = new Scheduler(refresher, new Sweeper(t.items, t.settings));
        pages = new Pages(t.feeds, t.items, t.sittings, t.settings, refresher, scheduler);
    }

    @AfterEach
    void tearDown() {
        refresher.close();
        scheduler.close();
        t.close();
    }

    private static Document parse(String html) {
        return Jsoup.parse(html);
    }

    // ---- Rule one: no jargon anywhere on screen -------------------------

    @Test
    void noScreenShowsJargon() {
        t.addPost("A post worth reading", Instant.now());
        t.sittings.begin().orElseThrow();
        t.sittings.toggleSaveOnCurrent();

        List<String> screens = List.of(
                pages.start(),
                pages.read(),
                pages.done(),
                pages.feeds(null),
                pages.feeds(Copy.ADD_NONE),
                pages.settings(Copy.SETTINGS_SAVED),
                pages.saved(),
                pages.removeConfirm(t.feeds.byId(t.feedId).orElseThrow()),
                pages.notFound(),
                pages.broke());

        for (String screen : screens) {
            Plain.assertPlain("a screen", parse(screen).text());
        }
    }

    @Test
    void noScreenLeaksTheWordsBehindTheScenesEitherEvenInItsMarkup() {
        t.addPost("A post", Instant.now());
        t.sittings.begin().orElseThrow();

        for (String screen : List.of(pages.start(), pages.read(), pages.done(),
                pages.feeds(null), pages.settings(null), pages.saved())) {
            String lower = screen.toLowerCase();
            for (String word : List.of("session", "guid", "rss")) {
                assertFalse(lower.contains(word), "the markup says \"" + word + "\"");
            }
        }
    }

    // ---- Rule two: one prominent action per screen ----------------------

    @Test
    void theReadingViewHasExactlyOneProminentControl() {
        t.addPost("post one", Instant.now());
        t.addPost("post two", Instant.now().minusSeconds(60));
        t.sittings.begin().orElseThrow();
        t.sittings.advance();

        Document doc = parse(pages.read());

        assertEquals(1, doc.select(".primary").size(), "Next must be the only prominent control");
        assertEquals(Copy.NEXT, doc.selectFirst(".primary").text());
        assertNotNull(doc.selectFirst("#f-save"), "Save is there, quietly");
        assertNotNull(doc.selectFirst("#f-back"), "and so is the way back");
        assertEquals("link", doc.selectFirst("#f-save button").className());
        assertEquals("link", doc.selectFirst("#f-back button").className());
    }

    @Test
    void thereIsNoWayBackFromTheFirstPostBecauseThereIsNothingBehindIt() {
        t.addPost("only post", Instant.now());
        t.sittings.begin().orElseThrow();

        assertNull(parse(pages.read()).selectFirst("#f-back"));
    }

    @Test
    void theReadingViewDropsTheHeaderEntirely() {
        t.addPost("a post", Instant.now());
        t.sittings.begin().orElseThrow();

        assertNull(parse(pages.read()).selectFirst("header.top"));
        assertNotNull(parse(pages.start()).selectFirst("header.top"));
    }

    // ---- Done is a full stop --------------------------------------------

    @Test
    void doneOffersNoWayToKeepGoing() {
        for (int i = 0; i < 30; i++) t.addPost("post-" + i, Instant.now().minusSeconds(i));
        t.sittings.begin().orElseThrow();
        t.sittings.finish();

        Document doc = parse(pages.done());

        assertTrue(doc.select("form").isEmpty(), "no button on this page starts more reading");
        assertTrue(doc.select("a[href=/read]").isEmpty());
        assertTrue(doc.text().contains("You're done."));
        assertTrue(doc.text().contains("more are waiting"), "but it says so honestly");
    }

    @Test
    void doneSaysNothingAboutMoreWhenThereIsNoMore() {
        t.addPost("only post", Instant.now());
        t.sittings.begin().orElseThrow();
        t.sittings.advance();

        assertFalse(parse(pages.done()).text().contains("waiting"));
    }

    // ---- The five start states ------------------------------------------

    @Test
    void aFreshInstallPointsStraightAtTheOneThingToDo() {
        try (TestDb empty = new TestDb(dir.resolve("empty"))) {
            empty.feeds.remove(empty.feedId);
            Pages p = new Pages(empty.feeds, empty.items, empty.sittings, empty.settings,
                    refresher, scheduler);
            Document doc = parse(p.start());

            assertTrue(doc.text().contains(Copy.START_EMPTY));
            assertNotNull(doc.selectFirst("form.add input#address"));
            assertEquals(Copy.ADD_PLACEHOLDER, doc.selectFirst("#address").attr("placeholder"));
            assertNotNull(doc.selectFirst("form.import input[type=file]"));
        }
    }

    @Test
    void postsWaitingOffersExactlyOneButton() {
        t.addPost("a post", Instant.now());
        Document doc = parse(pages.start());

        assertTrue(doc.text().contains("1 post waiting."));
        assertEquals(1, doc.select(".primary").size());
        assertEquals(Copy.BEGIN_READING, doc.selectFirst(".primary").text());
    }

    @Test
    void partwayThroughOffersThePlaceYouLeftOff() {
        for (int i = 0; i < 5; i++) t.addPost("post-" + i, Instant.now().minusSeconds(i));
        t.sittings.begin().orElseThrow();
        t.sittings.advance();

        Document doc = parse(pages.start());
        assertTrue(doc.text().contains("2 of 5"));
        assertEquals("/read", doc.selectFirst(".primary").attr("href"));
        assertTrue(doc.text().contains(Copy.START_OVER));
    }

    @Test
    void nothingNewSaysWhenItLastLooked() {
        t.settings.recordCheck(0);
        Document doc = parse(pages.start());

        assertTrue(doc.text().contains("Nothing new."));
        assertTrue(doc.text().contains(Copy.CHECK_NOW));
        assertTrue(doc.select(".primary").isEmpty(), "checking again is a quiet action, not a demand");
    }

    // ---- Feeds, settings, saved -----------------------------------------

    @Test
    void aBrokenFeedReadsAsPlainLanguageAndOffersAWayOut() {
        t.feeds.recordSuccess(t.feedId, "The Example Review", null, null, null);
        t.feeds.recordError(t.feedId, "ConnectException: Connection refused");

        Document doc = parse(pages.feeds(null));
        String text = doc.text();

        assertTrue(text.contains("Hasn't responded"), text);
        assertFalse(text.contains("ConnectException"), "never a stack trace");
        assertTrue(text.contains(Copy.RETRY));
    }

    @Test
    void removingAFeedNamesItFirst() {
        Document doc = parse(pages.removeConfirm(t.feeds.byId(t.feedId).orElseThrow()));

        assertTrue(doc.text().contains("The Example Review"));
        assertTrue(doc.text().contains("Its posts will go too."));
        assertTrue(doc.text().contains(Copy.CANCEL));
        assertEquals("/feeds/" + t.feedId + "/remove", doc.selectFirst("form.act").attr("action"));
    }

    @Test
    void settingsShowsThreeChoicesWithTheCurrentOneSelected() {
        Document doc = parse(pages.settings(null));

        assertEquals(3, doc.select("fieldset").size());
        assertEquals("20", doc.selectFirst("input[name=size][checked]").attr("value"));
        assertEquals("newest", doc.selectFirst("input[name=order][checked]").attr("value"));
        assertEquals("never", doc.selectFirst("input[name=forget][checked]").attr("value"));
        assertTrue(doc.text().contains(Copy.SET_SIZE_CAPTION));
        assertFalse(doc.text().contains(Copy.CLEAR_NOW), "nothing to clear while nothing is forgotten");
    }

    @Test
    void choosingToForgetOffersToDoItNow() {
        t.settings.save(20, "newest", "2w");
        assertTrue(parse(pages.settings(null)).text().contains(Copy.CLEAR_NOW));
    }

    @Test
    void savedIsEmptyUntilYouKeepSomething() {
        assertTrue(parse(pages.saved()).text().contains(Copy.SAVED_EMPTY));

        long id = t.addPost("Kept for later", Instant.now());
        t.items.setSaved(id, true);

        Document doc = parse(pages.saved());
        assertTrue(doc.text().contains("Kept for later"));
        assertEquals("/saved/" + id, doc.selectFirst("a.name").attr("href"));
    }

    @Test
    void aThinPostSaysSoRatherThanPretending() {
        t.items.insertIfNew(t.feedId, new ItemStore.Incoming(
                "thin", "https://example.com/thin", "A teaser", null, Instant.now(),
                "<p>Two sentences and a link.</p>", "Two sentences"));
        t.sittings.begin().orElseThrow();

        Document doc = parse(pages.read());
        assertTrue(doc.text().contains(Copy.SUMMARY_ONLY));
        assertEquals("https://example.com/thin", doc.selectFirst("a.outline").attr("href"));
        assertEquals(1, doc.select(".primary").size(), "Next is still the only prominent control");
    }

    @Test
    void theKeyboardHintAppearsOnceAndThenNeverAgain() {
        t.addPost("post one", Instant.now());
        t.addPost("post two", Instant.now().minusSeconds(5));
        t.sittings.begin().orElseThrow();

        assertTrue(parse(pages.read()).text().contains(Copy.KEY_HINT));

        t.settings.markKeyHintSeen();
        assertFalse(parse(pages.read()).text().contains(Copy.KEY_HINT));
    }
}
