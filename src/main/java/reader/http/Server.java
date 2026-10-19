package reader.http;

import com.sun.net.httpserver.HttpServer;
import reader.feed.Discovery;
import reader.feed.Opml;
import reader.feed.Refresher;
import reader.feed.Sweeper;
import reader.model.Feed;
import reader.model.Item;
import reader.model.Sitting;
import reader.store.FeedStore;
import reader.store.ItemStore;
import reader.store.SessionStore;
import reader.store.Settings;
import reader.view.Copy;
import reader.view.Html;
import reader.view.Pages;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Routing and the handful of actions behind it. Navigation is links and POST-then-redirect,
 * so every page works with JavaScript switched off.
 */
public final class Server implements AutoCloseable {

    /** How the page says "I can handle this myself, don't send me anywhere." */
    public static final String IN_PLACE_HEADER = "X-Quiet-Reader";
    public static final String IN_PLACE = "in-place";

    private final Pages pages;
    private final FeedStore feeds;
    private final ItemStore items;
    private final SessionStore sittings;
    private final Settings settings;
    private final Discovery discovery;
    private final Refresher refresher;
    private final Sweeper sweeper;

    private HttpServer http;

    public Server(Pages pages, FeedStore feeds, ItemStore items, SessionStore sittings,
                  Settings settings, Discovery discovery, Refresher refresher, Sweeper sweeper) {
        this.pages = pages;
        this.feeds = feeds;
        this.items = items;
        this.sittings = sittings;
        this.settings = settings;
        this.discovery = discovery;
        this.refresher = refresher;
        this.sweeper = sweeper;
    }

