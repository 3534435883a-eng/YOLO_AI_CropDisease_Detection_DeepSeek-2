package com.example.Ece.agent.rag;

import java.util.List;

/** 知识来源登记仓储。 */
public interface KnowledgeSourceRepository {

    void save(KnowledgeSource source);

    KnowledgeSource findByCode(String sourceCode, String version);

    /** 全部来源登记，用于知识库状态展示（含权威层级与版本，便于复核）。 */
    List<KnowledgeSource> findAll();
}
