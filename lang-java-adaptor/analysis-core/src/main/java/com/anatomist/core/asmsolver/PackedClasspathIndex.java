package com.anatomist.core.asmsolver;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;

/** Compact sorted UTF-8 FQN to classpath-origin index. */
final class PackedClasspathIndex {

    private static final int MAGIC = 0x41544350; // ATCP
    private static final int VERSION = 1;

    private final byte[] names;
    private final int[] offsets;
    private final int[] originIds;

    private PackedClasspathIndex(byte[] names, int[] offsets, int[] originIds) {
        this.names = names;
        this.offsets = offsets;
        this.originIds = originIds;
    }

    static PackedClasspathIndex empty() {
        return new PackedClasspathIndex(new byte[0], new int[] {0}, new int[0]);
    }

    static PackedClasspathIndex from(Map<String, Integer> entries) {
        if (entries == null || entries.isEmpty()) return empty();
        ArrayList<Map.Entry<String, Integer>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        int total = 0;
        ArrayList<byte[]> encoded = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Integer> entry : sorted) {
            byte[] bytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            encoded.add(bytes);
            total = Math.addExact(total, bytes.length);
        }
        byte[] arena = new byte[total];
        int[] offsets = new int[sorted.size() + 1];
        int[] origins = new int[sorted.size()];
        int position = 0;
        for (int i = 0; i < sorted.size(); i++) {
            byte[] bytes = encoded.get(i);
            offsets[i] = position;
            System.arraycopy(bytes, 0, arena, position, bytes.length);
            position += bytes.length;
            origins[i] = sorted.get(i).getValue();
        }
        offsets[sorted.size()] = position;
        return new PackedClasspathIndex(arena, offsets, origins);
    }

    int findOrigin(String fqn) {
        if (fqn == null || originIds.length == 0) return -1;
        byte[] query = fqn.getBytes(StandardCharsets.UTF_8);
        int low = 0;
        int high = originIds.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = compare(query, offsets[middle], offsets[middle + 1]);
            if (comparison == 0) return originIds[middle];
            if (comparison < 0) high = middle - 1;
            else low = middle + 1;
        }
        return -1;
    }

    int size() {
        return originIds.length;
    }

    int packedBytes() {
        return names.length + offsets.length * Integer.BYTES + originIds.length * Integer.BYTES;
    }

    Set<String> decodedNames() {
        Set<String> out = new HashSet<>(Math.max(16, originIds.length * 4 / 3));
        for (int i = 0; i < originIds.length; i++) {
            out.add(
                    new String(
                            names,
                            offsets[i],
                            offsets[i + 1] - offsets[i],
                            StandardCharsets.UTF_8));
        }
        return out;
    }

    void write(Path file, String fingerprint) throws IOException {
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(packedBytes() + 128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(fingerprint);
            out.writeInt(originIds.length);
            out.writeInt(names.length);
            out.write(names);
            for (int offset : offsets) out.writeInt(offset);
            for (int origin : originIds) out.writeInt(origin);
        }
        byte[] body = bytes.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        ByteBuffer complete = ByteBuffer.allocate(body.length + Integer.BYTES);
        complete.put(body).putInt((int) crc.getValue());
        Path temporary =
                Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, complete.array());
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static PackedClasspathIndex read(Path file, String fingerprint) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 24) throw new IOException("packed classpath index is truncated");
        int storedCrc =
                ByteBuffer.wrap(bytes, bytes.length - Integer.BYTES, Integer.BYTES).getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - Integer.BYTES);
        if ((int) crc.getValue() != storedCrc)
            throw new IOException("packed classpath index CRC mismatch");
        ByteBuffer in = ByteBuffer.wrap(bytes, 0, bytes.length - Integer.BYTES);
        if (in.getInt() != MAGIC || in.getInt() != VERSION) {
            throw new IOException("unsupported packed classpath index");
        }
        int fingerprintLength = Short.toUnsignedInt(in.getShort());
        if (fingerprintLength > in.remaining()) throw new IOException("invalid fingerprint length");
        byte[] fingerprintBytes = new byte[fingerprintLength];
        in.get(fingerprintBytes);
        if (!fingerprint.equals(new String(fingerprintBytes, StandardCharsets.UTF_8))) {
            throw new IOException("packed classpath index fingerprint mismatch");
        }
        int count = in.getInt();
        int namesLength = in.getInt();
        if (count < 0 || namesLength < 0 || namesLength > in.remaining()) {
            throw new IOException("invalid packed classpath index sizes");
        }
        byte[] names = new byte[namesLength];
        in.get(names);
        if (in.remaining() != (count + 1L + count) * Integer.BYTES) {
            throw new IOException("invalid packed classpath index body");
        }
        int[] offsets = new int[count + 1];
        int[] origins = new int[count];
        for (int i = 0; i < offsets.length; i++) offsets[i] = in.getInt();
        for (int i = 0; i < origins.length; i++) origins[i] = in.getInt();
        if (offsets[0] != 0 || offsets[count] != namesLength) {
            throw new IOException("invalid packed classpath offsets");
        }
        for (int i = 1; i < offsets.length; i++) {
            if (offsets[i] < offsets[i - 1] || offsets[i] > namesLength) {
                throw new IOException("invalid packed classpath offset order");
            }
        }
        return new PackedClasspathIndex(names, offsets, origins);
    }

    private int compare(byte[] query, int start, int end) {
        int length = end - start;
        int common = Math.min(query.length, length);
        for (int i = 0; i < common; i++) {
            int left = query[i] & 0xff;
            int right = names[start + i] & 0xff;
            if (left != right) return Integer.compare(left, right);
        }
        return Integer.compare(query.length, length);
    }
}
