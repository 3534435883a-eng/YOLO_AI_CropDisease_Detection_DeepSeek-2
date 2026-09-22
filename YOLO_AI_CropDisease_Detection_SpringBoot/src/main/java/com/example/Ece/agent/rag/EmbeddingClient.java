package com.example.Ece.agent.rag;

import java.util.ArrayList;
import java.util.List;

/** 文本向量化能力，由 Flask 侧 bge-small-zh 服务提供。 */
public interface EmbeddingClient {

    /**
     * 批量向量化的建议条数（单次请求）。
     *
     * <p>实测依据：本地 Flask + bge-small-zh 一次 32 条 **79 ms**（返回 32×512），
     * 而逐条 32 次约 **1,150 ms**（单条 36 ms），差异几乎全在 HTTP 往返上；
     * 79 ms 远低于默认 3 s 读超时，故取 32 兼顾吞吐与超时余量。</p>
     */
    int DEFAULT_BATCH_SIZE = 32;

    double[] embed(String text) throws EmbeddingUnavailableException;

    /**
     * 批量向量化，返回顺序与入参一一对应。
     *
     * <p>默认实现逐条调用 {@link #embed}，保证任何实现都能用；HTTP 实现覆盖为**单次请求**
     * ——实测 32 条一次往返 79 ms，而逐条 32 次约 1,150 ms，差异几乎全在 HTTP 往返上
     * （单条 36 ms 里模型前向只占很小一部分）。</p>
     *
     * <p>失败语义与 {@link #embed} 一致：整体失败抛异常，**不做部分成功**——
     * 调用方需要一个明确的"这批没成"信号来决定是降级还是退回逐条重试。</p>
     */
    default List<double[]> embedBatch(List<String> texts) throws EmbeddingUnavailableException {
        List<double[]> vectors = new ArrayList<double[]>();
        if (texts == null) {
            return vectors;
        }
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    /** 向量模型标识，写入知识块便于复核；默认值对应 Flask /embed 的默认模型。 */
    default String modelName() {
        return "BAAI/bge-small-zh-v1.5";
    }
}
