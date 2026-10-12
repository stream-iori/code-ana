package com.anatomist.store;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class IndexStateStoreTest {

    @Test
    void interruptedRebuildBecomesStaleAndCleansTemporaryFiles(@TempDir Path tmp) throws Exception {
        Path live = tmp.resolve("index.db");
        Path replacement = tmp.resolve("index.db.rebuild-test.db");
        java.nio.file.Files.writeString(replacement, "partial");
        IndexStateStore.write(
                live, IndexStateStore.State.REBUILDING, "schema mismatch", 7, replacement);

        IndexStateStore.recoverInterrupted(live);

        IndexStateStore.Snapshot state = IndexStateStore.read(live);
        assertEquals(IndexStateStore.State.STALE, state.state());
        assertEquals(7, state.dirtyGeneration());
        assertFalse(java.nio.file.Files.exists(replacement));
    }
}