    public void start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        http.createContext("/", routes());
        // Elastic rather than fixed: a browser that holds connections open can then never
        // starve the pool and leave a page hanging. Threads are reclaimed when idle.
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "web");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    /** The port actually bound, which matters when the caller asked for any free one. */
    public int port() {
        return http == null ? -1 : http.getAddress().getPort();
    }

    Router routes() {
        Router r = new Router(new Router.ErrorPages() {
            @Override
            public String notFound() {
                return pages.notFound();
            }

            @Override
            public String broke() {
                return pages.broke();
            }
        });

        r.get("/", req -> Router.Resp.html(pages.start()));

        // ---- The sitting -------------------------------------------------
        r.post("/sitting", req -> sittings.begin().isPresent()
                ? Router.Resp.seeOther("/read")
                : Router.Resp.seeOther("/"));

        r.get("/read", req -> {
            String html = pages.read();
            return html == null ? Router.Resp.seeOther("/") : Router.Resp.html(html);
        });

        r.post("/read/next", req -> {
            Optional<Sitting> open = sittings.current();
            if (open.isEmpty()) return Router.Resp.seeOther("/");
            if (open.get().position() == 0) settings.markKeyHintSeen();
            boolean finished = sittings.advance();
            return Router.Resp.seeOther(finished ? "/done" : "/read");
        });

        r.post("/read/back", req -> {
            sittings.back();
            return Router.Resp.seeOther("/read");
        });

        // Saving must never move you down the page you are reading. With a script it
        // answers in place; without one it comes back to the controls rather than the top.
        r.post("/read/save", req -> {
            sittings.toggleSaveOnCurrent();
            return IN_PLACE.equals(req.header(IN_PLACE_HEADER))
                    ? Router.Resp.nothingToShow()
                    : Router.Resp.seeOther("/read#controls");
        });

        r.post("/sitting/end", req -> {
            sittings.finish();
            return Router.Resp.seeOther("/");
        });

        r.get("/done", req -> Router.Resp.html(pages.done()));

        // ---- Saved -------------------------------------------------------
        r.get("/saved", req -> Router.Resp.html(pages.saved()));

        r.get("/saved/{id}", req -> items.byId(req.idParam())
                .filter(Item::isSaved)
                .map(i -> Router.Resp.html(pages.savedOne(i)))
                .orElseGet(() -> Router.Resp.html(404, pages.notFound())));

        r.post("/saved/{id}/remove", req -> {
            items.setSaved(req.idParam(), false);
            return Router.Resp.seeOther("/saved");
        });

        // ---- Feeds -------------------------------------------------------
        r.get("/feeds", req -> Router.Resp.html(pages.feeds(null)));
        r.post("/feeds/add", req -> Router.Resp.html(add(req.f("address"))));
        r.post("/feeds/import", req -> Router.Resp.html(importFeeds(req.file)));

        r.get("/feeds/{id}/remove", req -> feeds.byId(req.idParam())
                .map(f -> Router.Resp.html(pages.removeConfirm(f)))
                .orElseGet(() -> Router.Resp.html(404, pages.notFound())));

        r.post("/feeds/{id}/remove", req -> {
            Optional<Feed> f = feeds.byId(req.idParam());
            if (f.isEmpty()) return Router.Resp.html(pages.feeds(null));
            String name = f.get().displayTitle();
            // Its posts go with it, which can hollow out a running sitting; end that cleanly.
            sittings.finish();
            feeds.remove(f.get().id());
            return Router.Resp.html(pages.feeds(Copy.removed(name)));
        });

        r.post("/feeds/{id}/retry", req -> {
            refresher.startInBackground();
            return Router.Resp.seeOther("/");
        });

        // ---- Checking ----------------------------------------------------
        r.post("/refresh", req -> {
            refresher.startInBackground();
            return Router.Resp.seeOther("/");
        });

        // ---- Settings ----------------------------------------------------
        r.get("/settings", req -> {
            String notice = null;
            if ("1".equals(req.q("saved", ""))) notice = Html.esc(Copy.SETTINGS_SAVED);
            String cleared = req.q("cleared", "");
            if (!cleared.isEmpty()) {
                try {
                    notice = Html.esc(Copy.cleared(Integer.parseInt(cleared)));
                } catch (NumberFormatException ignored) {
                    // A hand-edited address is not worth an error page.
                }
            }
            return Router.Resp.html(pages.settings(notice));
        });

        r.post("/settings", req -> {
            int size;
            try {
                size = Integer.parseInt(req.f("size"));
            } catch (NumberFormatException e) {
                size = Settings.DEFAULT_SIZE;
            }
            settings.save(size, req.f("order"), req.f("forget"));
            return Router.Resp.seeOther("/settings?saved=1");
        });

        r.post("/settings/clear-old", req ->
                Router.Resp.seeOther("/settings?cleared=" + sweeper.sweep()));

        // ---- Static ------------------------------------------------------
        r.get("/static/*", req -> Assets.serve(req.params.get("rest"), pages.notFound()));

        return r;
    }

    // ---- Actions ---------------------------------------------------------

    /** The make-or-break moment: one field, anything in it, a concrete answer out. */
    String add(String address) {
        String typed = address == null ? "" : address.trim();
        if (typed.isEmpty()) return pages.feeds(Html.esc(Copy.ADD_EMPTY));

        Discovery.Result found = discovery.discover(typed);
        return switch (found.kind()) {
            case UNREACHABLE -> pages.feeds(Html.esc(Copy.unreachable(found.host())));
            case NONE -> pages.feeds(Html.esc(Copy.ADD_NONE));
            case FOUND_MANY -> pages.chooser(found.candidates());
            case FOUND_ONE -> addOne(found.only());
        };
    }

    private String addOne(Discovery.Candidate candidate) {
        Optional<Feed> existing = feeds.byUrl(candidate.address());
        if (existing.isPresent()) {
            return pages.feeds(Copy.alreadyFollowing(existing.get().displayTitle()));
        }
        Feed feed = feeds.add(candidate.address(), candidate.title(), null);
        int added = refresher.refresh(feed);
        String name = feeds.byId(feed.id()).orElse(feed).displayTitle();
        String notice = added > 0 ? Copy.added(name, added) : Copy.addedNoPosts(name);
        return pages.added(notice, items.countUnread() > 0);
    }

    /** The realistic way someone arrives with feeds they already care about. */
    String importFeeds(byte[] file) {
        if (file == null || file.length == 0) {
            return pages.feeds(Html.esc(Copy.IMPORT_NO_FILE));
        }
        Opml.Parsed parsed = Opml.parse(file);
        if (parsed.entries().isEmpty()) return pages.feeds(Html.esc(Copy.IMPORT_NONE));

        List<String> alreadyHad = new ArrayList<>();
        int added = 0;
        for (Opml.Entry e : parsed.entries()) {
            if (feeds.byUrl(e.address()).isPresent()) {
                alreadyHad.add(e.title());
                continue;
            }
            feeds.add(e.address(), e.title(), null);
            added++;
        }
        // Dozens of feeds would take a minute to read; the background check handles it.
        refresher.startInBackground();

        String notice = added > 0 ? Copy.imported(added) : Copy.ALREADY_FOLLOWING_ALL;
        if (added > 0 && !alreadyHad.isEmpty()) notice += " " + Copy.alreadyHad(alreadyHad.size());
        if (!parsed.skipped().isEmpty()) notice += " " + Copy.importSkipped(parsed.skipped());
        return pages.feeds(notice);
    }

    @Override
    public void close() {
        if (http != null) http.stop(0);
    }
}
