package io.github.ralfspoeth.xldr.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A feed's mapping spec and a file it has already loaded, found by looking at
 * the directories rather than by asking a running server.
 *
 * <p>This exists for {@code xldr check} with no argument, which sweeps every
 * feed a deployment would register. It is one type and one method on purpose:
 * finding a spec means knowing that a feed sits exactly one level below a root,
 * that its spec is called {@code spec.json} or {@code spec.xml}, and that
 * {@code archive/} is partitioned {@code year/month/day} - four facts that
 * belong to this package and that a caller elsewhere should not have to learn.
 * {@code Feed}, {@code Delivery}, {@code FeedRegistry} and {@code MappingSpecs}
 * all stay where 0.52 put them; what crosses the module boundary is the answer,
 * not the ingredients.
 *
 * <p><strong>This is not the registry.</strong> Nothing here reads
 * {@code delivery.properties} or decides whether a feed is active, and a
 * {@code FeedSpec} says only that a directory in feed position holds a spec. A
 * server would additionally require the delivery file; a check that refused to
 * look at a spec for want of one would be refusing to do the thing it was asked
 * to do, since a spec being wrong is worth knowing before the delivery file
 * arrives as much as after.
 *
 * @param directory      the feed directory, one level below a root
 * @param specFile       its {@code spec.json} or {@code spec.xml}
 * @param newestArchived the most recently archived input, where the feed has
 *                       loaded anything - the best sample there is, being a real
 *                       file from the real producer that really loaded
 */
public record FeedSpec(Path directory, Path specFile, Optional<Path> newestArchived) {

    private static final System.Logger LOG = System.getLogger(FeedSpec.class.getName());

    /**
     * Every feed directly below one of the roots that holds a mapping spec.
     *
     * <p>Directly below, because that is where a server looks:
     * {@code FeedRegistry.reconcileRoot} lists a root's immediate children and no
     * deeper, so a spec two levels down is one the server will never register and
     * reporting on it would describe a feed that does not exist.
     *
     * <p>A root that cannot be listed is logged and skipped rather than thrown
     * from: one unreadable mount should not stop a deployment-wide check of all
     * the others, and the caller finds out by the feeds simply not being there.
     *
     * @param roots the configured feed roots, as {@code Config#roots} gives them
     * @return one entry per feed with a spec, ordered by directory so that two
     * runs of a check report in the same order
     */
    public static List<FeedSpec> under(Collection<Path> roots) {
        var found = new ArrayList<FeedSpec>();
        for (var root : roots) {
            try (var children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .sorted()
                        .forEach(dir -> MappingSpecs.find(dir)
                                .ifPresent(spec -> found.add(
                                        new FeedSpec(dir, spec, newestArchived(dir)))));
            } catch (IOException | RuntimeException e) {
                // includes the IllegalStateException MappingSpecs.find throws for a
                // directory holding both spec.json and spec.xml: that is a real
                // finding, but it belongs to the feed rather than to the sweep, and
                // the reader meets it again the moment the spec is read
                LOG.log(System.Logger.Level.WARNING, () -> "cannot scan root " + root + ": " + e);
            }
        }
        return List.copyOf(found);
    }

    /**
     * The newest file under {@code archive/}, which
     * {@code FileProcessor} partitions as {@code year/month/day}.
     *
     * <p>Descended by name rather than walked and stat'ed. The partitions are
     * zero-padded - {@code 2026/09/08} - so their lexicographic order is their
     * chronological order, and taking the highest-sorting directory at each level
     * reaches the newest day without touching a feed's whole history, which after
     * a year of daily deliveries is three hundred directories of files nobody
     * needs to look at.
     *
     * <p>It falls back to the next-highest where a day directory turns out empty,
     * which can happen: the archive is created before the move into it, so a
     * crash between the two leaves one behind.
     */
    private static Optional<Path> newestArchived(Path feedDirectory) {
        return newestUnder(feedDirectory.resolve("archive"), 3);
    }

    /**
     * @param dir    a directory to descend into
     * @param levels how many partition levels remain below it
     * @return the newest file, by the naming convention above
     */
    private static Optional<Path> newestUnder(Path dir, int levels) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        if (levels == 0) {
            return newestFileIn(dir);
        }
        for (var child : highestFirst(dir, true)) {
            var found = newestUnder(child, levels - 1);
            if (found.isPresent()) {
                return found;
            }
        }
        // a feed archived before the partitioning existed, or a file dropped in by
        // hand: take it rather than report nothing
        return newestFileIn(dir);
    }

    private static Optional<Path> newestFileIn(Path dir) {
        return highestFirst(dir, false).stream().findFirst();
    }

    /** the directory's children of the wanted kind, highest name first */
    private static List<Path> highestFirst(Path dir, boolean directories) {
        try (var children = Files.list(dir)) {
            return children
                    .filter(p -> directories == Files.isDirectory(p))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "cannot list " + dir + ": " + e);
            return List.of();
        }
    }

    /** @return the feed's name, which is its directory's, as the server reports it */
    public String name() {
        var file = directory.getFileName();
        return file == null ? directory.toString() : file.toString();
    }
}
