package dev.codeana.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Reads sealed JARs with the reference's bounded local-entry path for old central directories. */
final class FrozenArchive implements AutoCloseable {
    private static final long MAX_INFLATED = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50000, MAX_NAMES = 8 * 1024 * 1024;
    private final Path path;
    private final ZipFile indexed;
    private final Set<Scan> scans = new HashSet<>();
    private List<ZipEntry> names;
    private boolean closed;

    FrozenArchive(Path path) throws IOException {
        this.path = path;
        ZipFile opened;
        try {
            opened = new ZipFile(path.toFile());
        } catch (ZipException invalidCentralDirectory) {
            try (InputStream input = Files.newInputStream(path)) {
                byte[] header = input.readNBytes(4);
                if (header.length != 4
                        || header[0] != 'P'
                        || header[1] != 'K'
                        || !(header[2] == 3 && header[3] == 4 || header[2] == 5 && header[3] == 6))
                    throw invalidCentralDirectory;
            }
            opened = null;
        }
        indexed = opened;
    }

    private void check() throws IOException {
        if (closed) throw new IOException("FROZEN_ARCHIVE_CLOSED");
        if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
    }

    Enumeration<? extends ZipEntry> entries() throws IOException {
        check();
        if (indexed != null) return indexed.entries();
        if (names == null) {
            List<ZipEntry> found = new ArrayList<>();
            try (Scan scan = scan()) {
                ZipEntry entry;
                while ((entry = scan.next()) != null) found.add(new ZipEntry(entry));
            }
            names = Collections.unmodifiableList(found);
        }
        return Collections.enumeration(names);
    }

    ZipEntry getEntry(String name) throws IOException {
        check();
        if (indexed != null) return indexed.getEntry(name);
        Enumeration<? extends ZipEntry> rows = entries();
        while (rows.hasMoreElements()) {
            ZipEntry entry = rows.nextElement();
            if (entry.getName().equals(name)) return entry;
        }
        return null;
    }

    InputStream getInputStream(ZipEntry wanted) throws IOException {
        check();
        if (indexed != null) return indexed.getInputStream(wanted);
        Scan scan = scan();
        boolean retained = false;
        try {
            ZipEntry entry;
            while ((entry = scan.next()) != null) {
                if (entry.getName().equals(wanted.getName())) {
                    retained = true;
                    return scan;
                }
            }
            throw new IOException("FROZEN_ARCHIVE_ENTRY_MISSING: " + wanted.getName());
        } finally {
            if (!retained) scan.close();
        }
    }

    private Scan scan() throws IOException {
        check();
        Scan scan = new Scan();
        scans.add(scan);
        return scan;
    }

    private final class Scan extends InputStream {
        private final ZipInputStream input = new ZipInputStream(Files.newInputStream(path));
        private final byte[] buffer = new byte[8192];
        private long inflated;
        private int visited, characters;
        private boolean current, ended;

        Scan() throws IOException {}

        ZipEntry next() throws IOException {
            check();
            if (current)
                while (read(buffer) != -1) {
                    /* charge all skipped data, including CRC validation */
                }
            ZipEntry entry = input.getNextEntry();
            current = entry != null;
            if (entry != null
                    && (++visited > MAX_ENTRIES
                            || entry.getName().length() > MAX_NAMES - characters))
                throw new IOException("ZIP_ENTRY_BUDGET");
            if (entry != null) characters += entry.getName().length();
            return entry;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            check();
            if (ended) throw new IOException("FROZEN_ARCHIVE_STREAM_CLOSED");
            int count = input.read(bytes, offset, length);
            if (count > 0) {
                if (count > MAX_INFLATED - inflated)
                    throw new IOException("ZIP_DECOMPRESSION_BUDGET");
                inflated += count;
            }
            return count;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }

        @Override
        public void close() throws IOException {
            if (!ended) {
                ended = true;
                scans.remove(this);
                input.close();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Scan scan : List.copyOf(scans))
            try {
                scan.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        if (indexed != null)
            try {
                indexed.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        if (failure != null) throw failure;
    }
}
