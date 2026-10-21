# Releasing xldr

Written for whoever does this next, which may not be whoever did it last.

A release here has two destinations and one trigger. The library modules go to Maven Central
under `io.github.ralfspoeth.xldr`; the server distribution goes to a GitHub release as a
`.tar.gz` and a `.zip`. Both follow from one pushed tag, `xldr-<version>`.

**The tag is irreversible.** `.github/workflows/release.yml` fires on it and publishes the
download immediately, and Maven Central never un-publishes anything. An asset can be removed
with `gh release delete-asset xldr-<version> <file>`, but a tag should not be re-cut. So the
checking happens before the tag, which is what most of this document is about.

## Which number

Three components since 1.0.0, and the rule 1.0.0's own changelog set: **the third component is
for a release that fixes something without adding anything.** If the changelog section has an
`### Added` heading, it is a minor.

`release:prepare` leaves `<revision>` at a *patch* bump for the next round - `1.3.2-SNAPSHOT` -
and that is a guess rather than a decision. It has been wrong more often than right. Before
agreeing to a version, settle it with evidence rather than recollection:

    git diff --name-status xldr-<previous>..HEAD | grep src/main

New public members mean a minor. So does an `### Added` section. Dependency bumps do not, even
when the dependency itself went up by a minor - the version describes what this artifact does,
not what its dependencies did.

Within `1.x` nothing a spec relies on may change incompatibly. Anything that would is a `2.0`
and goes under `### Breaking`.

## What to update before the tag

Five places, of which the gate checks two.

**`CHANGELOG.md`** - a new section headed with the version. Work out what belongs in it from
the tag rather than from the working tree, because work often continues after a release without
anybody saying so, which has twice left a section mislabelled:

    diff <(git show xldr-<previous>:CHANGELOG.md) CHANGELOG.md

**`README.md`** has *two* independent things that age, and they are nowhere near each other. The
BOM `<version>` in the library snippet is one. The prose list of published schemas is the other,
and because the gate only looks at the first, the second has gone stale for three releases
running before now.

**`docs/index.html`** is the GitHub Pages schema page. It lives nowhere near the code and goes
stale silently - it was once four releases behind. Check the schema table and the `$schema` and
`noNamespaceSchemaLocation` values in both snippets.

**`docs/schema/mapping-spec-<major.minor>.{json,xsd}`** - a new pair **only when the format
gained an optional member**, which post-1.0 is rare. Most releases publish no schema and
`docs/schema/` going untouched is the normal case. The pair is named for the version of the
*format*, not of the release, so `mapping-spec-1.0` covers every `1.x` that adds nothing.

> **Never edit a published schema in place.** It is served from Pages and is what a released
> server enforces, so changing it gives one file name two meanings: an editor would pass a spec
> that the user's own server then refuses. If you find the working tree has edited one, restore
> it with `git checkout xldr-<version> -- docs/schema/...` and publish the change as the next
> pair.

**`docs/modules.svg`** goes stale only when a module is added or removed or a `requires` edge
changes. Rare, and therefore exactly what nobody remembers to check.

## The gate

`ReleaseReadinessTest` in `spec` reads `<revision>` from the root pom. While it is a snapshot it
skips; otherwise it insists the changelog's newest heading is this version, that the section has
entries, and that the README's BOM version matches. It runs during `release:prepare`'s
`clean verify`, before the tag. A fourth test always runs, asserting the gate can still find and
parse what the other three read - three skips being indistinguishable from three broken tests.

Prove it without releasing:

    mvn -Drevision=<version> test -pl spec -Dtest=ReleaseReadinessTest

**What it does not check:** `docs/index.html`, the schema pair, and whether the changelog section
covers every commit since the tag. It asks only that a section exists and is not empty. The diff
against the previous tag stays a manual step, and skipping it has already let a commit through
with no entry.

## Cutting it

The index must be clean, not merely the diff. `release:prepare` compares the index to HEAD, so a
file staged and then reverted in the working tree shows as unchanged under `git diff HEAD` and
still blocks the release. `git status --short` shows it as `MM`; clear it with
`git restore --staged <file>`.

Then, with `<revision>` already at `<version>-SNAPSHOT`:

    mvn release:prepare
    mvn release:perform

`release:prepare` runs `clean verify` against the source it is about to tag - that is the real
gate - then sets `<revision>` to the release version, commits, tags, and bumps to the next
snapshot. `release:perform` rebuilds that tag and deploys. It runs `deploy -DskipITs`, configured
in the root pom, because the integration tests already passed against this exact source during
prepare and running them twice buys only minutes.

Pushing the tag triggers `release.yml`, which rebuilds the distribution with `-DskipTests`
(the testing already happened), renames `app-<version>-dist` to `xldr-<version>-dist` so the
download matches the directory it unpacks to, and writes both archive formats from one unpacked
tree so the two cannot differ.

That workflow also enforces the driver rule: `drivers/` must contain an `h2-*.jar` and no other
jar. H2 ships because the tutorial runs on it; nothing else ships because the bar is "the
documentation cannot work without it", not "somebody might want it". Both a missing H2 and a
stray addition fail the release.

## Credentials, and what a fork cannot inherit

Everything above works for anyone with the repository. Publishing does not.

- **Maven Central** needs a token for the `central` server id (see `publishingServerId` in
  `plumbum`) in your `settings.xml`, against the `io.github.ralfspoeth` namespace. That namespace
  is verified through the GitHub account of the same name, so **it cannot simply be handed over
  with the repository.**
- **A GPG key** whose public half is on a keyserver. Signing is bound to `verify` inside the
  `release-sign-artifacts` profile, which `release:perform` activates by name, so that an
  ordinary `mvn verify` does not stop and ask for a passphrase.

A fork published under a different namespace therefore needs its own token, its own key, and a
groupId change throughout the poms and the two README snippets. Nothing else here changes: the
version rule, the gate, the stale-file list and the workflow all transfer as they stand. See the
*Forking and stewardship* section of the README for why that is anticipated rather than regretted.

Everything in this file applies to the parent project `plumbum` and the satellite adapters
(`swift-mt`, `bbgia`) only in part: they are released the same way but publish no Maven Central
artifacts, carrying `skipPublishing` instead and producing a GitHub release alone.
