package reader.model;

import java.time.Instant;

/** One post. {@code feedTitle} and {@code feedSite} are filled by the join, not stored on the row. */
public record Item(
        long id,
        long feedId,
        String guid,
        String url,
        String title,
        String author,
        Instant publishedAt,
        String contentHtml,
        String excerpt,
        Instant fetchedAt,
        Instant readAt,
        Instant savedAt,
        String feedTitle,
        String feedSite) {

    public String displayTitle() {
        return (title == null || title.isBlank()) ? "Untitled" : title.trim();
    }

    public boolean isSaved() {
        return savedAt != null;
    }
}
