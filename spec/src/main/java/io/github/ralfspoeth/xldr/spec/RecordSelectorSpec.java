package io.github.ralfspoeth.xldr.spec;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static java.util.function.Predicate.not;

/**
 * Selects the records of one kind from an input and lists the fields to read
 * from each.
 * <p>
 * Which records are of this kind is a {@link Locator}, of which there are three
 * cases and no others: an input with somewhere to point is pointed at, one where
 * every record is a candidate is filtered, and one whose records are all of a
 * kind needs neither. That used to be two nullable fields here, four states, one
 * of which described no input at all and had to be refused - and the three that
 * remained were then sorted out again by hand in each of the five adapters.
 *
 * @param name           the record selector name, referenced by a record mapping
 * @param locator        which records of the input are of this kind
 * @param fieldSelectors the fields to read from each record
 */
public record RecordSelectorSpec(
        String name,
        Locator locator,
        Collection<FieldSelectorSpec> fieldSelectors
) {

    public RecordSelectorSpec {
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
}
