package reader;

import reader.feed.Discovery;
import reader.feed.Fetcher;
import reader.feed.Refresher;
import reader.feed.Scheduler;
import reader.feed.Sweeper;
import reader.http.Server;
import reader.store.Db;
import reader.store.FeedStore;
import reader.store.ItemStore;
import reader.store.SessionStore;
import reader.store.Settings;
import reader.view.Pages;

import java.net.BindException;
import java.nio.file.Path;

/** Wires the pieces together and keeps the process alive. */
public final class Main {

    public static final int DEFAULT_PORT = 7777;

    public static void main(String[] args) throws Exception {
        int port = port(args);
        Path file = databaseFile();

        Db db = new Db(file);
        Settings settings = new Settings(db);
        FeedStore feeds = new FeedStore(db);
        ItemStore items = new ItemStore(db);
        SessionStore sittings = new SessionStore(db, items, settings);

        Fetcher fetcher = new Fetcher();
        Refresher refresher = new Refresher(fetcher, feeds, items, settings);
        Sweeper sweeper = new Sweeper(items, settings);
        Scheduler scheduler = new Scheduler(refresher, sweeper);
        Discovery discovery = new Discovery(fetcher);

        Pages pages = new Pages(feeds, items, sittings, settings, refresher, scheduler);
        Server server = new Server(pages, feeds, items, sittings, settings, discovery, refresher, sweeper);

        try {
            server.start(port);
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use on this machine.");
            System.err.println("Close whatever is using it, or start this with: --port 7778");
            System.exit(1);
        }
        scheduler.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            scheduler.close();
            refresher.close();
            db.close();
        }));

        System.out.println("Quiet Reader is ready at http://localhost:" + port);
        System.out.println("Your reading lives in " + file);
        System.out.println("Press Ctrl+C to stop.");

        // Everything else runs on daemon threads, so the main thread parks here.
        Thread.currentThread().join();
    }

    static int port(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    return DEFAULT_PORT;
                }
            }
        }
        String env = System.getenv("READER_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                return DEFAULT_PORT;
            }
        }
        return DEFAULT_PORT;
    }

    /** One local file, on one machine, with no account anywhere. */
    static Path databaseFile() {
        String override = System.getenv("READER_FILE");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".quiet-reader", "reading.db");
    }
}
