package reader.feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetcherTest {

    LocalSite site;
    Fetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        site = new LocalSite();
        fetcher = new Fetcher();
    }

    @AfterEach
    void tearDown() {
        site.close();
    }

    @Test
    void asksAgainWithTheTokenAndIsToldNothingChanged() throws Exception {
        Fetcher.Result first = fetcher.get(site.at("/feed"));
        assertTrue(first.ok());
        assertEquals(LocalSite.TOKEN, first.etag());
        assertTrue(first.body().length > 0);

        Fetcher.Result second = fetcher.get(site.at("/feed"), first.etag(), first.lastModified());
        assertTrue(second.unchanged());
        assertFalse(second.ok());
        assertEquals(0, second.body().length);
        assertEquals(1, site.bodiesSent.get(), "the second check must not transfer the feed again");
    }

    @Test
    void aFailingServerIsReportedRatherThanThrown() throws Exception {
        Fetcher.Result r = fetcher.get(site.at("/broken"));
        assertEquals(500, r.status());
        assertFalse(r.ok());
    }

    @Test
    void anAddressThatGoesNowhereFails() {
        assertThrows(IOException.class, () -> fetcher.get("http://127.0.0.1:1/feed"));
    }
}
