package reader.http;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reader.TestDb;
import reader.feed.Discovery;
import reader.feed.Fetcher;
import reader.feed.Refresher;
import reader.feed.Scheduler;
import reader.feed.Sweeper;
import reader.view.Pages;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real server over the wire with a client that follows nothing and runs no
 * scripts — which is exactly the promise that every page works with JavaScript off.
 */
class RoutesTest {

    @TempDir
    Path dir;
    TestDb t;
    Refresher refresher;
    Scheduler scheduler;
    Server server;
    HttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        t = new TestDb(dir);
        Fetcher fetcher = new Fetcher();
        refresher = new Refresher(fetcher, t.feeds, t.items, t.settings);
        Sweeper sweeper = new Sweeper(t.items, t.settings);
        scheduler = new Scheduler(refresher, sweeper);
        Pages pages = new Pages(t.feeds, t.items, t.sittings, t.settings, refresher, scheduler);
        server = new Server(pages, t.feeds, t.items, t.sittings, t.settings,
                new Discovery(fetcher), refresher, sweeper);
        server.start(0);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void tearDown() {
        server.close();
        refresher.close();
        scheduler.close();
        t.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static String redirect(HttpResponse<?> r) {
        return r.headers().firstValue("Location").orElse("");
    }

    @Test
    void aWholeSittingFromStartToDone() throws Exception {
        t.addPost("first post", Instant.now());
        t.addPost("second post", Instant.now().minusSeconds(60));

        Document start = Jsoup.parse(get("/").body());
        assertTrue(start.text().contains("2 posts waiting."));

        HttpResponse<String> begun = post("/sitting", "");
        assertEquals(303, begun.statusCode());
        assertEquals("/read", redirect(begun));

        Document reading = Jsoup.parse(get("/read").body());
        assertTrue(reading.text().contains("1 / 2"));
        assertTrue(reading.text().contains("first post"));

        assertEquals("/read", redirect(post("/read/next", "")));
        assertTrue(Jsoup.parse(get("/read").body()).text().contains("2 / 2"));

        assertEquals("/done", redirect(post("/read/next", "")));
        assertTrue(Jsoup.parse(get("/done").body()).text().contains("You're done."));
        assertEquals(0, t.items.countUnread());
    }

    @Test
    void steppingBackUndoesTheStepForward() throws Exception {
        long first = t.addPost("first post", Instant.now());
        t.addPost("second post", Instant.now().minusSeconds(60));

        post("/sitting", "");
        post("/read/next", "");
        assertTrue(t.items.byId(first).orElseThrow().readAt() != null);

        assertEquals("/read", redirect(post("/read/back", "")));
        assertTrue(Jsoup.parse(get("/read").body()).text().contains("1 / 2"));
        assertEquals(null, t.items.byId(first).orElseThrow().readAt());
    }

    @Test
    void leavingEarlyReturnsToTheStartAndKeepsTheRestUnread() throws Exception {
        for (int i = 0; i < 5; i++) t.addPost("post-" + i, Instant.now().minusSeconds(i));

        post("/sitting", "");
        post("/read/next", "");
        assertEquals("/", redirect(post("/sitting/end", "")));

        assertEquals(4, t.items.countUnread());
        assertTrue(Jsoup.parse(get("/").body()).text().contains("4 posts waiting."));
    }

    @Test
    void savingWithoutAScriptComesBackToTheControlsRatherThanTheTop() throws Exception {
        t.addPost("worth keeping", Instant.now());
        post("/sitting", "");

        HttpResponse<String> saved = post("/read/save", "");
        assertEquals(303, saved.statusCode());
        assertEquals("/read#controls", redirect(saved),
                "a plain form post must not land you back at the title");
        assertTrue(Jsoup.parse(get("/read").body()).selectFirst("#controls") != null,
                "and there must be something there to land on");
    }

    @Test
    void savingInPlaceChangesNothingAboutWhereYouAre() throws Exception {
        t.addPost("worth keeping", Instant.now());
        post("/sitting", "");

        HttpResponse<String> saved = client.send(HttpRequest.newBuilder(uri("/read/save"))
                        .header(Server.IN_PLACE_HEADER, Server.IN_PLACE)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(204, saved.statusCode(), "no page, no redirect, no scroll");
        assertTrue(saved.body().isEmpty());
        assertEquals(1, t.items.saved().size(), "but the post really was kept");
    }

    @Test
    void theSaveButtonCarriesBothOfItsLabelsSoNothingHasToInventWords() throws Exception {
        t.addPost("worth keeping", Instant.now());
        post("/sitting", "");

        var button = Jsoup.parse(get("/read").body()).selectFirst("#f-save button");
        assertEquals("Save", button.text());
        assertEquals("Save", button.attr("data-unsaved"));
        assertEquals("Saved ✓", button.attr("data-saved"));
    }

    @Test
    void savingWhileReadingPutsThePostInSaved() throws Exception {
        t.addPost("worth keeping", Instant.now());
        post("/sitting", "");

        post("/read/save", "");
        assertTrue(Jsoup.parse(get("/saved").body()).text().contains("worth keeping"));

        long id = t.items.saved().get(0).id();
        assertEquals(200, get("/saved/" + id).statusCode());
        assertEquals("/saved", redirect(post("/saved/" + id + "/remove", "")));
        assertTrue(t.items.saved().isEmpty());
    }

    @Test
    void readingWithNothingOpenGoesHomeRatherThanNowhere() throws Exception {
        HttpResponse<String> r = get("/read");
        assertEquals(303, r.statusCode());
        assertEquals("/", redirect(r));
    }

    @Test
    void removingAFeedTakesTwoDeliberateSteps() throws Exception {
        Document confirm = Jsoup.parse(get("/feeds/" + t.feedId + "/remove").body());
        assertTrue(confirm.text().contains("Remove The Example Review?"));
        assertEquals(1, t.feeds.count(), "looking at the question changes nothing");

        String after = post("/feeds/" + t.feedId + "/remove", "").body();
        assertTrue(Jsoup.parse(after).text().contains("Removed The Example Review."));
        assertEquals(0, t.feeds.count());
    }

    @Test
    void savingASettingSaysSoAndTakesEffect() throws Exception {
        HttpResponse<String> saved = post("/settings", "size=10&order=oldest&forget=2w");
        assertEquals(303, saved.statusCode());
        assertEquals("/settings?saved=1", redirect(saved));

        Document page = Jsoup.parse(get("/settings?saved=1").body());
        assertTrue(page.text().contains("Saved."));
        assertEquals("10", page.selectFirst("input[name=size][checked]").attr("value"));
        assertEquals(10, t.settings.size());
        assertFalse(t.settings.newestFirst());
    }

    @Test
    void clearingOldPostsReportsWhatItDid() throws Exception {
        t.addPost("ancient", Instant.now().minusSeconds(60L * 60 * 24 * 40));
        post("/settings", "size=20&order=newest&forget=1m");

        HttpResponse<String> cleared = post("/settings/clear-old", "");
        assertEquals("/settings?cleared=1", redirect(cleared));
        assertTrue(Jsoup.parse(get("/settings?cleared=1").body()).text().contains("Cleared 1 post."));
    }

    @Test
    void checkingReturnsImmediatelyRatherThanHoldingThePageOpen() throws Exception {
        long before = System.currentTimeMillis();
        HttpResponse<String> r = post("/refresh", "");
        long took = System.currentTimeMillis() - before;

        assertEquals(303, r.statusCode());
        assertEquals("/", redirect(r));
        assertTrue(took < 1000, "a check took " + took + "ms, which is a page held open");
    }

    @Test
    void anAddressThatIsNotAPageSaysSoAndOffersTheWayBack() throws Exception {
        HttpResponse<String> r = get("/nowhere-at-all");
        assertEquals(404, r.statusCode());

        Document doc = Jsoup.parse(r.body());
        assertTrue(doc.text().contains("That page isn't here."));
        assertEquals("/", doc.selectFirst("main a").attr("href"));
    }

    @Test
    void theStylesheetAndScriptAreServedFromInsideTheProgram() throws Exception {
        HttpResponse<String> css = get("/static/reader.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("--bg"));
        assertEquals(200, get("/static/reader.js").statusCode());
        assertEquals(404, get("/static/../pom.xml").statusCode());
    }

    @Test
    void everyActionIsAPlainFormSoNoneOfThemNeedScripts() throws Exception {
        t.addPost("a post", Instant.now());
        post("/sitting", "");

        Document reading = Jsoup.parse(get("/read").body());
        assertFalse(reading.select("form").isEmpty());
        for (var form : reading.select("form")) {
            assertEquals("post", form.attr("method"), "every action posts a real form");
            assertFalse(form.attr("action").isBlank());
        }
        assertTrue(reading.select("[onclick]").isEmpty(), "nothing depends on an inline handler");
    }

    @Test
    void noImageIsEverRequestedWhileReading() throws Exception {
        t.items.insertIfNew(t.feedId, new reader.store.ItemStore.Incoming(
                "with-image", "https://example.com/p", "A post with a picture", null, Instant.now(),
                reader.feed.Sanitizer.clean(
                        "<p>Words.</p><img src=\"https://example.com/big.jpg\" alt=\"A picture\">",
                        "https://example.com/p").html(),
                "Words."));
        post("/sitting", "");

        Document reading = Jsoup.parse(get("/read").body());
        assertTrue(reading.select("img").isEmpty(), "nothing on this page fetches an image");
        assertTrue(reading.text().contains("A picture"), "but its meaning is still there");
    }
}
