package io.github.ralfspoeth.xldr.server;

import java.nio.file.Path;

/**
 * Delivery signalled by a marker: the data file may be written in place, and the
 * marker that follows it says so.
 * <p>
 * What is claimed here is the <em>marker</em>. The data file it names is not
 * matched against anything - the marker having vouched for it - which is why this
 * asks {@link Sentinel#isMarker} rather than a pattern of its own.
 * <p>
 * Package-private, as {@link Delivery}'s other case is. This one has something a
 * caller might want that {@code claims} does not give them - the {@link
 * #sentinel}, which knows the data file a marker names - and that is used by
 * {@code FileProcessor} in this package. If it is ever needed outside, the answer
 * is a method on {@code Delivery} rather than making the case public: the
 * question is "what data file does this arrival stand for", and only one of the
 * two cases has an interesting answer.
 */
record SignalledDelivery(Sentinel sentinel) implements Delivery {

    @Override
    public boolean claims(Path file) {
        return sentinel.isMarker(file);
    }

    @Override
    public String toString() {
        return Delivery.SENTINEL + "=" + sentinel;
    }
}
