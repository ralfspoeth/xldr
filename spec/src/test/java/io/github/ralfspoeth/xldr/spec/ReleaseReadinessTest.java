package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The two files a release has to say its own version in, checked at the one
 * moment they can still be fixed.
 * <p>
 * {@code release:prepare} rewrites {@code <revision>} in the root pom from
 * {@code 0.42-SNAPSHOT} to {@code 0.42}, runs {@code clean verify} against that,
 * and only then commits and tags. So a test that reads {@code <revision>} sees
 * the release version exactly when the gate matters, and a snapshot every other
 * time - which is why everything here is skipped during ordinary development,
 * when {@code ## Unreleased} is the correct heading.
 * <p>
 * It exists because this is the failure this project actually keeps having.
 * 0.28's work went out under {@code ## 0.27}; 0.33 was tagged with its section
 * still headed {@code ## Unreleased} and the README's BOM still on 0.32; 0.41
 * repeated both. The release plugin touches neither file and nothing else
 * looked, so each time the artifact was right and the two documents a consumer
 * reads first were a release behind. Three times is a process, not an accident.
 * <p>
 * A consequence worth knowing: checking out {@code xldr-0.41} and building it
 * fails here, because that release really was tagged with the wrong heading.
 * The test is reporting the truth about that tag rather than misfiring, but it
 * is a thing to expect during a bisect.
 * <p>
 * Here, beside {@code TutorialTest} in {@code spec.io}, for the same reason that
 * one is there: it guards a document rather than this module, and {@code spec}
 * is where the repository's document guards live. Named rather than linked
 * because both are package-private, so a {@code @link} across the two packages
 * is a reference javadoc cannot resolve.
 */
class ReleaseReadinessTest {

    private static final Path ROOT = Path.of("..");

    private static final Pattern REVISION = Pattern.compile("<revision>([^<]+)</revision>");

    /** the BOM coordinate in the README's "using the toolkit as a library" snippet */
    private static final Pattern BOM_VERSION = Pattern.compile(
            "<artifactId>bom</artifactId>\\s*<version>([^<]+)</version>");

    /** the newest section heading, which is the release being described */
    private static final Pattern FIRST_HEADING = Pattern.compile("^## (.+)$", Pattern.MULTILINE);

    /**
     * The version this build is producing, or a skipped test if it is producing
     * a snapshot. Read from the pom rather than passed in as a property,
     * because {@code <revision>} is the thing the release plugin edits and so
     * the only value that cannot disagree with the release.
     */
    private static String releaseVersion() throws IOException {
        var pom = Files.readString(ROOT.resolve("pom.xml"));
        var revision = REVISION.matcher(pom);
        assertTrue(revision.find(), "no <revision> in the root pom");
        var version = revision.group(1);
        assumeFalse(version.endsWith("-SNAPSHOT"),
                "a snapshot build: '## Unreleased' and a lagging BOM version are both correct here");
        return version;
    }

    /**
     * The changelog's newest section is this release, not {@code ## Unreleased}
     * and not the release before it.
     */
    @Test
    void theChangelogNamesThisRelease() throws IOException {
        var version = releaseVersion();
        var changelog = Files.readString(ROOT.resolve("CHANGELOG.md"));
        var heading = FIRST_HEADING.matcher(changelog);
        assertTrue(heading.find(), "no '## ' section in CHANGELOG.md");
        assertEquals(version, heading.group(1).strip(),
                "the newest changelog section has to be this release. Rename it before the tag: "
                        + "release:prepare does not touch this file, and once tagged it cannot be fixed");
    }

    /** and that section says something */
    @Test
    void theChangelogSectionIsNotEmpty() throws IOException {
        var version = releaseVersion();
        var changelog = Files.readString(ROOT.resolve("CHANGELOG.md"));
        var start = changelog.indexOf("## " + version);
        // the sibling test is the one that reports a missing section; this one
        // would otherwise fail on a negative index and say nothing useful
        assertTrue(start >= 0, "no '## " + version + "' section in CHANGELOG.md");
        var section = changelog.substring(start);
        var next = section.indexOf("\n## ");
        assertTrue(
                (next < 0 ? section : section.substring(0, next)).contains("- "),
                "the section for " + version + " has a heading and no entries");
    }

    /**
     * The README tells a consumer which BOM to import, and it is the first thing
     * anyone copies. A release that ships the previous version there sends every
     * new reader to the release before it.
     */
    @Test
    void theReadmeBomIsThisRelease() throws IOException {
        var version = releaseVersion();
        var readme = Files.readString(ROOT.resolve("README.md"));
        var bom = BOM_VERSION.matcher(readme);
        assertTrue(bom.find(), "no bom <version> in README.md - has the snippet changed shape?");
        assertEquals(version, bom.group(1).strip(),
                "the README's BOM version has to be this release; release:prepare does not touch it either");
    }
}
