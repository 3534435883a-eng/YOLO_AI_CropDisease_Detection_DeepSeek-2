package com.example.Ece.agent.repository;

import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeChunkRepository;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
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
    private static final String SELECT_ALL = "SELECT c.source_table, c.source_id, c.crop_type, c.disease_name, "
            + "c.field_type, c.chunk_no, c.start_offset, c.content, c.content_hash, o.source_code, "
            + "s.source_name, s.source_type, s.url AS source_url, s.version AS source_version "
            + "FROM agent_knowledge_chunk c "
            + "LEFT JOIN agent_knowledge_chunk_origin o ON o.id = "
            + "(SELECT MIN(origin_row.id) FROM agent_knowledge_chunk_origin origin_row "
            + "WHERE origin_row.content_hash = c.content_hash) "
            + "LEFT JOIN agent_knowledge_source s ON s.id = "
            + "(SELECT MAX(source_row.id) FROM agent_knowledge_source source_row "
            + "WHERE source_row.source_code = o.source_code) ORDER BY c.id";
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

    /**
     * 批量写入知识块与来源映射。
     *
     * <p>原实现是**逐块两条单行 update**：338 块 = 676 次数据库往返，实测写库 4,000 ms 左右，
     * 成为 ingest 里最大的一段（比向量化还贵）。改为 {@code batchUpdate} 后配合数据源 URL 上的
     * {@code rewriteBatchedStatements=true} 才会真正合并成多值 INSERT——少了那个参数，
     * Connector/J 仍逐条发送，改了也白改。</p>
     *
     * <p>返回值语义：能走到这里说明整个批次都已提交（JDBC 批量失败会抛
     * {@code BatchUpdateException} 而不是静默丢行），因此直接返回入参条数。</p>
     */
    public int saveAll(final String sourceCode, final int authorityLevel, final List<KnowledgeChunk> chunks,
                       List<double[]> embeddings, final String embeddingModel) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        final Timestamp now = new Timestamp(System.currentTimeMillis());
        final List<byte[]> vectors = new ArrayList<byte[]>();
        for (int i = 0; i < chunks.size(); i++) {
            double[] vector = embeddings != null && i < embeddings.size() ? embeddings.get(i) : null;
            vectors.add(toBytes(vector));
        }
        jdbcTemplate.batchUpdate(INSERT_CHUNK, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                KnowledgeChunk chunk = chunks.get(index);
                statement.setString(1, chunk.getSourceTable());
                statement.setLong(2, chunk.getSourceId());
                statement.setString(3, chunk.getCropType());
                statement.setString(4, chunk.getDiseaseName());
                statement.setString(5, chunk.getFieldType() == null ? null : chunk.getFieldType().name());
                statement.setInt(6, chunk.getChunkNo());
                statement.setInt(7, chunk.getStartOffset());
                statement.setString(8, chunk.getContent());
                statement.setString(9, chunk.getContentHash());
                statement.setBytes(10, vectors.get(index));
                statement.setString(11, embeddingModel);
                statement.setTimestamp(12, now);
            }

            public int getBatchSize() {
                return chunks.size();
            }
        });
        jdbcTemplate.batchUpdate(INSERT_ORIGIN, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setString(1, chunks.get(index).getContentHash());
                statement.setString(2, sourceCode);
                statement.setInt(3, authorityLevel);
                statement.setTimestamp(4, now);
            }

            public int getBatchSize() {
                return chunks.size();
            }
        });
        return chunks.size();
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
                        rs.getInt("start_offset"), rs.getString("content"), rs.getString("content_hash"),
                        rs.getString("source_code"), rs.getString("source_name"), rs.getString("source_type"),
                        rs.getString("source_url"), rs.getString("source_version"));
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
