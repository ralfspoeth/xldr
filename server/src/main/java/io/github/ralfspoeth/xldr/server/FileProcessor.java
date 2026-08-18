package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.xldr.ldr.Statistics;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import static java.lang.System.Logger.Level.*;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/**
 * Runs one input file through its feed and files it away afterwards.
 * <p>
 * A file is claimed by moving it out of {@code in/} into {@code work/} with an
 * atomic move. That move is the lock: whoever wins it owns the file, so two
 * event callbacks - or two server processes on the same tree - cannot both load
 * it. It also means a crash leaves the file in {@code work/}, where it is a
 * known-ambiguous case rather than something that gets loaded twice.
 * <p>
 * Every caller arrives on a virtual thread of its own - one per watch event -
 * so the number of loads running at once is bounded here rather than by the
 * connection pool: a semaphore is a limit one can reason about, whereas
 * exhausting the pool merely turns into threads parked in
 * {@code getConnection()}. Claiming and filing away are not counted, only the
 * load itself.
 */
class FileProcessor {

    private static final System.Logger LOG = System.getLogger(FileProcessor.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final ConnectionSource connectionSource;
    /**
     * fair, so a file cannot be starved by a steady stream of newer arrivals
     */
    private final Semaphore loadPermits;
    private final Statistics statistics;

    public FileProcessor(ConnectionSource connectionSource, int maxConcurrentLoads, Statistics statistics) {
        this.connectionSource = connectionSource;
        this.loadPermits = new Semaphore(maxConcurrentLoads, true);
        this.statistics = statistics;
    }

    /**
     * Reacts to one file appearing in {@code in/}.
     * <p>
     * With no sentinel the producer delivers atomically, so the arriving file is
     * ready and is loaded directly. With a sentinel it is the marker's arrival
     * that matters: the marker names the data file (its own name minus the
     * suffix), that file is loaded, and the marker is consumed. A data file
     * arriving on its own is ignored until its marker follows.
     * <p>
     * A file the feed does not {@link Feed#claims claim} is left in {@code in/}
     * untouched.
     */
    public void onArrival(Feed.Active feed, Path file) {
        switch (feed.delivery()) {
            case Delivery.Atomic atomic -> {
                if (atomic.claims(file)) {
                    process(feed, file);
                }
            }
            case Delivery.Signalled signalled -> {
                if (signalled.claims(file)) {
                    signalled.sentinel().dataFileOf(file).ifPresentOrElse(
                            data -> processSignalled(feed, file, data),
                            () -> discardMarker(feed, file, "names no data file"));
                }
            }
        }
    }

    /**
     * Claims {@code file} and loads it. Does nothing if the feed has been
     * switched off in the meantime, if the file has already been claimed by
     * someone else, or if it has disappeared.
     */
    public void process(Feed.Active feed, Path file) {
        if (configured(feed, file)) {
            var claimed = claimOrLog(feed, file);
            if (claimed != null) {
                runLoad(feed, claimed, file.getFileName().toString());
            }
        }
    }

    /**
     * Loads the data file a sentinel marker points at, then consumes the marker.
     * <p>
     * The data file is claimed first - that move is the lock - and only then is
     * the marker deleted, so a crash in between leaves the data safely in
     * {@code work/} (recovered at startup) and at worst an orphaned marker
     * (cleaned by the next scan), never a data file that is loaded twice.
     */
    private void processSignalled(Feed.Active feed, Path sentinel, Path dataFile) {
        // before the marker is consumed: a feed that is no longer configured
        // must leave both files where they are
        if (configured(feed, dataFile)) {
            var claimed = claimOrLog(feed, dataFile);
            deleteQuietly(sentinel);
            if (claimed != null) {
                runLoad(feed, claimed, dataFile.getFileName().toString());
            }
        }
    }

    /**
     * Whether the spec that made this a feed has gone since the feed was
     * registered - in which case the file is left in {@code in/} untouched.
     * <p>
     * The registry is authoritative but not instantaneous, and it cannot be:
     * the feed directory and its {@code in/} are two watch keys, handled on two
     * threads, so a file delivered just after the spec was removed can arrive
     * here while the removal is still on its way through. The periodic scan has
     * the same window from the other side - it takes the active feeds and then
     * walks their inboxes, and the spec may go between the two.
     * <p>
     * The window cannot be locked shut, because what is stale is the registry's
     * picture of the file system rather than any state of ours. So the file
     * system is asked once more at the last moment where the answer still costs
     * nothing - one stat, immediately before the claim that would otherwise be
     * irreversible. Removing a spec is how a feed is switched off, and an
     * operator who has switched one off is entitled to expect that nothing more
     * is loaded from it.
     */
    private static boolean configured(Feed.Active feed, Path file) {
        boolean conf = Files.exists(feed.specFile());
        if(!conf) {
            LOG.log(DEBUG, () -> "not loading " + file.getFileName()
                    + " [" + feed.name() + "]: the spec is gone");
        }
        return conf;
    }

    private @Nullable Path claimOrLog(Feed feed, Path file) {
        try {
            return claim(feed, file);
        } catch (IOException e) {
            LOG.log(WARNING, () -> "could not claim " + file + ": " + e);
            return null;
        }
    }

    /**
     * Loads an already-claimed file, under the concurrency permit, and files it
     * away afterwards.
     */
    private void runLoad(Feed.Active feed, Path claimed, String originalName) {
        if (acquirePermit(feed, claimed)) {
            // from here the permit is held, and the finally below is what returns it
            statistics.loadStarted();
            try {
                var rows = new LoadJob(feed.mappingSpec(), feed.directory(), connectionSource).load(claimed);
                var target = archive(feed, claimed);
                statistics.loaded(feed.name(), rows);
                LOG.log(INFO, () -> "loaded " + rows + " row(s) from " + originalName
                        + " [" + feed.name() + "] -> " + target);
            } catch (Exception e) {
                statistics.failed(feed.name());
                LOG.log(ERROR, () -> "load failed for " + originalName + " [" + feed.name() + "]: " + e);
                try {
                    hospitalise(feed, claimed, e);
                } catch (IOException io) {
                    LOG.log(ERROR, () -> "could not move " + claimed + " to the hospital: " + io);
                }
            } finally {
                statistics.loadFinished();
                loadPermits.release();
            }
        }
    }

    /**
     * Waits for a concurrency permit, putting the file back if the wait is
     * interrupted.
     * <p>
     * Separate from the load it guards, and deliberately so: {@code release()}
     * on a {@link Semaphore} that was never acquired does not fail, it hands out
     * a permit that did not exist. An acquire folded into the same {@code try}
     * as the load would be covered by the same {@code finally}, so an
     * interruption while waiting - which is what shutdown looks like - would
     * raise the concurrency limit by one every time, and take
     * {@code loadsInProgress} below zero with it.
     *
     * @return whether the permit was acquired; the caller must release it if so
     */
    private boolean acquirePermit(Feed.Active feed, Path claimed) {
        try {
            loadPermits.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unclaim(feed, claimed);
            return false;
        }
    }

    private void discardMarker(Feed feed, Path marker, String why) {
        LOG.log(WARNING, () -> "sentinel " + marker.getFileName() + " [" + feed.name() + "] " + why);
        deleteQuietly(marker);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.log(WARNING, () -> "could not delete " + path + ": " + e);
        }
    }

