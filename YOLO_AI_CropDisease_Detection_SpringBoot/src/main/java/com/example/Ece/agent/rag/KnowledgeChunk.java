package com.example.Ece.agent.rag;

/** 可检索知识块及其来源登记。 */
public class KnowledgeChunk {

    /**
     * 知识块的类别。
     *
     * <p><b>前四个是"病害形状"的</b>（本知识库最初的唯一形状：旧 {@code disease} 表就是这个结构）；
     * 后七个是 2026-09-26 为**非病害的农事知识**新增的。加它们的原因是一次端到端实测：
     * 智能体被问"定植后一周该怎么管""膨果期浇多少水"时只能答"依据不足"，
     * 因为知识库里 283 块**全是病害**，而水肥/栽培/环境调控的知识**没有类别可以落**。</p>
     *
     * <p><b>命名必须是缩写</b>：{@code agent_knowledge_chunk.field_type} 是 {@code varchar(16)}，
     * 而 {@code CONTROL_BIOLOGICAL} 有 18 个字符，直接入库会 {@code Data too long}。
     * 这个坑本项目在 {@code source_type} 上已经踩过一次（见设计文档 §23 的"真实 bug"记要），
     * 故此处一律控制在 16 字符内并加测试锁住。</p>
     */
    public enum FieldType {
        /** 症状（病害） */
        SYMPTOM,
        /** 诱因（病害） */
        CAUSE,
        /** 防治（病害，旧库的笼统写法） */
        CONTROL,
        /** 其他 */
        OTHER,
        /** 栽培管理（整地、定植、整枝、授粉、疏果、采收等农事操作） */
        CULTIVATION,
        /** 水肥管理（灌溉制度、施肥制度、水肥一体化） */
        WATER_FERT,
        /** 环境调控（温度、湿度、光照、通风、CO₂ 等阈值与措施） */
        ENVIRONMENT,
        /** 农业防治 */
        CTRL_AGRI,
        /** 物理防治 */
        CTRL_PHYS,
        /** 生物防治 */
        CTRL_BIO,
        /** 化学防治 */
        CTRL_CHEM;

        /**
         * 是否属于"病害形状"的类别。
         *
         * <p>决定上下文头写「病害：X」还是「主题：X」。这个区分必须存在：把"水肥管理"写成
         * "病害：水肥管理"会让检索与阅读都错位。</p>
         */
        public boolean isDiseaseField() {
            return this == SYMPTOM || this == CAUSE || this == CONTROL;
        }
    }

    private final String sourceTable;
    private final long sourceId;
    private final String cropType;
    private final String diseaseName;
    private final FieldType fieldType;
    private final int chunkNo;
    private final int startOffset;
    private final String content;
    private final String contentHash;
    private final String sourceCode;
    private final String sourceName;
    private final String sourceType;
    private final String sourceUrl;
    private final String sourceVersion;

    public KnowledgeChunk(String sourceTable, long sourceId, String cropType, String diseaseName,
                          FieldType fieldType, int chunkNo, int startOffset, String content, String contentHash) {
        this(sourceTable, sourceId, cropType, diseaseName, fieldType, chunkNo, startOffset, content, contentHash,
                null, null, null, null, null);
    }

    public KnowledgeChunk(String sourceTable, long sourceId, String cropType, String diseaseName,
                          FieldType fieldType, int chunkNo, int startOffset, String content, String contentHash,
                          String sourceCode, String sourceName, String sourceType, String sourceUrl,
                          String sourceVersion) {
        this.sourceTable = sourceTable;
        this.sourceId = sourceId;
        this.cropType = cropType;
        this.diseaseName = diseaseName;
        this.fieldType = fieldType;
        this.chunkNo = chunkNo;
        this.startOffset = startOffset;
        this.content = content;
        this.contentHash = contentHash;
        this.sourceCode = sourceCode;
        this.sourceName = sourceName;
        this.sourceType = sourceType;
        this.sourceUrl = sourceUrl;
        this.sourceVersion = sourceVersion;
    }

    public String getSourceTable() { return sourceTable; }

    public long getSourceId() { return sourceId; }

    public String getCropType() { return cropType; }

    public String getDiseaseName() { return diseaseName; }

    public FieldType getFieldType() { return fieldType; }

    public int getChunkNo() { return chunkNo; }

    public int getStartOffset() { return startOffset; }

    public String getContent() { return content; }

    /**
     * 去掉上下文头的正文。
     *
     * <p>检索的"证据共现"判据只看正文：头部里的作物名是**元数据而非证据**。
     * 实测反例——"如何给番茄施肥"因每个块头部都含"番茄"，共现判据拿到 {番茄, 施肥} 2 个词而放行，
     * 但知识库其实没有施肥知识。头部仍参与语料级覆盖率，因此病名类提问（如"番茄早疫病"）
     * 依然能凭"疫病/早疫"这类稀有词通过覆盖率判据。</p>
     */
    public String getBodyText() {
        if (content == null) {
            return "";
        }
        if (!content.startsWith("作物：") && !content.startsWith("病害：") && !content.startsWith("字段：")) {
            return content;
        }
        int end = content.indexOf('。');
        return end < 0 ? content : content.substring(end + 1);
    }

    public String getContentHash() { return contentHash; }

    public String getSourceCode() { return sourceCode; }

    public String getSourceName() { return sourceName; }

    public String getSourceType() { return sourceType; }

    public String getSourceUrl() { return sourceUrl; }

    public String getSourceVersion() { return sourceVersion; }
}
