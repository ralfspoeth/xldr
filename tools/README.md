# tools

One script, for the half of the tutorial sweep that a test cannot do.

The other half became one. `TutorialTest` in the `spec` module reads
[the tutorial](../docs/tutorial/README.md) on every build: every spec on every page validates
against the published schema - with the same validator the rest of the build uses - is read by
the reader that will read it in anger, and is cross-checked against the record selectors and
`create table` statements of its own page. It also holds page 3 to its claim that it is page 2
written in the other format, by reading both and comparing.

That belongs in the build because documentation drifts silently: `docs/index.html` was four
releases stale before anyone noticed, and nothing was ever going to notice. What a reader
copies off a page should be held to the standard of a fixture.

## `check-tutorial.py`

    mvn package -pl app -am -DskipTests
    python3 tools/check-tutorial.py . app/target/xldr-<version>/bin/xldr

What is left needs the real adapters, a packaged distribution and a database per page, which is
too much to put in front of every build. It creates each page's tables in a scratch H2 - a
database per page, because pages redefine the same table for their own lesson - pairs each spec
with the most recent sample the tutorial showed, and runs `xldr check` over it.

**Read the parsed values, not the exit code.** The reason to run it is the `id=1 (Long)` lines:
a date read under the wrong pattern is still a date, and a load that inserts it succeeds. No
check catches that, and the only remedy is a person looking at what the value became. Note also
that those lines show field selectors only - a constant, a `var`, an `expr` or a lookup's result
is the load rather than a reading of the file, so a clean run says nothing about the mapping
half of a spec.

Worth running when a page changes or a release does. If nobody has run it in a year, delete it
rather than trusting it.
