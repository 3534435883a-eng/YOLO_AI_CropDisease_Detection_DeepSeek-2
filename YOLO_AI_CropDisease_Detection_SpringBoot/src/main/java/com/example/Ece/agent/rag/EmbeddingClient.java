package com.example.Ece.agent.rag;

/** 文本向量化能力，由 Flask 侧 bge-small-zh 服务提供。 */
public interface EmbeddingClient {

    double[] embed(String text) throws EmbeddingUnavailableException;
}
