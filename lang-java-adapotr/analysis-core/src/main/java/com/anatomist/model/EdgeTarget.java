package com.anatomist.model;

/** Exclusive edge destination used at graph persistence boundaries. */
public sealed interface EdgeTarget permits EdgeTarget.Internal, EdgeTarget.External {

    record Internal(String nodeId) implements EdgeTarget {
        public Internal {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("internal edge target must have a node id");
            }
        }
    }

    record External(String fqn, String resolution) implements EdgeTarget {
        public External {
            if (fqn == null || fqn.isBlank()) {
                throw new IllegalArgumentException("external edge target must have an FQN");
            }
            resolution = resolution == null ? GraphConstants.Resolution.CLASSPATH : resolution;
        }
    }
}
