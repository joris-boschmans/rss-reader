package reader.feed;

import reader.store.ItemStore;
import reader.store.Settings;

import java.time.Instant;

/** Applies the "forget unread posts after" setting. Does nothing at all by default. */
public final class Sweeper {

    private final ItemStore items;
    private final Settings settings;

    public Sweeper(ItemStore items, Settings settings) {
        this.items = items;
        this.settings = settings;
    }

    /** @return how many posts were forgotten */
    public int sweep() {
        Instant cutoff = settings.forgetCutoff();
        return cutoff == null ? 0 : items.deleteForgettable(cutoff);
    }
}
