package reader.view;

import reader.feed.Discovery;
import reader.feed.Refresher;
import reader.feed.Sanitizer;
import reader.feed.Scheduler;
import reader.model.Feed;
import reader.model.Item;
import reader.model.Sitting;
import reader.store.FeedStore;
import reader.store.ItemStore;
import reader.store.SessionStore;
import reader.store.Settings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Every screen. The copy all comes from {@link Copy}; the shell comes from {@link Html}. */
public final class Pages {

    /** How long after a check the "Checked just now" line keeps saying so. */
    private static final Duration FRESH = Duration.ofMinutes(2);

    private final FeedStore feeds;
    private final ItemStore items;
    private final SessionStore sittings;
    private final Settings settings;
    private final Refresher refresher;
    private final Scheduler scheduler;

    public Pages(FeedStore feeds, ItemStore items, SessionStore sittings,
                 Settings settings, Refresher refresher, Scheduler scheduler) {
        this.feeds = feeds;
        this.items = items;
        this.sittings = sittings;
        this.settings = settings;
        this.refresher = refresher;
        this.scheduler = scheduler;
    }

    // ---- Start -----------------------------------------------------------

    /** The only decision point in the app: exactly one of five states. */
    public String start() {
        if (feeds.count() == 0) return startEmpty();
        if (refresher.isRunning()) return startChecking();

        Optional<Sitting> open = sittings.current();
        if (open.isPresent()) return startPartway(open.get());

        int unread = items.countUnread();
        return unread > 0 ? startWaiting(unread) : startNothingNew();
    }

    private String startEmpty() {
        String body = Html.header("")
                + Html.main("<p class=\"headline\">" + Html.esc(Copy.START_EMPTY) + "</p>\n"
                + addBox(true)
                + importBox());
        return Html.page(Copy.APP_NAME, body);
    }

    private String startChecking() {
        String body = Html.header("")
                + Html.main("<p class=\"headline quiet\">" + Html.esc(Copy.CHECKING) + "</p>");
        return Html.page(Copy.APP_NAME, Html.refreshAfter(3), body);
    }

    private String startPartway(Sitting s) {
        String body = Html.header("")
                + Html.main(freshNotice()
                + "<p class=\"headline\">" + Copy.partway(s.humanPosition(), s.total()) + "</p>\n"
                + "<p class=\"act\"><a class=\"primary\" href=\"/read\">"
                + Html.esc(Copy.PICK_UP) + "</a></p>\n"
                + "<div class=\"aside\">"
                + Html.postButton("/sitting", Copy.START_OVER, "link", null)
                + "</div>");
        return Html.page(Copy.APP_NAME, body);
    }

    private String startWaiting(int unread) {
        String body = Html.header("")
                + Html.main(freshNotice()
                + "<p class=\"headline\">" + Copy.postsWaiting(unread) + "</p>\n"
                + Html.postButton("/sitting", Copy.BEGIN_READING, "primary", null));
        return Html.page(Copy.APP_NAME, body);
    }

    private String startNothingNew() {
        String body = Html.header("")
                + Html.main("<p class=\"headline quiet\">"
                + Html.esc(Copy.nothingNewSince(settings.lastCheckedAt())) + "</p>\n"
                + "<div class=\"aside\">"
                + Html.postButton("/refresh", Copy.CHECK_NOW, "link", null)
                + "</div>");
        return Html.page(Copy.APP_NAME, body);
    }

    private String freshNotice() {
        Instant last = settings.lastCheckedAt();
        if (last == null || last.isBefore(Instant.now().minus(FRESH))) return "";
        return Html.notice(Html.esc(Copy.checkedJustNow(settings.lastNewCount())));
    }

    // ---- Reading ---------------------------------------------------------

