package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static java.util.function.Predicate.not;

/**
 * Selects the records of one kind from an input and lists the fields to read
 * from each.
 * <p>
 * Two ways of selecting, because inputs come in two shapes and the difference is
 * worth keeping. A tree or a sheet has to be <em>pointed at</em>: an XPath, a JSON
 * pointer, a range of cells, all of which say where the records are and none of
 * which every line could be. A flat file has no such place - every line is a
 * candidate - so the question there is which lines to keep, which is a
 * {@link Discriminator}.
 * <p>
 * The two used to share one attribute, and it read as one idea until you noticed
 * that {@code "//order"} and {@code "O"} have nothing whatever in common.
 *
 * @param name           the record selector name, referenced by a record mapping
 * @param selector       where the records are, for an input that has to be pointed
 *                       at; {@code null} for a flat one, and for a tree or sheet
 *                       holding only one kind of record
 * @param discriminator  which records are of this kind, for a flat input;
 *                       {@code null} where every record is
 * @param fieldSelectors the fields to read from each record
 */
public record RecordSelectorSpec(
        String name,
        @Nullable String selector,
        @Nullable Discriminator discriminator,
        Collection<FieldSelectorSpec> fieldSelectors
) implements Serializable {

    /**
     * Canonical constructor, refusing the one combination that describes no input:
     * a record selector cannot both be pointed at and be filtered, because no
     * format offers both. A spec saying both has confused an XPath with a column.
     */
    public RecordSelectorSpec {
        if (selector != null && discriminator != null) {
            throw new IllegalArgumentException("record selector '" + name
                    + "' has both a selector and a discriminator: '" + selector + "' says where the"
                    + " records are, " + discriminator + " says which lines are of this kind, and no"
                    + " input is read both ways");
        }
        refuseDuplicateNames(name, fieldSelectors);
        fieldSelectors = List.copyOf(fieldSelectors);
    }

    /**
     * A field selector's name is what a mapping refers to, so two of them cannot
     * share one: the mapping would be naming both and reading one.
     * <p>
     * Here rather than in the adapters, because every adapter builds a map keyed
     * by this name and each was therefore picking a winner of its own - the CSV
     * adapter the first declaration, the others whichever the loop reached last,
     * and none of them saying so. One rule refused in one place is the difference
     * between a format that has an answer and five that each have one.
     * <p>
     * Refused rather than resolved, because a duplicate is not something anyone
     * writes on purpose. It is a name that was meant to be different, which means
     * the field the author intended is missing - and a mapping naming <em>that</em>
     * one fails somewhere else entirely, with a message about the wrong thing.
     */
    private static void refuseDuplicateNames(String name, Collection<FieldSelectorSpec> fieldSelectors) {
        var seen = new HashSet<String>();
        var repeated = fieldSelectors.stream()
                .map(FieldSelectorSpec::name)
                .filter(not(seen::add))
                .distinct()
                .toList();
        if (!repeated.isEmpty()) {
            throw new IllegalArgumentException("record selector '" + name + "' declares "
                    + repeated + " more than once. A field selector's name is what a mapping refers"
                    + " to, so two of them cannot share it; the usual cause is a name that was meant"
                    + " to be different");
        }
    }

    /**
     * A record selector that points somewhere, or points nowhere in particular.
     * <p>
     * There is deliberately no matching three-argument constructor taking a
     * {@link Discriminator}: {@code null} in that position would then be
     * ambiguous between the two, and {@code null} in that position is what most
     * of the specs in this project write. A discriminated record selector uses
     * the canonical constructor and says {@code null} for the selector it does
     * not have, which is the more honest line to read anyway.
     */
    public RecordSelectorSpec(String name, @Nullable String selector,
                              Collection<FieldSelectorSpec> fieldSelectors) {
        this(name, selector, null, fieldSelectors);
    }

    /**
     * The selector of an adapter that cannot do without one - an XPath, a JSON
     * pointer, a spreadsheet range all have to point somewhere.
     *
     * @return the selector, never blank
     * @throws IllegalArgumentException if the spec left it out
     */
    public String requireSelector() {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException(
                    "record selector '" + name + "' needs a selector for this input");
        }
        return selector;
    }

    /**
     * Refuses a selector on an input that has nowhere to point.
     * <p>
     * The flat adapters used to read {@link #selector()} as a first-column
     * discriminator, so a spec written before the two parted says {@code "O"}
     * where it now means {@code discriminator}. Ignoring it would leave every line
     * matching every record selector and the load reporting a great many rows, so
     * it is named and refused instead.
     */
    public void refuseSelector(String what) {
        if (selector != null) {
            throw new IllegalArgumentException("record selector '" + name + "' has a selector, '"
                    + selector + "', but " + what + ". A value the records are recognised by is a"
                    + " discriminator: { \"nth\": 1, \"equals\": \"" + selector + "\" }");
        }
    }

    /**
     * Refuses a discriminator on an input whose records have to be located.
     * <p>
     * The mirror of {@link #refuseSelector}, and worth having for the same
     * reason. A discriminator picks records out of a file where every line is a
     * candidate; a tree, a document or a sheet has to be pointed at instead, so
     * there is nothing for one to filter.
     * <p>
     * The canonical constructor already refuses a selector and a discriminator
     * together, so this only ever fires where the selector is absent - which is
     * exactly where an adapter would otherwise proceed on a default. For JSON an
     * absent selector legitimately means the whole document, so a discriminator
     * there was being dropped in silence; for the adapters that require a
     * selector it was refused, but with a message telling the author they had
     * forgotten one when in fact they had written the other thing.
     */
    public void refuseDiscriminator(String what) {
        if (discriminator != null) {
            throw new IllegalArgumentException("record selector '" + name + "' has a discriminator, "
                    + discriminator + ", but " + what + ". Where the records are is a 'selector',"
                    + " in this adapter's own syntax; a discriminator is for a flat file, where"
                    + " every record is a candidate and the question is which to keep");
        }
    }
}
