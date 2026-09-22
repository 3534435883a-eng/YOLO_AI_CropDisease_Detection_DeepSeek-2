package com.example.Ece.agent.rag;

import java.util.List;
import java.util.Set;

/**
 * 知识块仓储。判重是**全局**的：{@code agent_knowledge_chunk.content_hash} 上有唯一键，
 * 同一内容只存一次（来源以首次入库者为准），这样重复 ingest 不会因唯一键冲突而失败。
 */
public interface KnowledgeChunkRepository {

    /** 已入库的全部块内容哈希，用于幂等判重。 */
    Set<String> existingContentHashes();

    /** 批量写入，返回本次新写入的块数。 */
    int saveAll(String sourceCode, int authorityLevel, List<KnowledgeChunk> chunks,
                List<double[]> embeddings, String embeddingModel);

    /**
     * 删除某个来源的全部知识块（连同其来源映射），返回删除块数。
     *
     * <p>ingest 采用**全量替换**语义：切块规则或内容调整后重新 ingest 必须能生效，
     * 而 {@code agent_knowledge_chunk} 的唯一键是「来源表|来源ID|字段|块号」，
     * 直接改内容再插入会撞唯一键。</p>
     */
    int deleteBySourceCode(String sourceCode);

    /** 按入库顺序读取全部知识块，用于启动时重建内存索引。 */
    List<KnowledgeChunk> loadAll();

    /** 读取与 {@link #loadAll()} **顺序一一对应**的向量；未写入向量的行返回 null 元素。 */
    List<double[]> loadEmbeddings();
}