    /**
     * Undoes a claim that was never acted upon - only safe because no load has
     * been attempted yet.
     */
    private void unclaim(Feed feed, Path claimed) {
        try {
            Files.move(claimed, unique(feed.in(), claimed.getFileName().toString()), ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(WARNING, () -> "could not return " + claimed + " to " + feed.in() + ": " + e);
        }
    }

    /**
     * @return the file in {@code work/}, or {@code null} if it was not ours to claim
     */
    private @Nullable Path claim(Feed feed, Path file) throws IOException {
        if (!Files.isRegularFile(file) || isIgnored(file)) {
            return null;
        }
        var target = unique(feed.work(), file.getFileName().toString());
        try {
            return Files.move(file, target, ATOMIC_MOVE);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private Path archive(Feed feed, Path claimed) throws IOException {
        var today = LocalDate.now();
        var dir = feed.archive()
                .resolve(String.valueOf(today.getYear()))
                .resolve("%02d".formatted(today.getMonthValue()))
                .resolve("%02d".formatted(today.getDayOfMonth()));
        Files.createDirectories(dir);
        var target = unique(dir, claimed.getFileName().toString());
        Files.move(claimed, target);
        return target;
    }

    /**
     * The log first, the input last.
     * <p>
     * The other order leaves a window - short, but a window - in which
     * {@code hospital/} holds a file with no explanation beside it, and if the
     * process dies inside that window the file stays that way. An operator finds
     * a failed input and nothing saying why, and {@code filesInHospital} counts
     * it, since that gauge counts everything that is not a {@code .log}. Writing
     * the log first costs nothing: {@link #unique} looks only for the input's own
     * name, so the log cannot change which name the input gets, and a failure to
     * write it leaves the input in {@code work/} for {@link #recoverWork} rather
     * than hospitalised in silence.
     * <p>
     * The move is the last thing that happens either way, which makes the input's
     * appearance the signal that this feed is done with the file.
     */
    private void hospitalise(Feed.Active feed, Path claimed, Exception failure) throws IOException {
        Files.createDirectories(feed.hospital());
        var name = claimed.getFileName().toString();
        var target = unique(feed.hospital(), name);
        var log = feed.hospital().resolve(target.getFileName() + "." + LocalDateTime.now().format(STAMP) + ".log");
        var trace = new StringWriter();
        try (var out = new PrintWriter(trace)) {
            out.println("feed:    " + feed.name());
            out.println("spec:    " + feed.specFile());
            out.println("input:   " + name);
            out.println("failed:  " + LocalDateTime.now());
            // the loader names the record it was on, which is the line an
            // operator wants first; the trace below is for everything after that
            out.println("cause:   " + failure.getMessage());
            out.println();
            failure.printStackTrace(out);
        }
        Files.writeString(log, trace.toString());
        Files.move(claimed, target);
    }

    /**
     * Anything left in {@code work/} was claimed by a process that then died. It
     * is unknown whether the load committed, so it is not retried automatically -
     * that could duplicate rows - but handed to an operator.
     */
    public void recoverWork(Feed feed) {
        try (var stale = Files.list(feed.work())) {
            stale.filter(Files::isRegularFile).forEach(file -> {
                try {
                    var target = unique(feed.hospital(), file.getFileName().toString());
                    Files.createDirectories(feed.hospital());
                    // the log first and the move last, as in hospitalise: a file
                    // in hospital/ is never there without its explanation
                    Files.writeString(
                            feed.hospital().resolve(target.getFileName() + ".recovered.log"),
                            """
                                    Found in work/ at startup. A previous run claimed this file and did not finish.
                                    Whether its transaction committed is unknown - check the target tables
                                    before moving it back into in/.
                                    """);
                    Files.move(file, target);
                    LOG.log(WARNING, () -> "recovered stale claim " + file.getFileName()
                            + " [" + feed.name() + "] -> hospital");
                } catch (IOException e) {
                    LOG.log(ERROR, () -> "could not recover " + file + ": " + e);
                }
            });
        } catch (IOException e) {
            LOG.log(WARNING, () -> "cannot list " + feed.work() + ": " + e);
        }
    }

    /**
     * Processes everything already sitting in {@code in/} - files that arrived
     * before the directory was watched, or whose event was lost. This is the
     * contract; watch events only make the reaction quicker.
     * <p>
     * With no sentinel every file is ready and is loaded. With a sentinel only
     * the markers act: each is matched to its data file, or, if that file is
     * gone, cleaned up as an orphan. Data files without a marker are left where
     * they are.
     */
    public void scanInbox(Feed.Active feed) {
        List<Path> claimed;
        try (var files = Files.list(feed.in())) {
            // one filter for both kinds: under atomic delivery this selects the
            // data files, under signalled delivery the markers
            claimed = files.filter(Files::isRegularFile).filter(feed::claims).toList();
        } catch (IOException e) {
            LOG.log(WARNING, () -> "cannot list " + feed.in() + ": " + e);
            return;
        }
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var file : claimed) {
                switch (feed.delivery()) {
                    case Delivery.Atomic _ -> exec.submit(() -> process(feed, file));
                    case Delivery.Signalled signalled -> signalled.sentinel()
                            .dataFileOf(file)
                            .ifPresentOrElse(data -> {
                                if (Files.exists(data)) {
                                    exec.submit(() -> processSignalled(feed, file, data));
                                } else {
                                    discardMarker(feed, file, "orphaned, no " + data.getFileName());
                                }
                            }, () -> discardMarker(feed, file, "names no data file"));
                }
            }
        }
    }

    private static boolean isIgnored(Path file) {
        var name = file.getFileName().toString();
        return name.startsWith(".") || name.endsWith(".tmp") || name.endsWith(".part");
    }

    private static Path unique(Path dir, String name) {
        var candidate = dir.resolve(name);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        var stamp = LocalDateTime.now().format(STAMP);
        var dot = name.lastIndexOf('.');
        return dir.resolve(dot > 0
                ? name.substring(0, dot) + "." + stamp + name.substring(dot)
                : name + "." + stamp);
    }
}
