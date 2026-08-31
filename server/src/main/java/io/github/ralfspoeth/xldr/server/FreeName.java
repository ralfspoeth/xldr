package io.github.ralfspoeth.xldr.server;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A path in a directory that nothing else occupies.
 * <p>
 * Every file this server moves goes through here: an input claimed into
 * {@code work/}, one archived after a load, one sent to {@code hospital/}, one
 * recovered from a process that died. All four use {@link Files#move} without
 * {@code REPLACE_EXISTING}, deliberately - nothing this server has been handed
 * is ever overwritten - so the name it moves to has to be free before it asks.
 * <p>
 * The consequence of getting this wrong is worst in the archive, and worth
 * spelling out: a load commits, the archive move then fails, and the file is
 * counted as a failure and sent to the hospital with its rows already in the
 * database. An operator redelivers it, and the rows arrive twice.
 * <p>
 * The discriminating text is a parameter rather than a timestamp taken here, so
 * that what makes a name different stays the caller's decision and a test can
 * supply one that collides on purpose.
 * <p>
 * <strong>Best effort, and the move is what guarantees.</strong> Between the
 * look and the caller's {@code move} another process may take the name, so this
 * cannot promise the path is still free when it is used. What it does is make
 * that outcome rare; {@code move} without {@code REPLACE_EXISTING} is what makes
 * it harmless, by throwing rather than overwriting. Read the two together: this
 * type avoids the exception, the move mode decides what happens if it comes
 * anyway.
 */
final class FreeName {

    private FreeName() {
    }

    /**
     * A path under {@code directory} for {@code name}, or a variant of it that
     * nothing occupies.
     * <p>
     * The plain name where it is free. Otherwise {@code discriminator} goes
     * before the extension, so that {@code data.csv} becomes
     * {@code data.<discriminator>.csv} and stays recognisably the same file to
     * whoever finds it.
     */
    static Path in(Path directory, String name, String discriminator) {
        var candidate = directory.resolve(name);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        // The discriminated name is checked too, which it used to not be: the
        // plain name was tested, a timestamp appended, and the result handed back
        // unexamined. Two files of one name reaching a directory inside the same
        // millisecond is unlikely and not impossible, and the caller then moved
        // onto a file that was already there.
        for (int n = 1; n <= LIMIT; n++) {
            var variant = directory.resolve(discriminated(name, n == 1 ? discriminator : discriminator + "-" + n));
            if (!Files.exists(variant)) {
                return variant;
            }
        }
        throw new IllegalStateException("no free name for '" + name + "' in " + directory + " after "
                + LIMIT + " tries with '" + discriminator + "'. Something is creating files as fast as"
                + " this can look, or the directory cannot be read");
    }

    /**
     * How many variants to try before deciding the directory is the problem.
     * <p>
     * A bound rather than a loop that cannot end: every turn costs a stat, and a
     * directory that never yields a free name would otherwise hold the load
     * thread forever. Failing loudly is the lesser harm - the caller's catch
     * hospitalises the file, and an operator gets a sentence rather than a
     * process that has stopped.
     */
    private static final int LIMIT = 1_000;

    private static String discriminated(String name, String discriminator) {
        var dot = name.lastIndexOf('.');
        return dot > 0
                ? name.substring(0, dot) + "." + discriminator + name.substring(dot)
                : name + "." + discriminator;
    }
}
