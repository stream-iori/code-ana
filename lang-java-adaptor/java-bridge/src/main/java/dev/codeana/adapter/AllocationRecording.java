package dev.codeana.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/** Opt-in diagnostic recording; performance comparisons run with this disabled. */
final class AllocationRecording implements AutoCloseable {
    private final Recording recording;
    private AllocationRecording(Recording recording) { this.recording = recording; }

    static AllocationRecording open(Path scratch) throws IOException {
        if (!"1".equals(System.getenv("CODEANA_PROFILE_ALLOCATIONS")))
            return new AllocationRecording(null);
        if (!FlightRecorder.isAvailable())
            throw new IOException("JFR_UNAVAILABLE: build diagnostic library with --enable-monitoring=jfr");
        Recording recording = new Recording();
        try {
            recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
            recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
            recording.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofSeconds(1));
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));
            recording.enable("jdk.GarbageCollection");
            recording.setToDisk(true);
            recording.setMaxSize(256L << 20);
            recording.setDestination(scratch.resolve("allocations.jfr"));
            recording.start();
            return new AllocationRecording(recording);
        } catch (IOException | RuntimeException | Error failure) {
            recording.close(); throw failure;
        }
    }
    @Override public void close() {
        if (recording != null) {
            try { recording.stop(); } finally { recording.close(); }
        }
    }
}
