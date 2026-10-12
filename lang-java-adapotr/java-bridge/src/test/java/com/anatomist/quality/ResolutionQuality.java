package com.anatomist.quality;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Exact-set quality metrics for symbol and relation resolution. */
public final class ResolutionQuality {
    private ResolutionQuality() {}

    public static Metrics evaluate(Collection<String> expected, Collection<String> actual) {
        Set<String> truth = expected == null ? Set.of() : Set.copyOf(expected);
        Set<String> observed = actual == null ? Set.of() : Set.copyOf(actual);
        Set<String> matched = new HashSet<>(observed);
        matched.retainAll(truth);
        int truePositives = matched.size();
        return new Metrics(
                truePositives, observed.size() - truePositives, truth.size() - truePositives);
    }

    public record Metrics(int truePositives, int falsePositives, int falseNegatives) {
        public Metrics {
            if (truePositives < 0 || falsePositives < 0 || falseNegatives < 0) {
                throw new IllegalArgumentException("quality counts cannot be negative");
            }
        }

        public double precision() {
            int emitted = truePositives + falsePositives;
            return emitted == 0 ? 1.0 : (double) truePositives / emitted;
        }

        public double recall() {
            int expected = truePositives + falseNegatives;
            return expected == 0 ? 1.0 : (double) truePositives / expected;
        }

        public double f1() {
            double precision = precision();
            double recall = recall();
            return precision + recall == 0.0
                    ? 0.0
                    : 2.0 * precision * recall / (precision + recall);
        }

        public boolean passes(double minimumPrecision, double minimumRecall) {
            return precision() >= minimumPrecision && recall() >= minimumRecall;
        }
    }
}
