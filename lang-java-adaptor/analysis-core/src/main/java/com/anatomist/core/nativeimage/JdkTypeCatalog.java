package com.anatomist.core.nativeimage;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory catalog of JDK type signatures, with a pooled binary on-disk format. Strings (FQNs,
 * method names, descriptors) are deduplicated to keep the per-JDK-version file under ~10 MB.
 *
 * <p>Wire format:
 *
 * <pre>
 *  u32  magic        "ATCT" (0x41544354)
 *  u16  version      1
 *  u16  jdk_release  e.g. 21
 *  u32  string_count
 *  ──── repeated string_count times: ────
 *    u16 length
 *    bytes utf8
 *  u32  type_count
 *  ──── repeated type_count times: ────
 *    u32 fqn_idx                → StringPool
 *    u32 super_idx              → StringPool (0xFFFFFFFF if null)
 *    u8  interface_count
 *    u32[] interface_idx        → StringPool
 *    u16 flags                  (JdkType.FLAG_*)
 *    u16 field_count
 *    repeated field_count: { u32 name_idx; u32 desc_idx; u16 flags }
 *    u16 method_count
 *    repeated method_count: { u32 name_idx; u32 desc_idx; u16 flags }
 *  </pre>
 */
public class JdkTypeCatalog {

    private static final int MAGIC = 0x41544354; // "ATCT"
    private static final int VERSION = 2;
    private static final int NULL_IDX = 0xFFFFFFFF;

    private final int jdkRelease;
    private final Map<String, JdkType> types = new LinkedHashMap<>();

    public JdkTypeCatalog(int jdkRelease) {
        this.jdkRelease = jdkRelease;
    }

    public int jdkRelease() {
        return jdkRelease;
    }

    public int size() {
        return types.size();
    }

    public Collection<JdkType> all() {
        return types.values();
    }

    public void add(JdkType t) {
        types.put(t.fqn, t);
    }

    public JdkType find(String fqn) {
        return types.get(fqn);
    }

    // ───────────────────────── write ─────────────────────────────────

