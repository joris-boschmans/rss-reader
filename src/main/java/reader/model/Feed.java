package reader.model;

import java.net.URI;
import java.time.Instant;

/** A source the reader follows. */
public record Feed(
        long id,
        String url,
        String title,
        String siteUrl,
        String etag,
        String lastModified,
        Instant lastFetchedAt,
        String fetchError,
        int failCount,
        Instant addedAt) {

    /** Never empty: falls back to the bare host so a list row always has a name. */
    public String displayTitle() {
        if (title != null && !title.isBlank()) return title.trim();
        return host();
    }

    public String host() {
        return hostOf(url);
    }

    public boolean broken() {
        return fetchError != null && !fetchError.isBlank();
    }

    public static String hostOf(String address) {
        if (address == null) return "";
        try {
            String h = URI.create(address).getHost();
            if (h == null) return address;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (RuntimeException e) {
            return address;
        }
    }
}
