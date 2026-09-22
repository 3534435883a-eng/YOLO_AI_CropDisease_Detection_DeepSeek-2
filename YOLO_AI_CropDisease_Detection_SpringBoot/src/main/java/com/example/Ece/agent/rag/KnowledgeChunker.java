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

    /**
     * 切块并给每块补上**上下文头**。
     *
     * <p><b>为什么必须有上下文头（实测修正）</b>：原实现把字段原文直接作为块内容，作物名与病害名只存在表列里。
     * 后果是用病名提问几乎检索不到——实测「作物+病名」直检 Top-1 仅 <b>52.5%</b>，
     * 因为"玉米"这类作物词把几十个块拉成同分，病害名在内容里根本不存在，排序近乎随机
     * （如"玉米弯孢霉叶斑病"首条返回"玉米大斑病"）。补上「作物·病害·字段」后，病名与字段名都进了
     * 倒排索引与向量，块自身也变成可独立阅读的引用片段。</p>
     *
     * @param sourceTable 来源表名（多来源知识库需要，不再硬编码 disease）
     */
    public List<KnowledgeChunk> chunk(String sourceTable, long sourceId, String cropType, String diseaseName,
                                      Map<KnowledgeChunk.FieldType, String> fields) {
        List<KnowledgeChunk> result = new ArrayList<KnowledgeChunk>();
        if (fields == null) {
            return result;
        }
        String table = sourceTable == null || sourceTable.trim().isEmpty() ? SOURCE_TABLE : sourceTable.trim();
        for (Map.Entry<KnowledgeChunk.FieldType, String> entry : fields.entrySet()) {
            String text = entry.getValue() == null ? "" : entry.getValue().trim();
            if (text.isEmpty()) {
                continue;
            }
            String header = contextHeader(cropType, diseaseName, entry.getKey());
            int chunkNo = 0;
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                String content = header + text.substring(start, end);
                String hash = sha256(table + "|" + sourceId + "|" + entry.getKey() + "|" + chunkNo + "|" + content);
                result.add(new KnowledgeChunk(table, sourceId, cropType, diseaseName,
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

    /** 兼容旧调用：默认来源表 disease。 */
    public List<KnowledgeChunk> chunk(long sourceId, String cropType, String diseaseName,
                                      Map<KnowledgeChunk.FieldType, String> fields) {
        return chunk(SOURCE_TABLE, sourceId, cropType, diseaseName, fields);
    }

    /**
     * 上下文头：作物 + 病害 + 字段。用自然语言短句而非符号标记，让中文 bigram 切分能自然命中
     * （"番茄早疫病"会切出 番茄/茄早/早疫/疫病，直接对上查询词）。
     */
    static String contextHeader(String cropType, String diseaseName, KnowledgeChunk.FieldType fieldType) {
        StringBuilder builder = new StringBuilder();
        if (cropType != null && !cropType.trim().isEmpty()) {
            builder.append("作物：").append(cropType.trim()).append("；");
        }
        if (diseaseName != null && !diseaseName.trim().isEmpty()) {
            builder.append("病害：").append(diseaseName.trim()).append("；");
        }
        builder.append("字段：").append(fieldLabel(fieldType)).append("。");
        return builder.toString();
    }

    static String fieldLabel(KnowledgeChunk.FieldType fieldType) {
        if (fieldType == KnowledgeChunk.FieldType.SYMPTOM) {
            return "症状";
        }
        if (fieldType == KnowledgeChunk.FieldType.CAUSE) {
            return "诱因";
        }
        if (fieldType == KnowledgeChunk.FieldType.CONTROL) {
            return "防治";
        }
        return "其他";
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
