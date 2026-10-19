package reader.feed;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Turns fetched bytes into posts. Encoding detection and both feed dialects come from Rome. */
public final class Parser {

    public record ParsedItem(
            String guid, String url, String title, String author,
            Instant publishedAt, String contentHtml) {
    }

    public record Parsed(String title, String siteUrl, List<ParsedItem> items) {
    }

    private Parser() {
    }

    /**
     * @param contentType the declared type, which helps Rome pick the right encoding
     * @throws Exception when the bytes are not a feed at all
     */
    public static Parsed parse(byte[] body, String contentType) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(false); // keeps external entities out of untrusted input
        SyndFeed feed;
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body), contentType, true)) {
            feed = input.build(reader);
        }

        List<ParsedItem> items = new ArrayList<>();
        for (SyndEntry e : feed.getEntries()) {
            String url = trimToNull(e.getLink());
            String title = trimToNull(e.getTitle());
            Instant published = published(e);
            items.add(new ParsedItem(
                    guidFor(e, url, title, published),
                    url,
                    title,
                    trimToNull(e.getAuthor()),
                    published,
                    contentOf(e)));
        }
        return new Parsed(trimToNull(feed.getTitle()), trimToNull(feed.getLink()), items);
    }

    private static Instant published(SyndEntry e) {
        if (e.getPublishedDate() != null) return e.getPublishedDate().toInstant();
        if (e.getUpdatedDate() != null) return e.getUpdatedDate().toInstant();
        return null;
    }

    private static String contentOf(SyndEntry e) {
        for (SyndContent c : e.getContents()) {
            if (c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
        }
        SyndContent d = e.getDescription();
        return d == null ? "" : (d.getValue() == null ? "" : d.getValue());
    }

    /**
     * A stable identity per post: the feed's own id, failing that the link, failing that a
     * digest of title and date — so feeds with no stable id still deduplicate.
     */
    static String guidFor(SyndEntry e, String url, String title, Instant published) {
        String id = trimToNull(e.getUri());
        if (id != null) return id;
        if (url != null) return url;
        String seed = (title == null ? "" : title) + "|" + (published == null ? "" : published);
        return "digest:" + sha256(seed);
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
