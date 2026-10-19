package reader.feed;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the export file every other reader produces. This is the realistic way someone
 * arrives with feeds they already care about, which is why it ships in the first version.
 */
public final class Opml {

    public record Entry(String address, String title) {
    }

    /** @param skipped names of entries that looked like feeds but carried no address */
    public record Parsed(List<Entry> entries, List<String> skipped) {
    }

    private Opml() {
    }

    public static Parsed parse(byte[] data) {
        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(data), null, "",
                    org.jsoup.parser.Parser.xmlParser());
        } catch (Exception e) {
            return new Parsed(List.of(), List.of());
        }

        for (Element outline : doc.select("outline")) {
            String address = attr(outline, "xmlUrl", "xmlurl", "xmlURL");
            String title = attr(outline, "title", "text");
            if (address == null) {
                // A row holding other rows is a folder, not a failure. A named leaf with
                // nowhere to fetch from is a failure, and the reader deserves to hear about it.
                boolean folder = outline.children().stream()
                        .anyMatch(child -> child.tagName().equalsIgnoreCase("outline"));
                if (!folder && title != null) skipped.add(title);
                continue;
            }
            if (!seen.add(address)) continue;
            entries.add(new Entry(address, title));
        }
        return new Parsed(entries, skipped);
    }

    private static String attr(Element e, String... names) {
        for (String n : names) {
            if (e.hasAttr(n)) {
                String v = e.attr(n).trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }
}
