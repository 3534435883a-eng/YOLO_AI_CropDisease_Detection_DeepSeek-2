package com.example.Ece.agent.rag;

/** 知识来源登记仓储。 */
public interface KnowledgeSourceRepository {

    void save(KnowledgeSource source);

    KnowledgeSource findByCode(String sourceCode, String version);
}
