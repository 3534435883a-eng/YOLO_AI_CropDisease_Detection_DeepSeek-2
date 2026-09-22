package com.example.Ece.agent.repository;

import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 知识块写入的 JDBC 实现：块表 + 来源映射表，向量以 float32 二进制存放。 */
@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private static final String SELECT_HASHES = "SELECT content_hash FROM agent_knowledge_chunk";
    private static final String INSERT_CHUNK = "INSERT INTO agent_knowledge_chunk "
            + "(source_table, source_id, crop_type, disease_name, field_type, chunk_no, start_offset, content, "
            + "content_hash, embedding, embedding_model, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_ORIGIN = "INSERT INTO agent_knowledge_chunk_origin "
            + "(content_hash, source_code, authority_level, created_at) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Set<String> existingContentHashes() {
        List<String> hashes = jdbcTemplate.queryForList(SELECT_HASHES, String.class);
        return new LinkedHashSet<String>(hashes);
    }

    public int saveAll(String sourceCode, int authorityLevel, List<KnowledgeChunk> chunks,
                       List<double[]> embeddings, String embeddingModel) {
        int written = 0;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            double[] vector = embeddings != null && i < embeddings.size() ? embeddings.get(i) : null;
            jdbcTemplate.update(INSERT_CHUNK, chunk.getSourceTable(), Long.valueOf(chunk.getSourceId()),
                    chunk.getCropType(), chunk.getDiseaseName(),
                    chunk.getFieldType() == null ? null : chunk.getFieldType().name(),
                    Integer.valueOf(chunk.getChunkNo()), Integer.valueOf(chunk.getStartOffset()),
                    chunk.getContent(), chunk.getContentHash(), toBytes(vector), embeddingModel, now);
            jdbcTemplate.update(INSERT_ORIGIN, chunk.getContentHash(), sourceCode,
                    Integer.valueOf(authorityLevel), now);
            written++;
        }
        return written;
    }

    /** 向量按 float32 小端序列化为二进制，与迁移中的 VARBINARY(4096) 对应（512 维 = 2048 字节）。 */
    private byte[] toBytes(double[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (double value : vector) {
            buffer.putFloat((float) value);
        }
        return buffer.array();
    }
}
