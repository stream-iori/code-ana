package com.anatomist.core.java;

import java.util.*;
import com.anatomist.core.AnalysisContext;

/** Dependency catalogue without bodies, bindings, local classes or unused expression types. */
public final class JavaDeclarationView {
    private final JavaTypes source;
    private final AnalysisContext analysis;
    private final Map<Integer,Integer> references;
    private final List<JavaType> types;

    private JavaDeclarationView(JavaTypes source,AnalysisContext analysis) {
        this.analysis=analysis;charge(1,1,1024);
        this.references=new HashMap<>();this.types=new ArrayList<>();
        this.source = source;
    }

    public static JavaUnit of(JavaUnit unit) {
        return of(unit,null);
    }
    public static JavaUnit of(JavaUnit unit,AnalysisContext analysis) {
        var view = new JavaDeclarationView(unit.types(),analysis);
        view.charge(unit.declarationInventory().size(),0,64L*unit.declarationInventory().size());
        var declarations =
                unit.declarationInventory().stream()
                        .filter(value -> !value.canonicalName().isEmpty())
                        .map(value -> view.declaration(value, 0))
                        .toList();
        Map<String, Object> header = new LinkedHashMap<>();
        for (String key :
                List.of("getPackageName", "getPackageAnnotations", "getPackage", "getModule")) {
            Object value = unit.tree().fields().get(key);
            if (value != null) header.put(key, value);
        }
        view.charge(view.types.size(),0,32L*view.types.size());
        return new JavaUnit(
                unit.path(),
                new JavaNode(0, "COMPILATION_UNIT", -1, -1, header),
                com.anatomist.core.java.JavaProcessingState.NONE,
                Map.of(),
                new JavaTypes(view.types),
                true,
                declarations,
                unit.sourceHash(),
                unit.extensions());
    }

    private JavaDeclaration declaration(JavaDeclaration value, int depth) {
        guard(depth+1,129);
        charge(1,1,1024L+64L*(value.members().size()+value.overrides().targets().size()));
        if (depth > 128)
            throw new IllegalArgumentException("declaration view exceeds depth budget");
        var symbol = value.symbol();
        return new JavaDeclaration(
                new JavaSymbol(
                        symbol.kind(),
                        symbol.owner(),
                        symbol.name(),
                        symbol.descriptor(),
                        type(symbol.declaredType(), 0),
                        symbol.modifiers(),
                        symbol.variadic()),
                value.canonicalName(),
                value.binaryName(),
                value.signature(),
                value.span(),
                value.origin(),
                type(value.superclass(), 0),
                refs(value.interfaces(), 0),
                refs(value.permitted(), 0),
                value.annotations(),
                value.constant(),
                value.annotationDefault(),
                value.members().stream().map(member -> declaration(member, depth + 1)).toList(),
                value.incompleteReasons(),
                new JavaOverrides(
                        value.overrides().targets().stream()
                                .map(
                                        target ->
                                                new JavaOverrides.Target(
                                                        new JavaSymbol(
                                                                target.symbol().kind(),
                                                                target.symbol().owner(),
                                                                target.symbol().name(),
                                                                target.symbol().descriptor(),
                                                                type(
                                                                        target.symbol()
                                                                                .declaredType(),
                                                                        0),
                                                                target.symbol().modifiers(),
                                                                target.symbol().variadic()),
                                                        target.binaryOwner()))
                                .toList(),
                        value.overrides().incompleteReasons()),
                value.deprecated());
    }

    private List<Integer> refs(List<Integer> values, int depth) {
        charge(values.size(),0,64L*values.size());
        return values.stream().map(value -> type(value, depth)).toList();
    }

    private int type(int reference, int depth) {
        guard(depth+1,129);charge(1,0,0);
        if (reference < 0) return -1;
        Integer known = references.get(reference);
        if (known != null) return known;
        if(analysis!=null && types.size()>=100_000)analysis.deny("nodes",Math.min(100_000,analysis.limits().maxNodes()),Math.min(types.size(),analysis.limits().maxNodes()),1);
        if (depth > 128 || types.size() >= 100_000)
            throw new IllegalArgumentException("declaration type view exceeds budget");
        charge(1,1,512);
        int index = types.size();
        references.put(reference, index);
        types.add(null); // Pin the index before traversing recursive generic bounds.
        var value = source.get(reference);
        types.set(
                index,
                new JavaType(
                        value.kind(),
                        value.name(),
                        value.display(),
                        type(value.enclosing(), depth + 1),
                        refs(value.arguments(), depth + 1),
                        refs(value.upperBounds(), depth + 1),
                        type(value.lowerBound(), depth + 1),
                        type(value.component(), depth + 1),
                        refs(value.parameters(), depth + 1),
                        type(value.returns(), depth + 1),
                        refs(value.exceptions(), depth + 1),
                        value.annotations(),
                        value.binaryName()));
        return index;
    }
    private void charge(long work,long nodes,long bytes) {if(analysis!=null)analysis.charge(work,nodes,bytes);}
    private void guard(int depth,int local) {if(analysis!=null)analysis.depth(depth,local);else if(depth>local)throw new IllegalArgumentException("declaration projection exceeds depth budget");}

}
