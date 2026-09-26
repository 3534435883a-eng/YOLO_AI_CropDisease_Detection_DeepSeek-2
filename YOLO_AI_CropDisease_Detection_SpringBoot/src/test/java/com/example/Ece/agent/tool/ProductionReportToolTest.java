package com.example.Ece.agent.tool;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.eval.PerformanceEvaluationService;
import com.example.Ece.agent.rag.CitationFormatter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告工具的测试。
 *
 * <p>两个关键约束：**摘要必须短到模型看得见**（证据块里每条来源只截 160 字），
 * 以及**必须走标准证据通道**（产出 citations 且非 lowScore），否则编排层的完成条件不认、只能拒答。</p>
 */
class ProductionReportToolTest {

    private final PerformanceEvaluationService evaluationService = new PerformanceEvaluationService(
            new TomatoSimulationEngine(), new TomatoDecisionPolicy(), new TomatoCropGrowthModel(),
            new SoilWaterNutrientModel(), new PestDiseaseEpidemicModel(), new ManagementEconomicsModel());

    private ProductionReportTool tool() {
        return new ProductionReportTool(evaluationService,
                new PlatformSnapshotEvidence(new CitationFormatter()));
    }

    private Map<String, Object> runWith(int days) throws Exception {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("days", Integer.valueOf(days));
        return tool().execute(input);
    }

    @Test
    void reportToolPassesTheEvidenceGateInsteadOfRefusing() throws Exception {
        Map<String, Object> output = runWith(5);
        assertEquals(Boolean.FALSE, output.get("lowScore"), "必须显式置为非低分，否则被当作相关性不足");
        List<?> items = (List<?>) output.get("items");
        assertFalse(items.isEmpty(), "守门需要非空的 ScoredChunk 列表");
        List<?> citations = (List<?>) output.get("citations");
        assertFalse(citations.isEmpty(), "编排层需要 citations 才会置 reliableEvidence");
    }

    @Test
    void summaryFitsTheSnippetBudgetSoTheModelCanActuallyReadIt() throws Exception {
        Map<String, Object> output = runWith(5);
        String summary = String.valueOf(output.get("summary"));
        // CitationFormatter 的 SNIPPET_LIMIT 是 160：超了就会被截，关键数字就丢了
        assertTrue(summary.length() <= 160,
                "摘要 " + summary.length() + " 字，超出 160 字的证据块预算，会被截断");
        assertTrue(summary.startsWith("SIMULATED"), "限定语必须在最前");
        assertTrue(summary.contains("推荐"), "推荐方案必须在摘要里");
        assertTrue(summary.contains("kg/m²"), "单位面积产量必须在摘要里");
    }

    /**
     * 增量必须带符号。
     *
     * <p>实测踩过：工具摘要里原用无符号 {@code format}，模型转述成
     * 「相对规则档产量记作 46%」——把 +46% 说成了"基准的 46%"，读数方向反了。
     * 模型是忠实转述，错在工具给的数字本身没带符号。</p>
     */
    @Test
    void deltasCarryAnExplicitSignSoPercentagesCannotBeReadBackwards() throws Exception {
        Map<String, Object> output = runWith(90);
        String summary = String.valueOf(output.get("summary"));
        assertTrue(summary.contains("相对规则档产量 +"),
                "增量应带显式正号，实得摘要：" + summary);
    }

    @Test
    void citationIdentifiesTheReportByBatchAndSeedNotByMeaninglessZeroes() throws Exception {
        Map<String, Object> output = runWith(5);
        Map<?, ?> citation = (Map<?, ?>) ((List<?>) output.get("citations")).get(0);
        String version = String.valueOf(citation.get("sourceVersion"));
        assertTrue(version.contains("batch="), "版本串应带批次号");
        assertTrue(version.contains("seed="), "版本串应带种子——同种子可复算才谈得上可回查");
        assertFalse(version.contains("run=0"), "不得沿用运行快照的 run=..;step=.. 模板（会变成无意义的 run=0;step=0）");
        assertTrue(String.valueOf(citation.get("sourceName")).contains("SIMULATED"), "出处须写明是推演");
    }

    @Test
    void reportNeverClaimsExecutionAndNamesItsLimits() throws Exception {
        Map<String, Object> output = runWith(5);
        assertEquals(Boolean.FALSE, output.get("executed"), "报告工具不得声称已执行");
        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("待人工确认"));
        assertTrue(note.contains("不是现场实测"));
        assertTrue(note.contains("水肥调控结论当前不可用"), "水肥解耦必须写进报告说明");
    }

    @Test
    void recommendationIsLimitedToProductTiersAndReportsItsCost() throws Exception {
        // 用 90 天：番茄坐果在推演期后段才发生，周期太短则各档商品产量都是 0，
        // 那时"按利润推荐"比的只是资源成本，测不出推荐逻辑本身。
        Map<String, Object> output = runWith(90);
        String label = String.valueOf(output.get("recommendedLabel"));
        assertNotNull(label);
        assertFalse(label.startsWith("离线消融"),
                "推荐不应落在离线消融档上——那是不给使用者选的场景，实得 " + label);
        assertTrue(output.containsKey("highTemperatureMinutes"), "推荐必须同时给出热风险（代价）");
        assertTrue(((Number) output.get("yieldPerSquareMeter")).doubleValue() > 0.0,
                "90 天推演应已有商品产量");
    }

    @Test
    void exportPathIsHandedBackSoTheFullReportIsReachable() throws Exception {
        Map<String, Object> output = runWith(5);
        String path = String.valueOf(output.get("exportPath"));
        assertTrue(path.startsWith("/ai/agent/report"), "应给导出路径");
        assertTrue(path.contains("seed="), "导出路径应带种子，便于复算同一份报告");
        assertTrue(output.containsKey("batchId"));
    }

    @Test
    void toolMetadataIsDraftPermissionAndDeclaresTheArtifact() {
        ProductionReportTool tool = tool();
        assertEquals(ToolPermission.DRAFT, tool.permission());
        assertEquals("report.draft", tool.name());
        assertTrue(tool.description().contains("不执行任何操作"));
        assertTrue(tool.inputSchemaJson().contains("days"));
    }

    @Test
    void handlesGarbageInputWithoutThrowing() throws Exception {
        Map<String, Object> output = tool().execute(null);
        assertEquals(Boolean.TRUE, output.get("available"), "输入为空应按默认值生成，而不是抛错");
        List<String> weird = new ArrayList<String>();
        weird.add("not-a-number");
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("days", "not-a-number");
        assertEquals(Boolean.TRUE, tool().execute(input).get("available"), "非法 days 应回退默认值");
    }
}
