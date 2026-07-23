package com.pd.xldr.app;

import com.pd.xldr.spec.MappingSpec;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;

/**
 * An active feed: a directory below one of the configured roots that holds a
 * mapping spec, together with the four working directories.
 *
 * <pre>
 * &lt;root&gt;/&lt;feed&gt;/
 *     spec.json           promotes the directory to a feed
 *     adapter.properties  optional, format settings such as fieldSeparator
 *     in/                 producers move input files in here
 *     work/               claimed, currently being loaded
 *     archive/            loaded successfully, date partitioned
 *     hospital/           failed, together with an error log
 * </pre>
 *
 * @param specModified stamp of the spec file when it was parsed; lets the
 *                     registry re-read it only when it actually changed
 */
public record Feed(
        Path directory,
        Path specFile,
        FileTime specModified,
        MappingSpec mappingSpec,
        Properties adapterProperties,
        Sentinel sentinel
) {

    /**
     * Created below a feed directory when the feed becomes active.
     */
    public static final List<String> SUBDIRECTORIES = List.of("in", "work", "archive", "hospital");

    /**
     * Optional, holds input adapter settings - the mapping spec has nowhere to
     * put a CSV dialect yet.
     */
    public static final String ADAPTER_PROPERTIES = "adapter.properties";

    public String name() {
        return directory.getFileName().toString();
    }

    public Path in() {
        return directory.resolve("in");
    }

    public Path work() {
        return directory.resolve("work");
    }

    public Path archive() {
        return directory.resolve("archive");
    }

    public Path hospital() {
        return directory.resolve("hospital");
    }
}
