package com.example.Ece.agent.m3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Atomic checkpoints of consumed observations and simulated states, never future observations. */
final class M3RunJournal {
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private final Path root;
    private final ObjectMapper mapper;
    M3RunJournal(Path datasetRoot, ObjectMapper mapper) {
        this.root=datasetRoot.resolve("runtime/live-runs").toAbsolutePath().normalize();
        this.mapper=mapper;
    }
    static String validateId(String id) throws IOException {
        try {
            if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();
            return id;
        } catch(IllegalArgumentException e){throw new IOException("运行编号无效");}
    }
    JsonNode read(String id) throws IOException {
        Path path=path(id);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("在线运行不存在，请选择已有任务或开始新运行");
        if(Files.size(path)>MAX_FILE_BYTES)throw new IOException("运行存档超过读取上限");
        JsonNode value=mapper.readTree(path.toFile());
        validateCheckpoint(id,value);
        return value;
    }
    void write(String id,JsonNode checkpoint) throws IOException {
        Path destination=path(id);
        validateCheckpoint(id,checkpoint);
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root))throw new IOException("运行存档目录不能为符号链接");
        Path temporary=Files.createTempFile(root,"checkpoint-",".tmp");
        try {
            mapper.writeValue(temporary.toFile(),checkpoint);
            if(Files.size(temporary)>MAX_FILE_BYTES)throw new IOException("运行存档超过写入上限");
            try {Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    private Path path(String id) throws IOException {
        Path destination=root.resolve(validateId(id)+".json").normalize();
        if(!root.equals(destination.getParent())||Files.isSymbolicLink(root)||Files.isSymbolicLink(destination))
            throw new IOException("运行存档路径无效");
        return destination;
    }
    private void validateCheckpoint(String id,JsonNode value) throws IOException {
        if(value==null||!value.isObject()||value.path("schemaVersion").asInt()!=1||!id.equals(value.path("runId").asText()))
            throw new IOException("运行存档格式不兼容");
    }
}
