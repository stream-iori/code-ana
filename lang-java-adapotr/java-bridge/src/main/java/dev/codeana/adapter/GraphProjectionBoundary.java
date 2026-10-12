package dev.codeana.adapter;

import com.anatomist.core.*;
import com.anatomist.model.ExtractionResult;
import java.util.*;

/** Runs the copied graph passes with captured identities and a complete visible catalogue. */
final class GraphProjectionBoundary {
    private final SourceIdentityResolver identities;

    GraphProjectionBoundary(CapturedProject project) {
        List<SourceRoot> roots = new ArrayList<>();
        for (var source : project.sources.values()) {
            var scope = source.resource()
                    ? project.contexts.stream()
                        .filter(context -> context.resources().contains(source))
                        .map(CapturedProject.Context::scope).findFirst().orElseThrow()
                    : SourceScope.valueOf(source.scope());
            roots.add(new SourceRoot(source.file(), source.module(), scope));
        }
        identities = SourceIdentityResolver.fromRoots(project.root, roots);
    }

    GraphPostProcessor.Summary process(ExtractionResult result, Set<String> visible) {
        Set<String> qualified = new HashSet<>();
        for (String id : visible) if (NodeKeyFactory.isKey(id)) qualified.add(id);
        GraphIdentityRewriter.rewrite(result, identities, qualified);
        var summary = new GraphPostProcessor().process(result, qualified);
        // Fact IR uses its own stable ids. Keep the original declaration key for raw-model
        // joins, while edge/annotation targets retain the upstream qualified selection.
        for (var node : result.nodes) node.id = node.symbolId;
        return summary;
    }
}