    /** Null when there is nothing open to read, which the caller turns into a redirect. */
    public String read() {
        Optional<Sitting> open = sittings.current();
        if (open.isEmpty()) return null;
        Optional<Item> maybe = sittings.currentItem();
        if (maybe.isEmpty()) return null;

        Sitting s = open.get();
        Item item = maybe.get();

        StringBuilder b = new StringBuilder();
        b.append("<p class=\"counter\">").append(Html.esc(Copy.counter(s.humanPosition(), s.total())))
                .append("</p>\n<article>\n<h1>");
        if (item.url() != null) {
            b.append("<a href=\"").append(Html.esc(item.url()))
                    .append("\" target=\"_blank\" rel=\"noopener\">")
                    .append(Html.esc(item.displayTitle())).append("</a>");
        } else {
            b.append(Html.esc(item.displayTitle()));
        }
        b.append("</h1>\n<p class=\"byline\">").append(Html.esc(byline(item))).append("</p>\n");
        b.append("<div class=\"post\">").append(item.contentHtml() == null ? "" : item.contentHtml())
                .append("</div>\n");

        if (Sanitizer.textLength(item.contentHtml()) < Sanitizer.SUMMARY_THRESHOLD && item.url() != null) {
            b.append("<p class=\"summary\">").append(Html.esc(Copy.SUMMARY_ONLY)).append("</p>\n")
                    .append("<p class=\"act\"><a class=\"outline\" href=\"").append(Html.esc(item.url()))
                    .append("\" target=\"_blank\" rel=\"noopener\">")
                    .append(Html.esc(Copy.READ_ON_SITE)).append("</a></p>\n");
        }
        b.append("</article>\n");

        b.append("<div class=\"controls\" id=\"controls\">\n")
                .append(Html.postButton("/read/next", Copy.NEXT, "primary", "f-next"))
                .append(saveButton(item.isSaved()));
        if (s.position() > 0) {
            b.append(Html.postButton("/read/back", Copy.BACK, "link", "f-back"));
        }
        b.append("\n</div>\n");

        if (!settings.keyHintSeen() && s.position() == 0) {
            b.append("<p class=\"hint\">").append(Html.esc(Copy.KEY_HINT)).append("</p>\n");
        }
        b.append("<div class=\"leave\">")
                .append(Html.postButton("/sitting/end", Copy.LEAVE, "link", "f-leave"))
                .append("</div>");

        return Html.page(item.displayTitle(), "<main class=\"reading\">\n" + b + "\n</main>");
    }

    /**
     * Saving must not move you. It carries both of its labels so the script can swap them
     * in place without reloading the post — and without inventing words of its own.
     */
    private static String saveButton(boolean saved) {
        return "<form class=\"act\" method=\"post\" action=\"/read/save\" id=\"f-save\">"
                + "<button class=\"link\""
                + " data-saved=\"" + Html.esc(Copy.UNSAVE_POST) + "\""
                + " data-unsaved=\"" + Html.esc(Copy.SAVE_POST) + "\">"
                + Html.esc(saved ? Copy.UNSAVE_POST : Copy.SAVE_POST)
                + "</button></form>";
    }

    private static String byline(Item item) {
        String source = item.feedTitle() == null || item.feedTitle().isBlank()
                ? Feed.hostOf(item.feedSite()) : item.feedTitle();
        if (item.publishedAt() == null) return source;
        return source + " · " + Copy.date(item.publishedAt());
    }

    // ---- Done ------------------------------------------------------------

