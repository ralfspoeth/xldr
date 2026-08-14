package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * A directory below one of the configured roots that holds a
 * {@value Delivery#FILE}, together with the four working directories.
 *
 * <pre>
 * &lt;root&gt;/&lt;feed&gt;/
 *     delivery.properties makes the directory a feed
 *     spec.json           what to do with what arrives
 *     env.properties      optional; what this deployment supplies to the spec
 *     in/                 producers move input files in here
 *     work/               claimed, currently being loaded
 *     archive/            loaded successfully, date partitioned
 *     hospital/           failed, together with an error log
 * </pre>
 * <p>
 * The delivery file is what promotes a directory to a feed, and the spec is what
 * lets the feed load: the two facts have different owners, the deployment and
 * the mapping author, and they arrive at different times. A feed that has the
 * first and not the second is {@link Pending} - real, watched, and accumulating
 * whatever its producer delivers, but with nothing yet to do with it.
 * <p>
 * That distinction is a type rather than a nullable field, so that
 * {@code FileProcessor} cannot be handed a feed it has no mapping for. The
 * alternative - one record with a {@code @Nullable MappingSpec} - would put the
 * guard in every method that loads instead of in the signature, and a guard that
 * only convention requires is one an IDE will eventually offer to remove.
 */
sealed interface Feed {

    /**
     * Created below a feed directory when the feed is registered, whether or not
     * it can load yet: a producer may deliver before the mapping author has
     * finished, and {@code in/} has to exist for that to be possible.
     */
    List<String> SUBDIRECTORIES = List.of("in", "work", "archive", "hospital");

    Path directory();

    Delivery delivery();

    /**
     * Stamp of {@value Delivery#FILE} when it was read; lets the registry re-read
     * it only when it actually changed.
     */
    FileTime deliveryModified();

    /**
     * A feed with no mapping spec. It keeps its directories and its watch, and
     * files its producer delivers stay in {@code in/} until a spec appears - at
     * which point the next scan of the inbox loads the backlog, the same way it
     * would recover from a missed event.
     */
    record Pending(Path directory, Delivery delivery, FileTime deliveryModified) implements Feed {}

    /**
     * A feed that can load: both files present and read.
     *
     * @param specModified stamp of the spec file when it was parsed
     */
    record Active(
            Path directory,
            Delivery delivery,
            FileTime deliveryModified,
            Path specFile,
            FileTime specModified,
            MappingSpec mappingSpec
    ) implements Feed {}

    default String name() {
        return directory().getFileName().toString();
    }

    /**
     * Whether this feed claims {@code file} - a data file under atomic delivery,
     * a marker under signalled delivery. Matched against the name only.
     */
    default boolean claims(Path file) {
        return delivery().claims(file);
    }

    default Path deliveryFile() {
        return directory().resolve(Delivery.FILE);
    }

    default Path in() {
        return directory().resolve("in");
    }

    default Path work() {
        return directory().resolve("work");
    }

    default Path archive() {
        return directory().resolve("archive");
    }

    default Path hospital() {
        return directory().resolve("hospital");
    }
}
