package reader.http;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reader.TestDb;
import reader.feed.Discovery;
import reader.feed.Fetcher;
import reader.feed.LocalSite;
import reader.feed.Refresher;
import reader.feed.Scheduler;
import reader.feed.Sweeper;
import reader.Plain;
import reader.view.Pages;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six outcomes of the one field that has to accept anything. Each must produce its
 * own plain sentence, and none of them may show a code, an exception or a raw address.
 */
class AddFeedTest {

    @TempDir
    Path dir;
    TestDb t;
    LocalSite site;
    Refresher refresher;
    Scheduler scheduler;
    Server server;

    void startWith(boolean siteOffersAFeed) throws IOException {
        site = new LocalSite(siteOffersAFeed);
        Fetcher fetcher = new Fetcher();
        refresher = new Refresher(fetcher, t.feeds, t.items, t.settings);
        Sweeper sweeper = new Sweeper(t.items, t.settings);
        scheduler = new Scheduler(refresher, sweeper);
        Pages pages = new Pages(t.feeds, t.items, t.sittings, t.settings, refresher, scheduler);
        server = new Server(pages, t.feeds, t.items, t.sittings, t.settings,
                new Discovery(fetcher), refresher, sweeper);
    }

    @BeforeEach
    void setUp() {
        t = new TestDb(dir);
        t.feeds.remove(t.feedId); // start from nothing followed
    }

    @AfterEach
    void tearDown() {
        if (refresher != null) refresher.close();
        if (scheduler != null) scheduler.close();
        if (site != null) site.close();
        t.close();
    }

    private String text(String html) {
        return Jsoup.parse(html).text();
    }

    @Test
    void aDirectFeedAddressIsFollowedAndItsPostsArrive() throws IOException {
        startWith(true);
        String page = server.add(site.at("/feed"));

        assertTrue(text(page).contains("Added The Example Review — 2 recent posts."), text(page));
        assertEquals(2, t.items.countUnread());
        assertTrue(Jsoup.parse(page).select(".primary").text().contains("Begin reading"),
                "and there is one obvious thing to do next");
    }

    @Test
    void aWebsiteAddressFindsTheFeedOnItWithoutAnySecondStep() throws IOException {
        startWith(true);
        String page = server.add(site.at("/page"));

        assertTrue(text(page).contains("Added The Example Review"), text(page));
        assertEquals(2, t.items.countUnread());
    }

    @Test
    void aPageOfferingSeveralAsksWhichByName() throws IOException {
        startWith(true);
        String page = server.add(site.at("/multi"));

        assertTrue(text(page).contains("That page offers a few."), text(page));
        assertTrue(text(page).contains("Everything"));
        assertTrue(text(page).contains("Just the long pieces"));
        assertEquals(2, Jsoup.parse(page).select("input[type=radio][name=address]").size());
        assertEquals(0, t.feeds.count(), "nothing is followed until you choose");
    }

    @Test
    void aPageWithNothingToFollowSaysSo() throws IOException {
        startWith(false);
        String page = server.add(site.at("/page"));

        assertTrue(text(page).contains("That looks like a website, but I couldn't find a feed on it."),
                text(page));
        assertEquals(0, t.feeds.count());
    }

    @Test
    void anAddressThatGoesNowhereNamesTheAddressBack() throws IOException {
        startWith(true);
        String page = server.add("127.0.0.1:1");

        assertTrue(text(page).contains("Couldn't reach 127.0.0.1. Is the address right?"), text(page));
    }

    @Test
    void addingTheSameFeedTwiceSaysYouAlreadyHaveIt() throws IOException {
        startWith(true);
        server.add(site.at("/feed"));
        String page = server.add(site.at("/feed"));

        assertTrue(text(page).contains("You already follow The Example Review."), text(page));
        assertEquals(1, t.feeds.count());
        assertEquals(2, t.items.countUnread(), "and nothing arrives twice");
    }

    @Test
    void anEmptyBoxAsksForSomethingRatherThanFailing() throws IOException {
        startWith(true);
        assertTrue(text(server.add("   ")).contains("Type a website address first."));
    }

    /** The same shape a mainstream reader exports, but pointed at the throwaway site. */
    private byte[] export() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml version=\"1.0\"><body>"
                + "<outline text=\"Tech\" title=\"Tech\">"
                + "<outline type=\"rss\" title=\"The Example Review\" xmlUrl=\"" + site.at("/feed") + "\"/>"
                + "<outline type=\"rss\" title=\"Another one\" xmlUrl=\"" + site.at("/feed-two") + "\"/>"
                + "</outline>"
                + "<outline type=\"rss\" title=\"A broken entry\"/>"
                + "</body></opml>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void anExportFromAnotherReaderBringsItsFeedsAcross() throws IOException {
        startWith(true);
        String page = server.importFeeds(export());

        assertEquals(2, t.feeds.count());
        assertTrue(text(page).contains("Added 2 feeds."), text(page));
        assertTrue(text(page).contains("Couldn't read: A broken entry."), text(page));
    }

    @Test
    void importingTheSameExportTwiceAddsNothingAndSaysSo() throws IOException {
        startWith(true);
        server.importFeeds(export());
        String page = server.importFeeds(export());

        assertEquals(2, t.feeds.count());
        assertTrue(text(page).contains("You already follow all of those."), text(page));
    }

    @Test
    void anExportThatOverlapsWhatYouHaveSaysSoRatherThanLosingTheCount() throws IOException {
        startWith(true);
        server.add(site.at("/feed"));

        String page = server.importFeeds(export());
        assertTrue(text(page).contains("Added 1 feed."), text(page));
        assertTrue(text(page).contains("You already follow one other."), text(page));
    }

    @Test
    void choosingNoFileIsAskedAboutRatherThanIgnored() throws IOException {
        startWith(true);
        assertTrue(text(server.importFeeds(new byte[0])).contains("Choose a file first."));
    }

    @Test
    void noOutcomeEverShowsACodeAnExceptionOrARawAddress() throws IOException {
        startWith(true);
        for (String outcome : new String[]{
                server.add(site.at("/feed")),
                server.add(site.at("/feed")),
                server.add(site.at("/multi")),
                server.add("127.0.0.1:1"),
                server.add(site.at("/broken")),
                server.add("")}) {
            String visible = text(outcome);
            Plain.assertPlain("an add outcome", visible);
            assertFalse(visible.contains("Exception"), visible);
            assertFalse(visible.contains("500"), visible);
        }
    }
}
