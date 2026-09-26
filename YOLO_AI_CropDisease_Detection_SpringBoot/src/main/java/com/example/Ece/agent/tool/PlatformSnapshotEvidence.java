package com.example.Ece.agent.tool;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把「平台状态 / 规则层推演」包装成**可核对的编号来源**，使它能走编排层既有的证据通道。
 *
 * <p><b>为什么需要它</b>：编排层的完成条件只认"工具返回了 {@code citations} 且不是 lowScore"
 * （见 {@code AgentOrchestrator} 中 {@code reliableEvidence} 的唯一置真处），
 * 而守门 {@code GuardrailService} 又要求一份非空的 {@code List<ScoredChunk>}。
 * 平台类工具若只回状态字段，就会永远停在"未提供可用证据"→拒答。</p>
 *
 * <p><b>为什么用包装而不是改契约</b>：可以给编排层加"证据类别"、让平台状态走第二套完成条件，
 * 但那等于承认存在两类证据，会削弱项目最硬的那条纪律——"每个结论都能追到编号来源"。
 * 这里选择让平台快照**符合既有契约**：它就是一条有编号、有出处、可回查的事实来源，
 * 与知识库条目走完全相同的通道，编排层零改动。</p>
 *
 * <p><b>可核对性</b>：来源表名 {@value #SOURCE_TABLE} 与知识库切块分开，
 * {@code sourceId} 是运行号、{@code chunkNo} 是步号，{@code sourceVersion} 写成
 * {@code run=..;step=..}，任何人可以拿这三个值回库核对当时的状态。
 * 这与"来源未登记"的历史条目形成对照：平台快照的来源是明确的、可复算的。</p>
 */
@Component
public class PlatformSnapshotEvidence {

    /** 来源表名。与知识库切块的 sourceTable 区分，避免 merge 去重时被误判为同一条。 */
    public static final String SOURCE_TABLE = "agent_run_snapshot";

    /** 固定分值的含义：这不是相似度，而是"权威事实来源"的哨兵值。 */
    private static final double AUTHORITATIVE_SCORE = 1.0;

    private final CitationFormatter citationFormatter;

    public PlatformSnapshotEvidence(CitationFormatter citationFormatter) {
        this.citationFormatter = citationFormatter;
    }

    /**
     * 把一段平台内容包成可引用的证据块。
     *
     * @param runId   模拟运行编号，作为 sourceId
     * @param stepNo  模拟步号，作为 chunkNo
     * @param crop    作物
     * @param title   引用标签里的名称（如"温室环境快照"）
     * @param content 正文，会被渲染进证据块供模型引用
     */
    public List<ScoredChunk> items(long runId, int stepNo, String crop, String title, String content) {
        int chunkNo = Math.max(0, stepNo);
        KnowledgeChunk chunk = new KnowledgeChunk(
                SOURCE_TABLE, runId, crop, title, KnowledgeChunk.FieldType.OTHER,
                chunkNo, 0, content, KnowledgeChunker.sha256(content),
                "SIM-RUN-" + runId,
                // 只用「推演值 / 实测」这一对词，不再同时出现"现场传感器""推演""显示"等近义表述。
                // 实测中模型会把相邻术语混成"现场传感器推演显示"这类句子——术语越少越不容易串。
                "场景推演快照（SIMULATED，不是实测）",
                "SIMULATED",
                null,
                "run=" + runId + ";step=" + stepNo);
        List<ScoredChunk> items = new ArrayList<ScoredChunk>();
        items.add(new ScoredChunk(chunk, AUTHORITATIVE_SCORE, 1));
        return items;
    }

    /** 转成编排层与守门都认得的引用列表（与知识库工具走同一个 formatter）。 */
    public List<Map<String, Object>> citations(List<ScoredChunk> items) {
        return citationFormatter.toCitations(items);
    }
}
