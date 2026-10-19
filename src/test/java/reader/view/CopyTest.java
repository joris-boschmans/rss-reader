package reader.view;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static reader.Plain.assertPlain;

/** Guards the first rule: no jargon ever reaches the screen. */
class CopyTest {

    @Test
    void everyFixedStringIsPlainLanguage() throws IllegalAccessException {
        int checked = 0;
        for (Field f : Copy.class.getDeclaredFields()) {
            if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            assertPlain("Copy." + f.getName(), (String) f.get(null));
            checked++;
        }
        assertTrue(checked > 30, "only found " + checked + " strings; has Copy moved?");
    }

    @Test
    void everyAssembledSentenceIsPlainLanguageToo() {
        Instant now = Instant.now();
        List<String> sentences = List.of(
                Copy.postsWaiting(23),
                Copy.postsWaiting(1),
                Copy.partway(7, 20),
                Copy.nothingNewSince(now),
                Copy.nothingNewSince(null),
                Copy.checkedJustNow(6),
                Copy.checkedJustNow(0),
                Copy.counter(7, 20),
                Copy.nextCheckAround(now),
                Copy.moreWaiting(30),
                Copy.added("The Verge", 23),
                Copy.addedNoPosts("The Verge"),
                Copy.unreachable("stratechery.com"),
                Copy.alreadyFollowing("The Verge"),
                Copy.imported(34),
                Copy.alreadyHad(1),
                Copy.alreadyHad(2),
                Copy.importSkipped(List.of("A broken entry")),
                Copy.removeConfirm("The Verge"),
                Copy.removed("The Verge"),
                Copy.brokenSince(now.minus(3, ChronoUnit.DAYS)),
                Copy.brokenSince(null),
                Copy.updated(now, 23),
                Copy.updated(null, 0),
                Copy.cleared(12),
                Copy.cleared(0),
                Copy.ago(now),
                Copy.ago(now.minus(90, ChronoUnit.MINUTES)),
                Copy.dayWord(now.minus(3, ChronoUnit.DAYS)),
                Copy.date(now));

        for (String s : sentences) assertPlain("a sentence", s);
    }

    @Test
    void countsAgreeWithTheirNouns() {
        assertTrue(Copy.postsWaiting(1).contains("1 post<"), Copy.postsWaiting(1));
        assertTrue(Copy.postsWaiting(2).contains("2 posts"));
        assertTrue(Copy.imported(1).contains("1 feed."));
        assertTrue(Copy.imported(34).contains("34 feeds."));
    }

    @Test
    void namesAreEscapedBeforeTheyReachThePage() {
        String notice = Copy.added("<script>alert(1)</script>", 3);
        assertFalse(notice.contains("<script>"), notice);
        assertTrue(notice.contains("&lt;script&gt;"));
    }
}
