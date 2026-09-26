package com.example.Ece.agent.tool;

import com.example.Ece.agent.parameter.ParameterProvenance;
import com.example.Ece.agent.parameter.ParameterRegistry;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.rag.ScoredChunk;
import com.example.Ece.agent.service.WaterFertilizerPrescription;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把**有出处的参数**包装成可引用的编号来源。
 *
 * <p><b>为什么需要</b>：水肥处方里的量（灌溉下限 85%、追肥 171 kg/hm²）是有论文出处的，
 * 但它们的出处登记在 {@code agent_parameter_source}，而编排层的证据通道只认知识库切块。
 * 不包装就只能走 {@code note} 以纯文本交给模型——模型能复述，却**无法给出编号**，
 * 于是"每个结论都能追到编号来源"这条纪律在水肥处方上失效。本类把它接回同一条通道。</p>
 *
 * <p><b>只包装可引用的</b>：{@link WaterFertilizerPrescription.Item#isCitable()} 为假的条目
 * （例如"单次滴灌 60 L"——登记表里就是"示例值：演示参数"）不在这里出现。
 * 它们仍在处方正文与 note 里，并标明"未登记出处"，但**不占引用编号**：
 * 编号的含义是"这条能核对到出处"，给无出处的数值编号会把这条纪律稀释成形式。</p>
 *
 * <p><b>可核对性</b>：{@code sourceId} 取登记表主键，{@code sourceCode} 取参数编码，
 * {@code sourceVersion} 取 {@link ParameterRegistry#REGISTRY_VERSION}。
 * 三者合起来可在 {@code agent_parameter_source} 中唯一定位一行；
 * 引用里另带 {@code sourceUrl}（论文 DOI 或标准页），可直接点开核对。</p>
 */
@Component
public class ParameterEvidence {

    /** 来源表名。与知识库切块、平台快照都不同，保证 merge 去重时不与它们相撞。 */
    public static final String SOURCE_TABLE = "agent_parameter_source";

    /** 与平台快照一致：这不是相似度，而是"权威事实来源"的哨兵值。 */
    private static final double AUTHORITATIVE_SCORE = 1.0;

    private static final String TITLE = "水肥处方参数";

    private final CitationFormatter citationFormatter;

    public ParameterEvidence(CitationFormatter citationFormatter) {
        this.citationFormatter = citationFormatter;
    }

    /**
     * 取处方里有出处的条目，包成证据块。
     *
     * @param crop 作物名，进引用标签
     */
    public List<ScoredChunk> items(String crop, WaterFertilizerPrescription prescription) {
        List<ScoredChunk> result = new ArrayList<ScoredChunk>();
        if (prescription == null) {
            return result;
        }
        for (WaterFertilizerPrescription.Item item : prescription.citableItems()) {
            String content = describe(item);
            KnowledgeChunk chunk = new KnowledgeChunk(
                    SOURCE_TABLE, item.getSourceId(), crop, TITLE,
                    KnowledgeChunk.FieldType.OTHER, 0, 0,
                    content, KnowledgeChunker.sha256(content),
                    item.getParameterCode(), item.getSourceName(),
                    ParameterProvenance.Status.VERIFIED.name(), item.getSourceUrl(),
                    ParameterRegistry.REGISTRY_VERSION);
            result.add(new ScoredChunk(chunk, AUTHORITATIVE_SCORE, 1));
        }
        return result;
    }

    /** 与知识库工具走同一个 formatter，编号由编排层全局统一。 */
    public List<Map<String, Object>> citations(List<ScoredChunk> items) {
        return citationFormatter.toCitations(items);
    }

    /**
     * 证据正文。
     *
     * <p>写成"参数名 = 值 单位"的完整陈述，而不是只放参数编码：模型看到编码只能照抄，
     * 看到完整陈述才能把它读成一条可用的事实。末尾附编码与版本，供回库核对。</p>
     */
    private String describe(WaterFertilizerPrescription.Item item) {
        StringBuilder builder = new StringBuilder();
        builder.append("水肥处方参数 ").append(item.getLabel()).append(" = ")
                .append(item.getValue());
        if (item.getUnit() != null && !item.getUnit().trim().isEmpty()) {
            builder.append(' ').append(item.getUnit());
        }
        builder.append("。参数编码 ").append(item.getParameterCode());
        if (!item.getLabel().equals(item.getParameterCode())) {
            builder.append("（").append(item.getSourceName()).append("）");
        }
        return builder.toString();
    }
}
