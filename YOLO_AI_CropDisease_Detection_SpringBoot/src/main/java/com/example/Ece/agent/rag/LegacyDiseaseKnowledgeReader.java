package com.example.Ece.agent.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取项目自带的遗留病害库（{@code cropdisease.disease} 表，100 条 / 9 种作物）作为知识来源。
 *
 * <p><b>来源如实登记</b>：这是项目**历史数据**，对应 spec §23.1 分层的 **E 档（本项目旧库）**，
 * 不是权威标准，因此 {@code sourceType="E"}、权威层级取最低档 4。</p>
 *
 * <p><b>踩过的坑</b>：{@code agent_knowledge_source.source_type} 是 {@code VARCHAR(8)}，存的是
 * **分层字母 A–E**；曾把来源标签（{@code LEGACY_HISTORY}，14 字符）直接写进去，入库即报
 * "Data too long for column 'source_type'"。人可读的来源性质应写在 {@code sourceName}/{@code licenseNote}。</p>
 *
 * <p>构造 {@link KnowledgeSource} 时若缺少来源名或版本会直接抛异常——
 * "没有出处就没有知识"是硬约束，不接受匿名语料入库。</p>
 */
@Component
public class LegacyDiseaseKnowledgeReader {

    public static final String SOURCE_CODE = "legacy-disease-db";
    /** spec §23.1 语料分层：E = 本项目旧库（现有 disease 表 100 条）。 */
    public static final String SOURCE_TIER = "E";
    /** 来源表名：多来源知识库用它区分数据集。 */
    public static final String SOURCE_TABLE = "disease";
    public static final String DATA_VERSION = "2026-09-23";

    private static final String SELECT_DISEASES =
            "SELECT id, name, crop_type, symptoms, causes, prevention FROM disease ORDER BY id";

    private final JdbcTemplate jdbcTemplate;

    public LegacyDiseaseKnowledgeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public KnowledgeSource source() {
        return new KnowledgeSource(SOURCE_CODE, "项目历史病害库 cropdisease.disease 表（层级 E：本项目旧库）",
                SOURCE_TIER, 4, null,
                "层级 E 项目自带历史数据，非权威标准；对外材料须标注来源层级，不得称为权威知识库",
                DATA_VERSION);
    }

    public List<IngestRecord> readAll() {
        return jdbcTemplate.query(SELECT_DISEASES, new RowMapper<IngestRecord>() {
            public IngestRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
                Map<KnowledgeChunk.FieldType, String> fields =
                        new LinkedHashMap<KnowledgeChunk.FieldType, String>();
                put(fields, KnowledgeChunk.FieldType.SYMPTOM, rs.getString("symptoms"));
                put(fields, KnowledgeChunk.FieldType.CAUSE, rs.getString("causes"));
                put(fields, KnowledgeChunk.FieldType.CONTROL, rs.getString("prevention"));
                return new IngestRecord(SOURCE_TABLE, rs.getLong("id"), rs.getString("crop_type"),
                        rs.getString("name"), fields);
            }
        });
    }

    private void put(Map<KnowledgeChunk.FieldType, String> fields, KnowledgeChunk.FieldType type, String value) {
        if (value != null && !value.trim().isEmpty()) {
            fields.put(type, value);
        }
    }
}