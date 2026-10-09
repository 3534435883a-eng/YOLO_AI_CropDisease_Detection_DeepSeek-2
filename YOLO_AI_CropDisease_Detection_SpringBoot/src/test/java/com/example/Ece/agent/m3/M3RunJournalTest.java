package com.example.Ece.agent.m3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class M3RunJournalTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode checkpoint(String id, int cursor) {
        return mapper.createObjectNode().put("schemaVersion", 1).put("runId", id).put("cursor", cursor).put("observationsSha256", "observation-hash");
    }

    @Test
    void writesAndReplacesExactlyOneCheckpointWithoutLeavingTemporaryFiles() throws Exception {
        M3RunJournal journal = new M3RunJournal(directory, mapper);
        String id = UUID.randomUUID().toString();
        journal.write(id, checkpoint(id, 1));
        journal.write(id, checkpoint(id, 9));
        assertEquals(checkpoint(id, 9), journal.read(id));
        try (Stream<Path> paths = Files.list(directory.resolve("runtime/live-runs"))) { assertEquals(1, paths.count()); }
    }

    @Test
    void badIdentityAndSchemaCannotReplaceGoodCheckpoint() throws Exception {
        M3RunJournal journal = new M3RunJournal(directory, mapper);
        String id = UUID.randomUUID().toString();
        journal.write(id, checkpoint(id, 3));
        assertThrows(IOException.class, () -> journal.write(id, checkpoint(UUID.randomUUID().toString(), 4)));
        assertThrows(IOException.class, () -> journal.write(id, checkpoint(id, 4).put("schemaVersion", 2)));
        assertThrows(IOException.class, () -> journal.write(id, mapper.createArrayNode()));
        assertThrows(IOException.class, () -> journal.write(id, null));
        assertEquals(3, journal.read(id).path("cursor").asInt());
    }

    @Test
    void failedSerializationKeepsPreviousCheckpointAndCleansTemporaryFile() throws Exception {
        String id = UUID.randomUUID().toString();
        new M3RunJournal(directory, mapper).write(id, checkpoint(id, 2));
        ObjectMapper failing = new ObjectMapper() {
            @Override public void writeValue(java.io.File file, Object value) throws IOException {
                Files.write(file.toPath(), "partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                throw new IOException("模拟磁盘写失败");
            }
        };
        assertThrows(IOException.class, () -> new M3RunJournal(directory, failing).write(id, checkpoint(id, 7)));
        assertEquals(2, new M3RunJournal(directory, mapper).read(id).path("cursor").asInt());
        try (Stream<Path> paths = Files.list(directory.resolve("runtime/live-runs"))) { assertEquals(1, paths.count()); }
    }

    @Test
    void rejectsMissingMalformedAndMismatchedArchive() throws Exception {
        M3RunJournal journal = new M3RunJournal(directory, mapper);
        String id = UUID.randomUUID().toString();
        assertThrows(IOException.class, () -> journal.read(id));
        journal.write(id, checkpoint(id, 1));
        Path saved = directory.resolve("runtime/live-runs/" + id + ".json");
        Files.write(saved, "null".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> journal.read(id));
        mapper.writeValue(saved.toFile(), checkpoint(UUID.randomUUID().toString(), 1));
        assertThrows(IOException.class, () -> journal.read(id));
    }

    @Test
    void onlyAcceptsCanonicalUuidAndInvalidWriteDoesNotCreateDirectory() {
        M3RunJournal journal = new M3RunJournal(directory, mapper);
        for (String id : new String[]{null, "../escape", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"}) {
            assertThrows(IOException.class, () -> journal.write(id, mapper.createObjectNode()));
            assertThrows(IOException.class, () -> journal.read(id));
        }
        assertFalse(Files.exists(directory.resolve("runtime/live-runs")));
    }

    @Test
    void rejectsOversizedArchiveBeforeParsingIt() throws Exception {
        M3RunJournal journal = new M3RunJournal(directory, mapper);
        String id = UUID.randomUUID().toString();
        journal.write(id, checkpoint(id, 1));
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(directory.resolve("runtime/live-runs/" + id + ".json").toFile(), "rw")) {
            file.setLength(64L * 1024 * 1024 + 1);
        }
        assertThrows(IOException.class, () -> journal.read(id));
    }
}
