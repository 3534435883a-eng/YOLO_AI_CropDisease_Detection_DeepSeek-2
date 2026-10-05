package com.example.Ece.agent.m3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;

/** Read-only external observation store; no AgentRun or production sensor writes. */
@Service
public class M3ObservationService {
    private final Path root;
    private final ObjectMapper mapper;
    public M3ObservationService(ObjectMapper mapper,
            @Value("${agent.m3.data-root:../../../.runtime/datasets/horti-m3}") String directory) {
        this.mapper = mapper;
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }
    public Path root() { return root; }
    public JsonNode read() throws IOException {
        Path path = root.resolve("normalized/multiyear-observations.json");
        if (!Files.isRegularFile(path)) throw new IOException("M3三年原始观测尚未导入，请运行import_horti_m3_multiyear.py");
        byte[] bytes = Files.readAllBytes(path);
        JsonNode manifest = mapper.readTree(root.resolve("normalized/multiyear-manifest.json").toFile());
        String sha = sha256(bytes);
        if (!sha.equals(manifest.path("observationsSha256").asText()))
            throw new IOException("观测文件与导入清单SHA256不一致，请重新导入");
        ObjectNode data = (ObjectNode)mapper.readTree(bytes);
        if (data.path("schemaVersion").asInt() != 2) throw new IOException("不支持的M3三年观测版本");
        data.put("observationsSha256", sha);
        return data;
    }
    public static String sha256(byte[] bytes) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte v : MessageDigest.getInstance("SHA-256").digest(bytes)) b.append(String.format("%02x", v & 255));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public ObjectNode summary() throws IOException {
        JsonNode data = read();
        ObjectNode summary = mapper.createObjectNode();
        for (String key : new String[]{"schemaVersion", "datasetVersion", "importedAt", "sourceUrl", "paperUrl",
                "license", "publisherMd5", "archiveBytes", "observationsSha256", "cohort", "quality", "sources", "fieldMappings"})
            summary.set(key, data.get(key));
        com.fasterxml.jackson.databind.node.ArrayNode seasons = mapper.createArrayNode();
        for (JsonNode season : data.path("seasons")) {
            ObjectNode item = mapper.createObjectNode();
            for (String key : new String[]{"year", "role", "startDate", "endDate", "cohort", "quality"}) item.set(key, season.path(key));
            com.fasterxml.jackson.databind.node.ArrayNode growth = mapper.createArrayNode();
            for (JsonNode row : season.path("growth")) if (row.path("selected").asBoolean()) growth.add(row);
            item.set("growth", growth); seasons.add(item);
        }
        summary.set("seasons", seasons); summary.set("assumptions", data.path("assumptions"));
        return summary;
    }
}
