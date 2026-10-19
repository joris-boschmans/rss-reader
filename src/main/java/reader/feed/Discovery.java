package reader.feed;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns whatever was pasted into something to follow.
 *
 * <p>One field has to accept {@code stratechery.com}, {@code https://theverge.com} and a
 * direct feed address alike, because asking a reader to find the feed themselves is the
 * step where this kind of app usually loses them.
 */
public final class Discovery {

    public enum Kind {FOUND_ONE, FOUND_MANY, NONE, UNREACHABLE}

    /** A feed we could follow. The title is what the reader picks from; the address stays hidden. */
    public record Candidate(String address, String title) {
    }

    public record Result(Kind kind, List<Candidate> candidates, String host) {
        public Candidate only() {
            return candidates.get(0);
        }
    }

    /** Tried only when a page advertises no feed of its own. */
    private static final String[] COMMON_PATHS = {"/feed", "/rss.xml", "/index.xml", "/atom.xml"};

    private static final int MAX_CANDIDATES = 5;

    private final Fetcher fetcher;

    public Discovery(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    public Result discover(String input) {
        String cleaned = normalise(input);
        if (cleaned.isEmpty()) return new Result(Kind.NONE, List.of(), "");
        String host = reader.model.Feed.hostOf(firstAttempt(cleaned));

        Fetcher.Result response = null;
        for (String attempt : attempts(cleaned)) {
            try {
                Fetcher.Result r = fetcher.get(attempt);
                if (r.ok()) {
                    response = r;
                    break;
                }
            } catch (Exception e) {
                // Try the next spelling of the address before giving up.
            }
        }
        if (response == null) return new Result(Kind.UNREACHABLE, List.of(), host);

        boolean looksLikeHtml = response.contentType() != null
                && response.contentType().toLowerCase().contains("html");

        if (!looksLikeHtml) {
            String title = feedTitle(response);
            if (title != null) {
                return new Result(Kind.FOUND_ONE, List.of(new Candidate(response.finalUrl(), title)), host);
            }
        }

        List<Candidate> found = linksIn(response.text(), response.finalUrl());
        if (found.isEmpty()) found = probeCommonPaths(response.finalUrl());
        if (found.isEmpty()) return new Result(Kind.NONE, List.of(), host);

        List<Candidate> named = withTitles(found, host);
        if (named.isEmpty()) return new Result(Kind.NONE, List.of(), host);
        return new Result(named.size() == 1 ? Kind.FOUND_ONE : Kind.FOUND_MANY, named, host);
    }

    /** Pure, so the picker's zero / one / many cases are testable without a network. */
    public static List<Candidate> linksIn(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        Map<String, Candidate> byAddress = new LinkedHashMap<>();
        for (Element link : doc.select("link[rel~=(?i)alternate][href]")) {
            String type = link.attr("type").toLowerCase();
            if (!(type.contains("rss") || type.contains("atom") || type.contains("xml"))) continue;
            String address = link.attr("abs:href");
            if (address.isBlank()) address = link.attr("href");
            if (address.isBlank()) continue;
            String title = link.attr("title").trim();
            byAddress.putIfAbsent(address, new Candidate(address, title.isEmpty() ? null : title));
        }
        return new ArrayList<>(byAddress.values());
    }

    private List<Candidate> probeCommonPaths(String pageUrl) {
        for (String path : COMMON_PATHS) {
            String attempt = join(pageUrl, path);
            if (attempt == null) continue;
            try {
                Fetcher.Result r = fetcher.get(attempt);
                if (!r.ok()) continue;
                String title = feedTitle(r);
                if (title != null) return List.of(new Candidate(r.finalUrl(), title));
            } catch (Exception e) {
                // A path that is not there is the expected outcome; keep looking.
            }
        }
        return List.of();
    }

    /** Every candidate shown to a reader needs a name, so unnamed ones are looked up. */
    private List<Candidate> withTitles(List<Candidate> candidates, String host) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates.subList(0, Math.min(candidates.size(), MAX_CANDIDATES))) {
            if (c.title() != null) {
                out.add(c);
                continue;
            }
            String title = null;
            try {
                Fetcher.Result r = fetcher.get(c.address());
                if (r.ok()) title = feedTitle(r);
            } catch (Exception e) {
                // Falls through to the host name below.
            }
            if (title == null && candidates.size() == 1) title = host;
            if (title != null) out.add(new Candidate(c.address(), title));
        }
        return out;
    }

    private static String feedTitle(Fetcher.Result r) {
        try {
            Parser.Parsed parsed = Parser.parse(r.body(), r.contentType());
            String title = parsed.title();
            return (title == null || title.isBlank()) ? reader.model.Feed.hostOf(r.finalUrl()) : title;
        } catch (Exception e) {
            return null;
        }
    }

    static String normalise(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.startsWith("feed://")) s = "https://" + s.substring("feed://".length());
        return s;
    }

    static List<String> attempts(String cleaned) {
        if (cleaned.contains("://")) return List.of(cleaned);
        return List.of("https://" + cleaned, "http://" + cleaned);
    }

    private static String firstAttempt(String cleaned) {
        return attempts(cleaned).get(0);
    }

    private static String join(String base, String path) {
        try {
            return java.net.URI.create(base).resolve(path).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
