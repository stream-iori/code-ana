package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Task-owned immutable archive inventories. Never retains descriptors or parsed class maps. */
final class BinaryCatalogue implements AutoCloseable {
    private static final ThreadLocal<BinaryCatalogue> CURRENT = new ThreadLocal<>();
    private static final long MAX_BYTES = 32L << 20;
    private static final int MAX_ENTRIES = 4096, MAX_PACKAGES = 262144;
    private final long limit;
    private final LinkedHashMap<Path, Entry> entries = new LinkedHashMap<>(64, .75f, true);
    private long bytes, scans, hits, hashes, evictions;
    private boolean closed;

    private static final class Entry {
        final BasicFileAttributes identity;
        final List<String> packages;
        final long weight;
        String hash;
        Entry(BasicFileAttributes identity, List<String> packages, long weight) {
            this.identity = identity; this.packages = packages; this.weight = weight;
        }
    }

    BinaryCatalogue() { this(MAX_BYTES); }
    BinaryCatalogue(long limit) {
        if (limit < 0 || limit > MAX_BYTES) throw new IllegalArgumentException("BINARY_CATALOGUE_BUDGET");
        this.limit = limit;
    }

    static BinaryCatalogue current() { return CURRENT.get(); }
    Scope bind() { return new Scope(this); }
    static final class Scope implements AutoCloseable {
        private final BinaryCatalogue value, previous;
        private boolean closed;
        Scope(BinaryCatalogue value) { this.value=value; previous=CURRENT.get(); CURRENT.set(value); }
        public void close() {
            if (closed) return;
            if (CURRENT.get()!=value) throw new IllegalStateException("BINARY_CATALOGUE_SCOPE");
            closed=true;
            if (previous==null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    private static void check() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
    }
    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return a.size()==b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && a.creationTime().equals(b.creationTime()) && Objects.equals(a.fileKey(),b.fileKey());
    }
    private Entry existing(Path path, BasicFileAttributes identity) {
        Entry entry=entries.get(path);
        if (entry!=null && !same(entry.identity,identity)) {
            entries.remove(path); bytes-=entry.weight; entry=null;
        }
        return entry;
    }
    private BasicFileAttributes identity(Path path) throws IOException {
        check();
        if (closed) throw new IOException("BINARY_CATALOGUE_CLOSED");
        BasicFileAttributes identity=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!identity.isRegularFile()) throw new IOException("BINARY_INPUT_NOT_REGULAR");
        return identity;
    }

    /** null means over budget: caller retains the original exact sequential lookup. */
    synchronized List<String> packages(Path path) throws IOException {
        BasicFileAttributes identity=identity(path);
        Entry cached=existing(path,identity);
        if(cached!=null) { hits++; return cached.packages; }
        scans++;
        long weight=256L+2L*path.toString().length();
        Set<String> packages=new LinkedHashSet<>();
        boolean complete=true;
        try(FrozenArchive archive=new FrozenArchive(path)) {
            var rows=archive.entries();
            int visited=0;
            while(rows.hasMoreElements()) {
                if((visited++ & 255)==0) check();
                String name=rows.nextElement().getName();
                if(!complete || !name.endsWith(".class")) continue;
                int slash=name.lastIndexOf('/');
                String owner=slash<0?"":name.substring(0,slash);
                if(packages.contains(owner)) continue;
                long next=64L+2L*owner.length();
                if(packages.size()>=MAX_PACKAGES || next>limit-weight) {
                    complete=false; packages.clear(); continue;
                }
                packages.add(owner); weight+=next;
            }
        }
        if(!same(identity,identity(path))) throw new IOException("BINARY_INPUT_CHANGED");
        if(!complete || weight>limit) return null;
        while(!entries.isEmpty() && (bytes>limit-weight || entries.size()>=MAX_ENTRIES)) {
            var oldest=entries.entrySet().iterator();bytes-=oldest.next().getValue().weight;
            oldest.remove();evictions++;
        }
        List<String> result=List.copyOf(packages);
        entries.put(path,new Entry(identity,result,weight));bytes+=weight;
        return result;
    }

    synchronized String hash(Path path) throws IOException {
        BasicFileAttributes identity=identity(path);
        Entry cached=existing(path,identity);
        if(cached!=null && cached.hash!=null) return cached.hash;
        String hash=Json.sha(path); hashes++;
        if(!same(identity,identity(path))) throw new IOException("BINARY_INPUT_CHANGED");
        if(cached!=null) cached.hash=hash;
        return hash;
    }
    synchronized Map<String,Object> metrics() {
        return Json.object("stage","binary_catalogue","scans",scans,"hits",hits,"hashes",hashes,
                "evictions",evictions,"bytes",bytes,"entries",entries.size());
    }
    public synchronized void close() { closed=true;entries.clear();bytes=0; }
}
