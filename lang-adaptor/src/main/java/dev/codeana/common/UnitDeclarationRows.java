package dev.codeana.common;

import java.util.*;
import java.util.function.Predicate;

/** Mutable composite of original rows and sealed unit overlays. No decoded rows are retained. */
public final class UnitDeclarationRows extends AbstractList<Object> implements ModelRows {
    private final List<Object> original,overlay;
    private final List<Object> appended=new ArrayList<Object>();
    private int[] selected;
    private int count;
    public UnitDeclarationRows(List<Object> original,List<Object> overlay,int[] selected) {
        this.original=original;this.overlay=overlay;this.selected=selected.clone();count=selected.length;
    }
    public int size() { return count; }
    public Object get(int index) {
        if(index<0||index>=count)throw new IndexOutOfBoundsException();
        int position=selected[index];
        return position>=0?original.get(position):position>=-overlay.size()?overlay.get(-position-1)
                :appended.get(-position-overlay.size()-1);
    }
    public void add(int index,Object row) {
        if(index<0||index>count)throw new IndexOutOfBoundsException();
        if(count==selected.length)selected=Arrays.copyOf(selected,Math.max(8,count*2));
        System.arraycopy(selected,index,selected,index+1,count-index);
        selected[index]=-overlay.size()-appended.size()-1;appended.add(row);count++;modCount++;
    }
    public Object set(int index,Object row) {
        Object old=get(index);selected[index]=-overlay.size()-appended.size()-1;appended.add(row);return old;
    }
    public Object remove(int index) {
        Object old=get(index);System.arraycopy(selected,index+1,selected,index,count-index-1);count--;modCount++;return old;
    }
    public boolean removeIf(Predicate<? super Object> predicate) {
        int retained=0;
        for(int i=0;i<count;i++)if(!predicate.test(get(i)))selected[retained++]=selected[i];
        if(retained==count)return false;count=retained;modCount++;return true;
    }
    private static List<Object> freeze(List<Object> rows) {
        if(rows instanceof ModelRows)return ((ModelRows)rows).snapshot();
        if(rows instanceof ArchiveRows)return ((ArchiveRows)rows).snapshot();
        List<Object> frozen=new ArrayList<Object>(rows.size());
        for(Object row:rows)frozen.add(ModelArchive.immutable(row,0));
        return Collections.unmodifiableList(frozen);
    }
    public List<Object> snapshot() {
        List<Object> base=freeze(original),edits=freeze(overlay),extra=freeze(appended);
        final int[] positions=Arrays.copyOf(selected,count);
        return new Snapshot(base,edits,extra,positions);
    }
    public static final class Snapshot extends AbstractList<Object> implements ModelRows {
        private final List<Object> original,overlay,appended;
        private final int[] selected;
        private Snapshot(List<Object> original,List<Object> overlay,List<Object> appended,int[] selected) {
            this.original=original;this.overlay=overlay;this.appended=appended;this.selected=selected;
        }
        public int size(){return selected.length;}
        public Object get(int index) {
            int position=selected[index];
            return position>=0?original.get(position):position>=-overlay.size()?overlay.get(-position-1)
                :appended.get(-position-overlay.size()-1);
        }
        public List<Object> snapshot(){return this;}
    }
}
