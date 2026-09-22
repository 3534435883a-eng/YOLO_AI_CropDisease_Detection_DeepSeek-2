package com.example.Ece.agent.rag;

/** 向量服务不可用（未启动、超时、返回异常）时抛出，调用方据此降级为纯关键词检索。 */
public class EmbeddingUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
