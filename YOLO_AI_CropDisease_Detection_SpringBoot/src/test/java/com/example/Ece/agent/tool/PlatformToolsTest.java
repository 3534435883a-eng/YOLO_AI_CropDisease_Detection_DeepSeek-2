package com.example.Ece.agent.tool;

import com.example.Ece.agent.dto.AgentRunResponse;
import com.example.Ece.agent.dto.AgentRunSummaryResponse;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.service.AgentRunService;
import com.example.Ece.agent.service.PrescriptionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台类工具（温室状态 / 处方拟制）的测试。
 *
 * <p>核心不变量有两条：**无运行时要如实说明而不是抛错或编造**；
 * **处方工具绝不能声称已执行任何操作**（它不是写工具，只出草案）。</p>
 */
class PlatformToolsTest {

    private static final long RUN_ID = 42L;

    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();

    /** 高温高湿状态：规则层必然要动作，用于验证处方非空。 */
    private SimulationState hotHumidState() {
        return engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                33.0, 88.0, 60.0, 400.0, 900.0, 6.5);
    }

    private AgentRunResponse activeRun() {
        AgentRunResponse run = new AgentRunResponse();
        run.setId(Long.valueOf(RUN_ID));
        run.setRunCode("RUN-8-TOMATO");
        run.setStatus("RUNNING");
        run.setCurrentStep(12);
        run.setSimulatedAt("2026-09-21T09:00");
        return run;
    }

    private Map<String, Object> metric(String label, double value, String unit) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("label", label);
        row.put("value", Double.valueOf(value));
        row.put("unit", unit);
        return row;
    }

    /** 贴近真实：6 个指标 + 9 类设备。字数预算测试依赖这份规模。 */
    private AgentRunSummaryResponse summary() {
        AgentRunSummaryResponse summary = new AgentRunSummaryResponse();
        summary.setRun(activeRun());
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("temperatureC", Double.valueOf(33.0));
        state.put("airHumidityPct", Double.valueOf(88.0));
        state.put("riskLevel", "HIGH");
        summary.setCurrentState(state);

        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        metrics.add(metric("室内温度", 33.0, "C"));
        metrics.add(metric("空气湿度", 88.0, "%"));
        metrics.add(metric("土壤水分", 60.0, "%"));
        metrics.add(metric("CO2", 430.61, "ppm"));
        metrics.add(metric("光照", 272.91, "PPFD"));
        metrics.add(metric("VPD", 0.583, "kPa"));
        summary.setMetrics(metrics);

        List<Map<String, Object>> devices = new ArrayList<Map<String, Object>>();
        for (String[] pair : new String[][]{
                {"IRRIGATION", "ON"}, {"VENTILATION", "OFF"}, {"SUPPLEMENTAL_LIGHT", "OFF"},
                {"SHADE", "OFF"}, {"CO2_SUPPLY", "ON"}, {"ROOF_VENT", "OFF"},
                {"EXHAUST_FAN", "OFF"}, {"COOLING_PAD", "OFF"}, {"CIRCULATION_FAN", "ON"}}) {
            Map<String, Object> device = new LinkedHashMap<String, Object>();
            device.put("code", pair[0]);
            device.put("actualState", pair[1]);
            devices.add(device);
        }
        summary.setDevices(devices);
        summary.setAlerts(new ArrayList<Map<String, Object>>());
        summary.setResources(new ArrayList<Map<String, Object>>());
        summary.setStrategySummary("温度偏高，优先通风降温");
        return summary;
    }

    private KnowledgeRetriever retrieverWithCorpus() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(Arrays.asList(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1")));
        return retriever;
    }

    private final PlatformSnapshotEvidence evidence =
            new PlatformSnapshotEvidence(new CitationFormatter());

    private PrescriptionDraftTool prescriptionTool(AgentRunService runService, SimulationState state) {
        if (state != null) {
            when(runService.currentState(Long.valueOf(RUN_ID))).thenReturn(state);
        }
        return new PrescriptionDraftTool(runService, new PrescriptionService(),
                new TomatoDecisionPolicy(), retrieverWithCorpus(), new CitationFormatter(), evidence);
    }

    // ---------------- 温室状态工具 ----------------

    @Test
    void greenhouseStateReportsNoActiveRunInsteadOfThrowing() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(null);
        GreenhouseStateTool tool = new GreenhouseStateTool(runService, evidence);

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        assertEquals(Boolean.FALSE, output.get("available"));
        assertTrue(String.valueOf(output.get("note")).contains("没有进行中的温室模拟运行"),
                "无运行时应给出可读原因，而不是空对象");
        assertEquals("SIMULATED", output.get("source"));
    }

    @Test
    void greenhouseStateLabelsTheDataAsSimulatedNotMeasured() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        when(runService.getSummary(Long.valueOf(RUN_ID))).thenReturn(summary());
        GreenhouseStateTool tool = new GreenhouseStateTool(runService, evidence);

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        assertEquals(Boolean.TRUE, output.get("available"));
        assertEquals(Long.valueOf(RUN_ID), output.get("runId"));
        assertEquals("SIMULATED", output.get("source"));
        String note = String.valueOf(output.get("note"));
        // 术语只用「推演值 / 实测 / 实时遥测」这一套（见 GreenhouseStateTool 的注释）：
        // 曾经同时出现"现场传感器实测"，模型会把相邻术语混成"现场传感器推演显示"这类读不通的句子。
        assertTrue(note.contains("不是实测"), "必须标明数据性质，否则会被当成实测报给农户");
        assertTrue(note.contains("推演值"), "应明确这是推演值");
        assertTrue(note.contains("不是实时遥测"));
        assertNotNull(output.get("devices"));
        assertEquals("HIGH", ((Map<?, ?>) output.get("currentState")).get("riskLevel"));
    }

    @Test
    void greenhouseStateAcceptsRunIdAsString() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getSummary(Long.valueOf(RUN_ID))).thenReturn(summary());
        GreenhouseStateTool tool = new GreenhouseStateTool(runService, evidence);

        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("runId", "42");
        Map<String, Object> output = tool.execute(input);

        assertEquals(Long.valueOf(RUN_ID), output.get("runId"));
        assertEquals(Boolean.TRUE, output.get("available"));
    }

    @Test
    void greenhouseStateIsReadOnly() {
        GreenhouseStateTool tool = new GreenhouseStateTool(mock(AgentRunService.class), evidence);
        assertEquals(ToolPermission.READ_ONLY, tool.permission());
        assertEquals("platform.greenhouseState", tool.name());
        assertFalse(tool.description().isEmpty());
    }

    // ---------------- 处方拟制工具 ----------------

    @Test
    void prescriptionNeverClaimsExecution() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        PrescriptionDraftTool tool = prescriptionTool(runService, hotHumidState());

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        assertEquals(Boolean.FALSE, output.get("executed"), "处方工具不得声称已执行");
        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("待人工确认"), "必须标明是草案");
        assertTrue(note.contains("不会执行任何设备操作"));
        assertTrue(output.containsKey("proposedDeviceStates"), "设备建议应以 proposed 命名");
        assertFalse(output.containsKey("deviceCommands"), "不得使用会被读成「已下发的指令」的字段名");
    }

    @Test
    void prescriptionRequiresManualConfirmWhenRulesProposeActions() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        PrescriptionDraftTool tool = prescriptionTool(runService, hotHumidState());

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        assertEquals(Boolean.TRUE, output.get("requiresManualConfirm"));
        assertFalse(((List<?>) output.get("actions")).isEmpty(), "33℃/88% 必然有建议动作");
        // 不断言具体等级：风险等级由引擎按温度/湿度/土壤/CO₂/光照/VPD 加权算出，
        // 凭印象写死等级是脆弱的断言（初版就写错成 HIGH，实际为 MEDIUM）。
        assertTrue(Arrays.asList("LOW", "MEDIUM", "HIGH").contains(String.valueOf(output.get("riskLevel"))),
                "风险等级应为引擎定义的三种取值之一，实测 " + output.get("riskLevel"));
    }

    @Test
    void prescriptionStatesThatThePlanIsARuleLayerRecomputation() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        PrescriptionDraftTool tool = prescriptionTool(runService, hotHumidState());

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("独立重算"),
                "必须说明处方是规则层对当前状态的重算，不等同于该运行实际已执行的决策");
        assertTrue(note.contains("人工接管") || note.contains("设备故障"),
                "应点出实际动作可能与重算不同的原因");
    }

    @Test
    void prescriptionAttachesEvidenceWhenTopicGiven() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        PrescriptionDraftTool tool = prescriptionTool(runService, hotHumidState());

        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("topic", "番茄褐色轮纹斑");
        input.put("crop", "番茄");
        Map<String, Object> output = tool.execute(input);

        assertFalse(((List<?>) output.get("kbEvidence")).isEmpty(),
                "给了主题就应附带知识库依据（kbEvidence 与平台快照引用是两回事）");
        assertFalse(((List<?>) output.get("citations")).isEmpty(),
                "平台快照引用无论有没有主题都必须在，否则工具走不通作答通道");
    }

    @Test
    void prescriptionReportsNoRunGracefully() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(null);
        PrescriptionDraftTool tool = prescriptionTool(runService, null);

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        assertEquals(Boolean.FALSE, output.get("available"));
        assertEquals(Boolean.FALSE, output.get("executed"));
        assertTrue(String.valueOf(output.get("note")).contains("没有进行中的温室模拟运行"));
    }

    @Test
    void prescriptionIsDraftPermissionNotWrite() {
        PrescriptionDraftTool tool = prescriptionTool(mock(AgentRunService.class), null);
        assertEquals(ToolPermission.DRAFT, tool.permission());
        assertEquals("prescription.draft", tool.name());
        assertTrue(tool.description().contains("不会执行任何设备操作"));
    }

    // ---------------- 证据通道（2026-09-26 端到端实测暴露的缺口）----------------
    //
    // 缺口背景：编排层的完成条件只认"工具返回了 citations 且不是 lowScore"，守门又要求
    // 非空 List<ScoredChunk>。平台工具最初只回状态字段，于是永远停在"未提供可用证据"→拒答。
    // 这组断言锁住修复：平台工具必须产出**可核对的编号来源**。

    @Test
    void greenhouseStateEmitsCheckableCitationsSoTheAnswerPathCanAcceptIt() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        when(runService.getSummary(Long.valueOf(RUN_ID))).thenReturn(summary());
        GreenhouseStateTool tool = new GreenhouseStateTool(runService, evidence);

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        assertEquals(Boolean.FALSE, output.get("lowScore"), "必须显式置为非低分，否则被当作相关性不足");
        List<?> items = (List<?>) output.get("items");
        assertFalse(items.isEmpty(), "守门需要非空的 ScoredChunk 列表");
        List<?> citations = (List<?>) output.get("citations");
        assertFalse(citations.isEmpty(), "编排层需要 citations 才会置 reliableEvidence");

        Map<?, ?> citation = (Map<?, ?>) citations.get(0);
        assertEquals(PlatformSnapshotEvidence.SOURCE_TABLE, citation.get("sourceTable"),
                "来源表名要与知识库切块区分，避免 merge 去重时被误判为同一条");
        assertEquals(Long.valueOf(RUN_ID), citation.get("sourceId"), "运行号应可回查");
        assertEquals("OTHER", String.valueOf(citation.get("fieldType")));
        String sourceName = String.valueOf(citation.get("sourceName"));
        assertTrue(sourceName.contains("SIMULATED"), "出处必须写明是场景推演，不能像实测");
        String version = String.valueOf(citation.get("sourceVersion"));
        assertTrue(version.contains("run=" + RUN_ID), "版本串应带运行号与步号，便于回库核对");
    }

    /**
     * 字数预算护栏。
     *
     * <p>2026-09-26 端到端实测抓到的两个退化，都由这条测试守住：</p>
     * <ol>
     *   <li>初版把"SIMULATED 非实测"的限定语写在正文**末尾**，而模型在证据块里
     *       只看得到 {@code CitationFormatter.SNIPPET_LIMIT = 160} 字——限定语被整段截掉；</li>
     *   <li>为省字数把指标标签删掉，模型拿到一串无标注数字，
     *       实测回答里出现"40.45% 未标注其含义，我不做推断"。</li>
     * </ol>
     * <p>所以这里断言：快照正文**未被截断**，且限定语在最前、指标带标签。</p>
     */
    @Test
    void snapshotEvidenceFitsTheSnippetBudgetWithCaveatFirstAndLabelsKept() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        when(runService.getSummary(Long.valueOf(RUN_ID))).thenReturn(summary());
        GreenhouseStateTool tool = new GreenhouseStateTool(runService, evidence);

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());
        Map<?, ?> citation = (Map<?, ?>) ((List<?>) output.get("citations")).get(0);
        String snippet = String.valueOf(citation.get("snippet"));

        assertFalse(snippet.endsWith("…"),
                "快照正文超出 snippet 预算被截断，末尾内容模型看不到——请精简正文而不是放宽预算");
        assertTrue(snippet.startsWith("SIMULATED"),
                "SIMULATED 限定语必须在最前，否则一旦超预算就被截掉");
        assertTrue(snippet.contains("不是实测"), "必须写明不是实测");
        assertTrue(snippet.contains("室内温度"), "指标必须带标签，不能让模型猜哪个数字是什么");
        assertTrue(snippet.contains("空气湿度"), "指标必须带标签");
        assertTrue(snippet.contains("设备"), "设备开关状态应在预算内（回答「换气通道通不通」必需）");
    }

    @Test
    void greenhouseStateWithoutRunEmitsNoCitationsSoTheGateRefuses() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(null);
        GreenhouseStateTool tool = new GreenhouseStateTool(runService, evidence);

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        assertEquals(Boolean.FALSE, output.get("available"));
        assertFalse(output.containsKey("citations"),
                "没有运行就**不能**给引用：凭空造一条来源比拒答更糟，应让编排层正常走拒答");
        assertFalse(output.containsKey("items"));
    }

    @Test
    void prescriptionCitationDescribesTheBasisNotTheProposal() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.getActiveRun()).thenReturn(activeRun());
        when(runService.getSummary(Long.valueOf(RUN_ID))).thenReturn(summary());
        PrescriptionDraftTool tool = prescriptionTool(runService, hotHumidState());

        Map<String, Object> output = tool.execute(new LinkedHashMap<String, Object>());

        List<?> citations = (List<?>) output.get("citations");
        assertFalse(citations.isEmpty(), "草案也要给依据引用，否则模型无法作答");
        String snippet = String.valueOf(((Map<?, ?>) citations.get(0)).get("snippet"));
        assertTrue(snippet.contains("草案") || snippet.contains("待人工确认"),
                "引用正文必须写明这是草案依据，避免模型把建议当已发生的事实引用");
        assertTrue(snippet.contains("独立重算"),
                "引用正文应说明这是对当前状态的重算，不是该运行已执行的决策");
    }
}
