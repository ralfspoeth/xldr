# tools

Maintenance scripts. Nothing in the build runs them, which is the point: they check the
documentation, and documentation is not compiled.

Both sweep [the tutorial](../docs/tutorial/README.md), whose twelve pages carry specs, sample
files and `create table` statements that nothing else looks at. Every claim in them is a claim
about the current release, and the format changed twice in the last four.

## `check-tutorial-statically.py`

    pip install jsonschema
    python3 tools/check-tutorial-statically.py

No Java, no database, a second to run. For each page it validates the spec against
`docs/schema/mapping-spec-0.35.json`, checks that a mapping names a record selector the input
declares, that a field mapping reads a field selector that exists, and that every `column` is
one of the target table's - the tables being those the page creates, or those an earlier page
left behind, which is what a reader following in order has.

Change the schema filename here when a new schema pair is published; it is one of the places
that goes stale otherwise.

## `check-tutorial.py`

    mvn package -pl app -am -DskipTests
    python3 tools/check-tutorial.py . app/target/xldr-<version>/bin/xldr

The other half, which needs the real adapters. It builds each page's tables in a scratch H2 -
a database per page, because pages redefine the same table for their own lesson - pairs each
spec with the most recent sample the tutorial showed, and runs `xldr check` over it.

**Read the parsed values, not the exit code.** The reason to run this is the `id=1 (Long)`
lines: a date read under the wrong pattern is still a date and a load that inserts it succeeds.
That is the failure nothing else here can catch, and the only remedy is a person looking at
what the value became. Note also that those lines show field selectors only - a constant, a
`var`, an `expr` or a lookup's result is the load rather than a reading of the file, so a clean
run says nothing about the mapping half of a spec.

Both were written for one sweep and kept because that sweep found a real gap - `xldr check` was
not looking inside a `lookup` at all. If they ever disagree with the tutorial and the tutorial
is right, fix them; if nobody has run them in a year, delete them rather than trusting them.
