package com.anatomist.core.nativeimage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end: build catalog from JDK → serialise to disk → mmap back → identical type lookup. This
 * is the loop a release-time CI job will run.
 */
class JdkTypeCatalogE2EIT {

    @Test
    void buildSerialiseReload_endToEnd(@TempDir Path tmp) throws Exception {
        JdkTypeCatalog built = new JdkTypeCatalogBuilder().buildFromCurrentJdk();
        Path bin = tmp.resolve("jdk" + built.jdkRelease() + "-types.bin");
        try (OutputStream out = Files.newOutputStream(bin)) {
            built.writeTo(out);
        }
        long size = Files.size(bin);
        // jdk21+ catalog (~10-20k types after stripping internals) lands around
        // 5-15 MB. 20 MB is a sanity bound — beyond that, a module include
        // regression has slipped in (see JdkTypeCatalogBuilder#includeModule).
        assertTrue(size > 100_000, "catalog suspiciously small: " + size);
        assertTrue(size < 20_000_000, "catalog suspiciously large: " + size);

        JdkTypeCatalog reloaded;
        try (var in = Files.newInputStream(bin)) {
            reloaded = JdkTypeCatalog.readFrom(in);
        }
        assertEquals(built.size(), reloaded.size());
        assertEquals(built.jdkRelease(), reloaded.jdkRelease());

        // Spot check round-trip equality on a known type
        JdkType origStr = built.find("java.lang.String");
        JdkType backStr = reloaded.find("java.lang.String");
        assertNotNull(backStr);
        assertEquals(origStr.fqn, backStr.fqn);
        assertEquals(origStr.superFqn, backStr.superFqn);
        assertEquals(origStr.interfaceFqns, backStr.interfaceFqns);
        assertEquals(origStr.methods.size(), backStr.methods.size());
        assertEquals(origStr.fields.size(), backStr.fields.size());
    }
}
