package com.example.Ece.agent.repository;

import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 知识块写入的 JDBC 实现：块表 + 来源映射表，向量以 float32 二进制存放。 */
@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private static final String DELETE_CHUNKS_BY_SOURCE = "DELETE c FROM agent_knowledge_chunk c "
            + "JOIN agent_knowledge_chunk_origin o ON o.content_hash = c.content_hash WHERE o.source_code = ?";
    private static final String DELETE_ORIGIN_BY_SOURCE =
            "DELETE FROM agent_knowledge_chunk_origin WHERE source_code = ?";
    private static final String SELECT_HASHES = "SELECT content_hash FROM agent_knowledge_chunk";
    private static final String SELECT_ALL = "SELECT source_table, source_id, crop_type, disease_name, field_type, "
            + "chunk_no, start_offset, content, content_hash FROM agent_knowledge_chunk ORDER BY id";
    private static final String SELECT_EMBEDDINGS = "SELECT embedding FROM agent_knowledge_chunk ORDER BY id";
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

    public int deleteBySourceCode(String sourceCode) {
        int removed = jdbcTemplate.update(DELETE_CHUNKS_BY_SOURCE, sourceCode);
        jdbcTemplate.update(DELETE_ORIGIN_BY_SOURCE, sourceCode);
        return removed;
    }

    /** 按病害名取遗留 disease 表的记录 ID（用于把识别事件关联到具体病害条目）；查不到返回 null。 */
    public Long findDiseaseSourceId(String diseaseName) {
        if (diseaseName == null || diseaseName.trim().isEmpty()) {
            return null;
        }
        List<Long> found = jdbcTemplate.queryForList(
                "SELECT MIN(source_id) FROM agent_knowledge_chunk WHERE source_table = 'disease' AND disease_name = ?",
                Long.class, diseaseName.trim());
        Long value = found.isEmpty() ? null : found.get(0);
        return value == null || value.longValue() <= 0L ? null : value;
    }

    public List<KnowledgeChunk> loadAll() {
        return jdbcTemplate.query(SELECT_ALL, new org.springframework.jdbc.core.RowMapper<KnowledgeChunk>() {
            public KnowledgeChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
                return new KnowledgeChunk(rs.getString("source_table"), rs.getLong("source_id"),
                        rs.getString("crop_type"), rs.getString("disease_name"),
                        parseFieldType(rs.getString("field_type")), rs.getInt("chunk_no"),
                        rs.getInt("start_offset"), rs.getString("content"), rs.getString("content_hash"));
            }
        });
    }

    public List<double[]> loadEmbeddings() {
        List<byte[]> raw = jdbcTemplate.query(SELECT_EMBEDDINGS, new org.springframework.jdbc.core.RowMapper<byte[]>() {
            public byte[] mapRow(ResultSet rs, int rowNum) throws SQLException {
                return rs.getBytes(1);
            }
        });
        List<double[]> vectors = new ArrayList<double[]>();
        for (byte[] bytes : raw) {
            vectors.add(toVector(bytes));
        }
        return vectors;
    }

    private KnowledgeChunk.FieldType parseFieldType(String value) {
        if (value == null) {
            return KnowledgeChunk.FieldType.OTHER;
        }
        try {
            return KnowledgeChunk.FieldType.valueOf(value);
        } catch (IllegalArgumentException error) {
            return KnowledgeChunk.FieldType.OTHER;
        }
    }

    /** 向量按 float32 序列化为二进制（{@link ByteBuffer} 默认字节序），与迁移的 VARBINARY(4096) 对应（512 维 = 2048 字节）。 */
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

    /** 反序列化；空值或长度不是 4 的倍数时返回 null，交由调用方现场重新向量化。 */
    private double[] toVector(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 4 != 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        double[] vector = new double[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