    public void writeTo(OutputStream out) {
        try {
            // Build deterministic string pool: insertion order = first occurrence in a
            // deterministic walk over types.
            StringPool pool = new StringPool();
            for (JdkType t : types.values()) {
                pool.intern(t.fqn);
                if (t.superFqn != null) pool.intern(t.superFqn);
                for (String i : t.interfaceFqns) pool.intern(i);
                if (t.signature != null) pool.intern(t.signature);
                for (JdkType.FieldEntry f : t.fields) {
                    pool.intern(f.name);
                    pool.intern(f.descriptor);
                    if (f.signature != null) pool.intern(f.signature);
                }
                for (JdkType.MethodEntry m : t.methods) {
                    pool.intern(m.name);
                    pool.intern(m.descriptor);
                    if (m.signature != null) pool.intern(m.signature);
                }
            }

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(body);

            dout.writeInt(MAGIC);
            dout.writeShort(VERSION);
            dout.writeShort(jdkRelease);

            dout.writeInt(pool.size());
            for (String s : pool.ordered()) {
                byte[] bs = s.getBytes(StandardCharsets.UTF_8);
                if (bs.length > 0xFFFF) {
                    throw new IllegalStateException("string too long for u16 length: " + s);
                }
                dout.writeShort(bs.length);
                dout.write(bs);
            }

            dout.writeInt(types.size());
            for (JdkType t : types.values()) {
                dout.writeInt(pool.indexOf(t.fqn));
                dout.writeInt(t.superFqn == null ? NULL_IDX : pool.indexOf(t.superFqn));
                if (t.interfaceFqns.size() > 0xFF) {
                    throw new IllegalStateException("too many interfaces: " + t.fqn);
                }
                dout.writeByte(t.interfaceFqns.size());
                for (String i : t.interfaceFqns) dout.writeInt(pool.indexOf(i));
                dout.writeShort(t.flags);
                dout.writeInt(t.signature == null ? NULL_IDX : pool.indexOf(t.signature));
                if (t.fields.size() > 0xFFFF || t.methods.size() > 0xFFFF) {
                    throw new IllegalStateException("too many members: " + t.fqn);
                }
                dout.writeShort(t.fields.size());
                for (JdkType.FieldEntry f : t.fields) {
                    dout.writeInt(pool.indexOf(f.name));
                    dout.writeInt(pool.indexOf(f.descriptor));
                    dout.writeShort(f.flags);
                    dout.writeInt(f.signature == null ? NULL_IDX : pool.indexOf(f.signature));
                }
                dout.writeShort(t.methods.size());
                for (JdkType.MethodEntry m : t.methods) {
                    dout.writeInt(pool.indexOf(m.name));
                    dout.writeInt(pool.indexOf(m.descriptor));
                    dout.writeShort(m.flags);
                    dout.writeInt(m.signature == null ? NULL_IDX : pool.indexOf(m.signature));
                }
            }
            dout.flush();
            out.write(body.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ───────────────────────── read ──────────────────────────────────

    public static JdkTypeCatalog readFrom(InputStream in) {
        try {
            DataInputStream din = new DataInputStream(in);
            int magic = din.readInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException(
                        String.format("bad magic 0x%08x; expected 0x%08x (ATCT)", magic, MAGIC));
            }
            int version = din.readUnsignedShort();
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported version " + version);
            }
            int jdkRelease = din.readUnsignedShort();

            int stringCount = din.readInt();
            String[] pool = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                int len = din.readUnsignedShort();
                byte[] bs = new byte[len];
                din.readFully(bs);
                pool[i] = new String(bs, StandardCharsets.UTF_8);
            }

            JdkTypeCatalog cat = new JdkTypeCatalog(jdkRelease);
            int typeCount = din.readInt();
            for (int i = 0; i < typeCount; i++) {
                String fqn = pool[din.readInt()];
                int superIdx = din.readInt();
                String superFqn = superIdx == NULL_IDX ? null : pool[superIdx];
                int ic = din.readUnsignedByte();
                List<String> ifaces = new ArrayList<>(ic);
                for (int j = 0; j < ic; j++) ifaces.add(pool[din.readInt()]);
                int flags = din.readUnsignedShort();
                int classSigIdx = din.readInt();
                String signature = classSigIdx == NULL_IDX ? null : pool[classSigIdx];
                int fc = din.readUnsignedShort();
                List<JdkType.FieldEntry> fields = new ArrayList<>(fc);
                for (int j = 0; j < fc; j++) {
                    String nm = pool[din.readInt()];
                    String desc = pool[din.readInt()];
                    int fl = din.readUnsignedShort();
                    int fSigIdx = din.readInt();
                    String fSig = fSigIdx == NULL_IDX ? null : pool[fSigIdx];
                    fields.add(new JdkType.FieldEntry(nm, desc, fl, fSig));
                }
                int mc = din.readUnsignedShort();
                List<JdkType.MethodEntry> methods = new ArrayList<>(mc);
                for (int j = 0; j < mc; j++) {
                    String nm = pool[din.readInt()];
                    String desc = pool[din.readInt()];
                    int fl = din.readUnsignedShort();
                    int mSigIdx = din.readInt();
                    String mSig = mSigIdx == NULL_IDX ? null : pool[mSigIdx];
                    methods.add(new JdkType.MethodEntry(nm, desc, fl, mSig));
                }
                cat.add(new JdkType(fqn, superFqn, ifaces, flags, signature, fields, methods));
            }
            return cat;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Insertion-ordered string interning, indexed by first-seen position. */
    private static final class StringPool {
        private final Map<String, Integer> idx = new HashMap<>();
        private final List<String> order = new ArrayList<>();

        int intern(String s) {
            Integer existing = idx.get(s);
            if (existing != null) return existing;
            int p = order.size();
            order.add(s);
            idx.put(s, p);
            return p;
        }

        int indexOf(String s) {
            Integer i = idx.get(s);
            if (i == null) throw new IllegalStateException("not interned: " + s);
            return i;
        }

        int size() {
            return order.size();
        }

        List<String> ordered() {
            return order;
        }
    }
}
