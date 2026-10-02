package dev.codeana.analysis;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** One coordination domain for the two derived signature stores. */
final class SignatureStores {
    static final ReentrantLock LOCK = new ReentrantLock();

    static List<Path> roots(Path files) {
        return List.of(files, files.resolveSibling(files.getFileName() + "-modules"));
    }

    static Path lockPath(Path files) {
        return files.resolveSibling(files.getFileName() + ".lock");
    }

    static void checkRoots(Path files) throws java.io.IOException {
        for (Path root : roots(files))
            if (Files.isSymbolicLink(root))
                throw new java.io.IOException("SIGNATURE_STORE_SYMLINK");
        if (Files.isSymbolicLink(lockPath(files)))
            throw new java.io.IOException("SIGNATURE_STORE_LOCK_SYMLINK");
    }
}
