package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;
import dev.codeana.common.GraphArchive;
import dev.codeana.graph.GraphRecords;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphArchiveTest {
    @Test void protobufPreservesSqlNullEmptyUnicodeAndLongIntegers(@TempDir Path root) throws Exception {
        var row=GraphRecords.Row.newBuilder().setTable(GraphRecords.Table.PROJECT_META)
            .addCells(GraphRecords.Cell.getDefaultInstance())
            .addCells(GraphRecords.Cell.newBuilder().setText(""))
            .addCells(GraphRecords.Cell.newBuilder().setText("中文😀"))
            .addCells(GraphRecords.Cell.newBuilder().setInteger(1L<<40)).build();
        Path destination=root.resolve("graph.rows");
        try(var archive=new GraphArchive(root.resolve("temporary"),1<<20,10)) {
            archive.add(row);var descriptor=archive.export(destination);
            byte[] bytes=Files.readAllBytes(destination);
            assertEquals(bytes.length,((Number)descriptor.get("bytes")).intValue());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),descriptor.get("sha256"));
            try(var input=new DataInputStream(Files.newInputStream(destination))) {
                assertEquals(0x47524131,input.readInt());
                assertEquals(row,GraphRecords.Row.parseFrom(input.readNBytes(input.readInt())));
                assertEquals(-1,input.read());
            }
        }
        assertTrue(Files.isRegularFile(destination));
    }
    @Test void recordAndArchiveBudgetsRejectWithoutPublishing(@TempDir Path root) throws Exception {
        var row=GraphRecords.Row.newBuilder().setTable(GraphRecords.Table.PROJECT_META)
            .addCells(GraphRecords.Cell.newBuilder().setText("value")).build();
        try(var archive=new GraphArchive(root.resolve("count"),1024,1)) {
            archive.add(row);assertThrows(IOException.class,()->archive.add(row));
        }
        assertFalse(Files.exists(root.resolve("count")));
        try(var archive=new GraphArchive(root.resolve("bytes"),4,10)) {
            assertThrows(IOException.class,()->archive.add(row));
        }
        assertFalse(Files.exists(root.resolve("bytes")));
    }
    @Test void exportedArchiveCannotAcceptMoreRecords(@TempDir Path root) throws Exception {
        var row=GraphRecords.Row.newBuilder().setTable(GraphRecords.Table.PROJECT_META).build();
        try(var archive=new GraphArchive(root.resolve("temporary"),1024,10)) {
            archive.add(row);archive.export(root.resolve("graph.rows"));
            assertThrows(IOException.class,()->archive.add(row));
            assertThrows(IOException.class,()->archive.export(root.resolve("second")));
        }
    }
}
