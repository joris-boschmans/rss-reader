package reader.model;

import java.time.Instant;

/**
 * A finite reading sitting: a fixed snapshot of posts taken when it began.
 * Posts arriving afterwards cannot join it, which is what makes "done" mean done.
 */
public record Sitting(long id, Instant startedAt, Instant finishedAt, int position, int total) {

    public boolean open() {
        return finishedAt == null;
    }

    /** 1-based number of the post currently in front of the reader. */
    public int humanPosition() {
        return Math.min(position + 1, total);
    }
}
