package com.example.Ece.agent.rag;

/** 文本向量化能力，由 Flask 侧 bge-small-zh 服务提供。 */
public interface EmbeddingClient {

    double[] embed(String text) throws EmbeddingUnavailableException;

    /** 向量模型标识，写入知识块便于复核；默认值对应 Flask /embed 的默认模型。 */
    default String modelName() {
        return "BAAI/bge-small-zh-v1.5";
    }
}
