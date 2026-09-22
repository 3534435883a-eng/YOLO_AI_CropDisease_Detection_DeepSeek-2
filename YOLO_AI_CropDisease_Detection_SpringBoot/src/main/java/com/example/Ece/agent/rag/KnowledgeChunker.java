package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把病害知识文本切成可检索的知识块。
 * 规则：单块 500 字、重叠 80 字；空字段跳过；内容哈希用于幂等重建索引。
 */
@Component
public class KnowledgeChunker {

    public static final int CHUNK_SIZE = 500;
    public static final int OVERLAP = 80;
    public static final int STEP = CHUNK_SIZE - OVERLAP;

    private static final String SOURCE_TABLE = "disease";

    public List<KnowledgeChunk> chunk(long sourceId, String cropType, String diseaseName,
                                      Map<KnowledgeChunk.FieldType, String> fields) {
        List<KnowledgeChunk> result = new ArrayList<KnowledgeChunk>();
        if (fields == null) {
            return result;
        }
        for (Map.Entry<KnowledgeChunk.FieldType, String> entry : fields.entrySet()) {
            String text = entry.getValue() == null ? "" : entry.getValue().trim();
            if (text.isEmpty()) {
                continue;
            }
            int chunkNo = 0;
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                String content = text.substring(start, end);
                String hash = sha256(SOURCE_TABLE + "|" + sourceId + "|" + entry.getKey() + "|" + chunkNo + "|" + content);
                result.add(new KnowledgeChunk(SOURCE_TABLE, sourceId, cropType, diseaseName,
                        entry.getKey(), chunkNo, start, content, hash));
                chunkNo++;
                if (end == text.length()) {
                    break;
                }
                start += STEP;
            }
        }
        return result;
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