    /**
     * Deliberately near-empty, and deliberately offers no way to continue: a one-tap
     * "more" here would rebuild the endless stream this whole design exists to avoid.
     */
    public String done() {
        int remaining = items.countUnread();
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"headline\"><strong>").append(Html.esc(Copy.DONE)).append("</strong></p>\n");
        b.append("<p class=\"quiet\">").append(Html.esc(Copy.nextCheckAround(scheduler.nextCheck())))
                .append("</p>\n");
        if (remaining > 0) {
            b.append("<p class=\"quiet\">").append(Html.esc(Copy.moreWaiting(remaining))).append("</p>\n");
        }
        return Html.page(Copy.APP_NAME, Html.header("") + Html.main(b.toString()));
    }

    // ---- Feeds -----------------------------------------------------------

    public String feeds(String noticeHtml) {
        List<Feed> all = feeds.all();
        StringBuilder b = new StringBuilder();
        if (noticeHtml != null && !noticeHtml.isBlank()) b.append(Html.notice(noticeHtml)).append('\n');

        if (all.isEmpty()) {
            b.append("<p class=\"quiet\">").append(Html.esc(Copy.FEEDS_EMPTY)).append("</p>\n");
        } else {
            b.append("<ul class=\"feeds\">\n");
            for (Feed f : all) b.append(feedRow(f));
            b.append("</ul>\n");
        }
        b.append(addBox(all.isEmpty())).append(importBox());
        return Html.page(Copy.NAV_FEEDS, Html.header("feeds") + Html.main(b.toString()));
    }

    private String feedRow(Feed f) {
        StringBuilder b = new StringBuilder("<li>\n<span class=\"name\">");
        b.append(Html.esc(f.displayTitle())).append("</span>\n");
        if (f.broken()) {
            b.append("<span class=\"meta broken\">")
                    .append(Html.esc(Copy.brokenSince(f.lastFetchedAt()))).append("</span>\n")
                    .append(Html.postButton("/feeds/" + f.id() + "/retry", Copy.RETRY, "link", null));
        } else {
            b.append("<span class=\"meta\">")
                    .append(Html.esc(Copy.updated(f.lastFetchedAt(), feeds.itemCount(f.id()))))
                    .append("</span>\n");
        }
        b.append("<a class=\"link remove\" href=\"/feeds/").append(f.id()).append("/remove\">")
                .append(Html.esc(Copy.REMOVE)).append("</a>\n</li>\n");
        return b.toString();
    }

    private static String addBox(boolean prominent) {
        return "<form class=\"add\" method=\"post\" action=\"/feeds/add\">\n"
                + "<label class=\"offscreen\" for=\"address\">" + Html.esc(Copy.ADD_PLACEHOLDER) + "</label>\n"
                + "<input id=\"address\" name=\"address\" type=\"text\" autocomplete=\"off\" "
                + "autocapitalize=\"off\" spellcheck=\"false\" autofocus placeholder=\""
                + Html.esc(Copy.ADD_PLACEHOLDER) + "\">\n"
                + "<button class=\"" + (prominent ? "primary" : "outline") + "\">"
                + Html.esc(Copy.ADD_BUTTON) + "</button>\n</form>\n";
    }

    private static String importBox() {
        return "<form class=\"import\" method=\"post\" action=\"/feeds/import\" "
                + "enctype=\"multipart/form-data\">\n"
                + "<label for=\"file\">" + Html.esc(Copy.IMPORT_LABEL) + "</label>\n"
                + "<input id=\"file\" name=\"file\" type=\"file\">\n"
                + "<button class=\"link\">" + Html.esc(Copy.IMPORT_BUTTON) + "</button>\n</form>\n";
    }

    /** Shown after an add that left something to read. */
    public String added(String noticeHtml, boolean somethingToRead) {
        StringBuilder b = new StringBuilder(Html.notice(noticeHtml));
        b.append('\n');
        if (somethingToRead) {
            b.append(Html.postButton("/sitting", Copy.BEGIN_READING, "primary", null));
        }
        b.append("<p class=\"aside\"><a class=\"link\" href=\"/feeds\">")
                .append(Html.esc(Copy.NAV_FEEDS)).append("</a></p>");
        return Html.page(Copy.APP_NAME, Html.header("") + Html.main(b.toString()));
    }

    /** Several feeds on one page: pick by name, never by address. */
    public String chooser(List<Discovery.Candidate> candidates) {
        StringBuilder b = new StringBuilder();
        b.append(Html.notice(Html.esc(Copy.ADD_SEVERAL)));
        b.append("\n<form class=\"choose\" method=\"post\" action=\"/feeds/add\">\n");
        for (int i = 0; i < candidates.size(); i++) {
            Discovery.Candidate c = candidates.get(i);
            b.append("<label><input type=\"radio\" name=\"address\" value=\"")
                    .append(Html.esc(c.address())).append('"')
                    .append(i == 0 ? " checked" : "").append("> ")
                    .append(Html.esc(c.title())).append("</label>\n");
        }
        b.append("<button class=\"primary\">").append(Html.esc(Copy.ADD_CHOOSE))
                .append("</button>\n</form>\n")
                .append("<p class=\"aside\"><a class=\"link\" href=\"/feeds\">")
                .append(Html.esc(Copy.CANCEL)).append("</a></p>");
        return Html.page(Copy.NAV_FEEDS, Html.header("feeds") + Html.main(b.toString()));
    }

    public String removeConfirm(Feed f) {
        String body = Html.header("feeds") + Html.main(
                "<p class=\"headline\">" + Copy.removeConfirm(f.displayTitle()) + "</p>\n"
                        + "<div class=\"controls\">\n"
                        + Html.postButton("/feeds/" + f.id() + "/remove", Copy.REMOVE, "primary", null)
                        + "<p class=\"act\"><a class=\"link\" href=\"/feeds\">"
                        + Html.esc(Copy.CANCEL) + "</a></p>\n</div>");
        return Html.page(Copy.NAV_FEEDS, body);
    }

    // ---- Settings --------------------------------------------------------

    public String settings(String noticeHtml) {
        int size = settings.size();
        String order = settings.order();
        String forget = settings.forget();

        StringBuilder b = new StringBuilder();
        if (noticeHtml != null && !noticeHtml.isBlank()) b.append(Html.notice(noticeHtml)).append('\n');
        b.append("<form class=\"settings\" method=\"post\" action=\"/settings\">\n");

        b.append(group(Copy.SET_SIZE_LABEL, Copy.SET_SIZE_CAPTION,
                radio("size", "10", "10", size == 10)
                        + radio("size", "20", "20", size == 20)
                        + radio("size", "30", "30", size == 30)));

        b.append(group(Copy.SET_ORDER_LABEL, Copy.SET_ORDER_CAPTION,
                radio("order", Settings.NEWEST, Copy.ORDER_NEWEST, Settings.NEWEST.equals(order))
                        + radio("order", Settings.OLDEST, Copy.ORDER_OLDEST, Settings.OLDEST.equals(order))));

        b.append(group(Copy.SET_FORGET_LABEL, Copy.SET_FORGET_CAPTION,
                radio("forget", Settings.NEVER, Copy.FORGET_NEVER, Settings.NEVER.equals(forget))
                        + radio("forget", "1w", Copy.FORGET_1W, "1w".equals(forget))
                        + radio("forget", "2w", Copy.FORGET_2W, "2w".equals(forget))
                        + radio("forget", "1m", Copy.FORGET_1M, "1m".equals(forget))));

        b.append("<button class=\"primary\">").append(Html.esc(Copy.SAVE_SETTINGS))
                .append("</button>\n</form>\n");

        if (!Settings.NEVER.equals(forget)) {
            b.append("<div class=\"aside\">")
                    .append(Html.postButton("/settings/clear-old", Copy.CLEAR_NOW, "link", null))
                    .append("</div>\n");
        }
        return Html.page(Copy.NAV_SETTINGS, Html.header("settings") + Html.main(b.toString()));
    }

    private static String group(String legend, String caption, String controls) {
        return "<fieldset>\n<legend>" + Html.esc(legend) + "</legend>\n"
                + "<div class=\"choices\">" + controls + "</div>\n"
                + "<p class=\"caption\">" + Html.esc(caption) + "</p>\n</fieldset>\n";
    }

    private static String radio(String name, String value, String label, boolean checked) {
        return "<label><input type=\"radio\" name=\"" + name + "\" value=\"" + Html.esc(value) + "\""
                + (checked ? " checked" : "") + "> " + Html.esc(label) + "</label>\n";
    }

    // ---- Saved -----------------------------------------------------------

    public String saved() {
        List<Item> kept = items.saved();
        StringBuilder b = new StringBuilder();
        if (kept.isEmpty()) {
            b.append("<p class=\"quiet\">").append(Html.esc(Copy.SAVED_EMPTY)).append("</p>");
        } else {
            b.append("<ul class=\"saved\">\n");
            for (Item i : kept) {
                b.append("<li>\n<a class=\"name\" href=\"/saved/").append(i.id()).append("\">")
                        .append(Html.esc(i.displayTitle())).append("</a>\n")
                        .append("<span class=\"meta\">").append(Html.esc(byline(i))).append("</span>\n")
                        .append(Html.postButton("/saved/" + i.id() + "/remove",
                                Copy.SAVED_REMOVE, "link", null))
                        .append("</li>\n");
            }
            b.append("</ul>");
        }
        return Html.page(Copy.NAV_SAVED, Html.header("saved") + Html.main(b.toString()));
    }

    public String savedOne(Item item) {
        StringBuilder b = new StringBuilder();
        b.append("<article>\n<h1>");
        if (item.url() != null) {
            b.append("<a href=\"").append(Html.esc(item.url()))
                    .append("\" target=\"_blank\" rel=\"noopener\">")
                    .append(Html.esc(item.displayTitle())).append("</a>");
        } else {
            b.append(Html.esc(item.displayTitle()));
        }
        b.append("</h1>\n<p class=\"byline\">").append(Html.esc(byline(item))).append("</p>\n")
                .append("<div class=\"post\">")
                .append(item.contentHtml() == null ? "" : item.contentHtml())
                .append("</div>\n</article>\n")
                .append("<p class=\"aside\"><a class=\"link\" href=\"/saved\">")
                .append(Html.esc(Copy.BACK_TO_SAVED)).append("</a></p>");
        return Html.page(item.displayTitle(), Html.header("saved") + Html.main(b.toString()));
    }

    // ---- Dead ends -------------------------------------------------------

    public String notFound() {
        return Html.page(Copy.APP_NAME, Html.header("") + Html.main(
                "<p class=\"headline quiet\">" + Html.esc(Copy.NOT_FOUND) + "</p>\n"
                        + "<p class=\"aside\"><a class=\"link\" href=\"/\">"
                        + Html.esc(Copy.BACK_HOME) + "</a></p>"));
    }

    public String broke() {
        return Html.page(Copy.APP_NAME, Html.header("") + Html.main(
                "<p class=\"headline quiet\">" + Html.esc(Copy.BROKE) + "</p>\n"
                        + "<p class=\"aside\"><a class=\"link\" href=\"/\">"
                        + Html.esc(Copy.BACK_HOME) + "</a></p>"));
    }
}
