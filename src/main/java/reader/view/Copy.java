package reader.view;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Every user-facing string in the application, in one place, so the voice stays
 * consistent and is reviewable at a glance.
 *
 * <p>Two rules hold here, and {@code CopyTest} enforces the first:
 * <ul>
 *   <li>No jargon. The words <em>session</em>, <em>RSS</em>, <em>URL</em>, <em>GUID</em>,
 *       <em>XML</em> and <em>HTTP</em> must never reach the screen. Sources are "feeds";
 *       items are "posts".</li>
 *   <li>Anything returned from here may contain light inline markup ({@code <strong>}),
 *       but every value interpolated into it is escaped first.</li>
 * </ul>
 */
public final class Copy {

    private Copy() {
    }

    // ---- Chrome ---------------------------------------------------------

    public static final String APP_NAME = "Quiet Reader";
    public static final String NAV_SAVED = "Saved";
    public static final String NAV_FEEDS = "Feeds";
    public static final String NAV_SETTINGS = "Settings";

    // ---- Start page -----------------------------------------------------

    public static final String START_EMPTY = "Nothing here yet. Add somewhere you'd like to read.";
    public static final String BEGIN_READING = "Begin reading";
    public static final String PICK_UP = "Pick up where you left off";
    public static final String START_OVER = "Start over";
    public static final String CHECK_NOW = "Check now";
    public static final String CHECKING = "Checking your feeds…";
    public static final String NOTHING_NEW = "Nothing new.";
    public static final String NEVER_CHECKED = "Not checked yet.";

    public static String postsWaiting(int n) {
        return "<strong>" + count(n, "post", "posts") + "</strong> waiting.";
    }

    public static String partway(int position, int total) {
        return "You're partway through — <strong>" + position + " of " + total + "</strong>.";
    }

    public static String nothingNewSince(Instant lastChecked) {
        if (lastChecked == null) return NOTHING_NEW + " " + NEVER_CHECKED;
        return NOTHING_NEW + " Last checked at " + clock(lastChecked) + ".";
    }

    public static String checkedJustNow(int newPosts) {
        if (newPosts <= 0) return "Checked just now — nothing new.";
        return "Checked just now — " + newPosts + " new.";
    }

    // ---- Reading view ---------------------------------------------------

    public static final String NEXT = "Next";
    public static final String SAVE_POST = "Save";
    public static final String UNSAVE_POST = "Saved ✓";
    public static final String BACK = "← Back";
    public static final String LEAVE = "Leave";
    public static final String KEY_HINT = "space — next · s — save";
    public static final String SUMMARY_ONLY = "This feed only publishes summaries.";
    public static final String READ_ON_SITE = "Read on site →";

    public static String counter(int position, int total) {
        return position + " / " + total;
    }

    // ---- Done -----------------------------------------------------------

    public static final String DONE = "You're done.";

    public static String nextCheckAround(Instant when) {
        if (when == null) return "";
        return "Next check around " + clock(when) + ".";
    }

    public static String moreWaiting(int n) {
        return count(n, "more is", "more are") + " waiting whenever you want them.";
    }

    // ---- Adding a feed --------------------------------------------------

    public static final String ADD_PLACEHOLDER = "Paste a website address";
    public static final String ADD_BUTTON = "Add";
    public static final String ADD_EMPTY = "Type a website address first.";
    public static final String ADD_SEVERAL = "That page offers a few. Which would you like?";
    public static final String ADD_NONE = "That looks like a website, but I couldn't find a feed on it.";
    public static final String ADD_CHOOSE = "Add this one";
    public static final String IMPORT_LABEL = "Bring your feeds from another reader";
    public static final String IMPORT_BUTTON = "Import";
    public static final String IMPORT_NONE = "Couldn't read any feeds from that file.";
    public static final String IMPORT_NO_FILE = "Choose a file first.";

    public static String added(String feedTitle, int posts) {
        return "Added <strong>" + Html.esc(feedTitle) + "</strong> — "
                + count(posts, "recent post", "recent posts") + ".";
    }

    public static String addedNoPosts(String feedTitle) {
        return "Added <strong>" + Html.esc(feedTitle) + "</strong>. Nothing published there yet.";
    }

    public static String unreachable(String host) {
        return "Couldn't reach " + Html.esc(host) + ". Is the address right?";
    }

