package reader;

import java.io.InputStream;
import java.io.UncheckedIOException;

/** Loads the sample feeds and export files the tests run against. */
public final class Fixtures {

    private Fixtures() {
    }

    public static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IllegalArgumentException("No fixture named " + name);
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
