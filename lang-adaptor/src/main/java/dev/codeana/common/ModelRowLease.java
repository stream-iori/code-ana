package dev.codeana.common;

import java.util.List;

/** A borrowed row and its alias-preserving backing state, charged by encoded bytes. */
public final class ModelRowLease {
    public final Object row;
    public final long bytes;
    // Keep mutable archive state alive without copying or changing its ownership.
    private final Object owner;
    public ModelRowLease(Object row, Object owner, long bytes) {
        this.row=row;this.owner=owner;this.bytes=bytes;
    }
    public static ModelRowLease borrow(List<Object> rows,int index) {
        if(rows instanceof ArchiveRows)return ((ArchiveRows)rows).borrow(index);
        if(rows instanceof ArchiveRows.Snapshot)return ((ArchiveRows.Snapshot)rows).borrow(index);
        if(rows instanceof UnitDeclarationRows)return ((UnitDeclarationRows)rows).borrow(index);
        if(rows instanceof UnitDeclarationRows.Snapshot)return ((UnitDeclarationRows.Snapshot)rows).borrow(index);
        if(rows instanceof ModelArchive.RowSpool)return ((ModelArchive.RowSpool)rows).borrow(index);
        Object row=rows.get(index);
        // Ordinary resident lists need no additional decoded-row cache.
        return new ModelRowLease(row,row,Long.MAX_VALUE);
    }
}
