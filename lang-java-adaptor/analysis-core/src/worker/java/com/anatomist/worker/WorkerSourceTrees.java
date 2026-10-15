package com.anatomist.worker;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.List;

import javax.lang.model.element.*;

/** Public compiler paths; Java 8 does not return a direct path for method parameters. */
final class WorkerSourceTrees {
    private WorkerSourceTrees() {}

    static TreePath path(Element element, Trees trees) {
        TreePath direct = trees.getPath(element);
        if (direct != null) return direct;
        Element owner = element.getEnclosingElement();
        if (owner == null) return null;
        if (element.getKind() != ElementKind.PARAMETER
                && element.getKind() != ElementKind.TYPE_PARAMETER) return null;
        TreePath parent = trees.getPath(owner);
        if (parent == null) return null;
        Tree syntax = parent.getLeaf();
        List<? extends Element> declarations;
        List<? extends Tree> nodes;
        if (element.getKind() == ElementKind.PARAMETER
                && owner instanceof ExecutableElement
                && syntax instanceof MethodTree) {
            declarations = ((ExecutableElement) owner).getParameters();
            nodes = ((MethodTree) syntax).getParameters();
        } else if (element.getKind() == ElementKind.TYPE_PARAMETER
                && owner instanceof ExecutableElement
                && syntax instanceof MethodTree) {
            declarations = ((ExecutableElement) owner).getTypeParameters();
            nodes = ((MethodTree) syntax).getTypeParameters();
        } else if (element.getKind() == ElementKind.TYPE_PARAMETER
                && owner instanceof TypeElement
                && syntax instanceof ClassTree) {
            declarations = ((TypeElement) owner).getTypeParameters();
            nodes = ((ClassTree) syntax).getTypeParameters();
        } else return null;
        int index = declarations.indexOf(element);
        if (index < 0 || nodes.size() != declarations.size()) return null;
        TreePath candidate = new TreePath(parent, nodes.get(index));
        return element.equals(trees.getElement(candidate)) ? candidate : null;
    }
}