    public static String alreadyFollowing(String feedTitle) {
        return "You already follow <strong>" + Html.esc(feedTitle) + "</strong>.";
    }

    public static final String ALREADY_FOLLOWING_ALL = "You already follow all of those.";

    public static String imported(int n) {
        return "Added " + count(n, "feed", "feeds") + ".";
    }

    /** Said alongside a count of new ones, so the numbers in the file still add up. */
    public static String alreadyHad(int n) {
        return "You already follow " + (n == 1 ? "one other" : n + " others") + ".";
    }

    public static String importSkipped(java.util.List<String> names) {
        StringBuilder b = new StringBuilder("Couldn't read: ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(Html.esc(names.get(i)));
        }
        return b.append('.').toString();
    }

    // ---- Feeds page -----------------------------------------------------

    public static final String FEEDS_EMPTY = "No feeds yet.";
    public static final String REMOVE = "Remove";
    public static final String RETRY = "Try again";
    public static final String CANCEL = "Cancel";
    public static final String NEVER_UPDATED = "Nothing read from it yet.";

    public static String removeConfirm(String feedTitle) {
        return "Remove <strong>" + Html.esc(feedTitle) + "</strong>? Its posts will go too.";
    }

    public static String removed(String feedTitle) {
        return "Removed <strong>" + Html.esc(feedTitle) + "</strong>.";
    }

    public static String brokenSince(Instant lastOk) {
        if (lastOk == null) return "Hasn't responded yet.";
        return "Hasn't responded since " + dayWord(lastOk) + ".";
    }

    public static String updated(Instant when, int posts) {
        if (when == null) return NEVER_UPDATED;
        return count(posts, "post", "posts") + " · checked " + ago(when);
    }

    // ---- Settings -------------------------------------------------------

    public static final String SET_SIZE_LABEL = "Posts per sitting";
    public static final String SET_SIZE_CAPTION = "How much to put in front of you at once.";
    public static final String SET_ORDER_LABEL = "Order";
    public static final String SET_ORDER_CAPTION = "Newest first means old posts are quietly never reached.";
    public static final String ORDER_NEWEST = "Newest first";
    public static final String ORDER_OLDEST = "Oldest first";
    public static final String SET_FORGET_LABEL = "Forget unread posts after";
    public static final String SET_FORGET_CAPTION = "Nothing is deleted unless you choose this.";
    public static final String FORGET_NEVER = "Never";
    public static final String FORGET_1W = "1 week";
    public static final String FORGET_2W = "2 weeks";
    public static final String FORGET_1M = "1 month";
    public static final String SAVE_SETTINGS = "Save";
    public static final String SETTINGS_SAVED = "Saved.";
    public static final String CLEAR_NOW = "Clear them now";

    public static String cleared(int n) {
        if (n == 0) return "Nothing old enough to clear.";
        return "Cleared " + count(n, "post", "posts") + ".";
    }

    // ---- Saved ----------------------------------------------------------

    public static final String SAVED_EMPTY = "Nothing kept yet. Press Save while reading and it lands here.";
    public static final String SAVED_REMOVE = "Remove";
    public static final String BACK_TO_SAVED = "← Back to Saved";

    // ---- Dead ends ------------------------------------------------------

    public static final String BACK_HOME = "← Back to the start";
    public static final String NOT_FOUND = "That page isn't here.";
    public static final String BROKE = "Something went wrong on this machine. Nothing was lost.";

    // ---- Small formatters -----------------------------------------------

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    public static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    public static String clock(Instant t) {
        return CLOCK.format(t.atZone(ZoneId.systemDefault()));
    }

    public static String date(Instant t) {
        return DATE.format(t.atZone(ZoneId.systemDefault()));
    }

    /** "Tuesday" when recent enough for that to be unambiguous, otherwise a plain date. */
    public static String dayWord(Instant t) {
        LocalDate day = t.atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        long days = today.toEpochDay() - day.toEpochDay();
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 7) return day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return date(t);
    }

    public static String ago(Instant t) {
        Duration d = Duration.between(t, Instant.now());
        long minutes = d.toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return count((int) minutes, "minute", "minutes") + " ago";
        long hours = d.toHours();
        if (hours < 24) return count((int) hours, "hour", "hours") + " ago";
        return dayWord(t);
    }
}
