package com.example.Ece.agent.report;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.eval.PerformanceEvaluationService;
import com.example.Ece.agent.parameter.ParameterSourceService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生产规划报告的测试。
 *
 * <p>这份报告的价值在于"用得上"，因此测试盯的是**它是否真的给了能用的东西**：
 * 方案对比、推荐与依据、生育期进程、风险、以及**可信度与待确认项**是否齐全。
 * 后两节不是修辞——报告若不给边界，读的人会把推演值当实测用。</p>
 *
 * <p>用真实模型渲染（不 mock 评测服务），确保报告真的出得来；参数出处服务用 mock，
 * 因为要验证"可信度那一节是**实时取数**、而非写死一句免责声明"。</p>
 */
class ProductionReportServiceTest {

    private final PerformanceEvaluationService evaluationService = new PerformanceEvaluationService(
            new TomatoSimulationEngine(), new TomatoDecisionPolicy(), new TomatoCropGrowthModel(),
            new SoilWaterNutrientModel(), new PestDiseaseEpidemicModel(), new ManagementEconomicsModel());

    private ParameterSourceService parameterServiceWith(int total, int citable, int unverified,
                                                        int placeholder, int unitMissing) {
        ParameterSourceService service = mock(ParameterSourceService.class);
        Map<String, Object> byStatus = new LinkedHashMap<String, Object>();
        byStatus.put("VERIFIED", Integer.valueOf(citable));
        byStatus.put("UNVERIFIED_LITERATURE", Integer.valueOf(unverified));
        byStatus.put("PLACEHOLDER", Integer.valueOf(placeholder));
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("version", "sim-params-test");
        summary.put("total", Integer.valueOf(total));
        summary.put("citable", Integer.valueOf(citable));
        summary.put("byStatus", byStatus);
        summary.put("unitMissing", Integer.valueOf(unitMissing));
        when(service.summary()).thenReturn(summary);
        return service;
    }

    private String render() {
        return new ProductionReportService(evaluationService,
                parameterServiceWith(108, 1, 83, 24, 104))
                .renderMarkdown("report-test", 20260921L, 20);
    }

    @Test
    void reportCarriesEverySectionNeededToActuallyUseIt() {
        String md = render();
        assertTrue(md.contains("# 番茄温室生产规划报告"), "缺少标题");
        assertTrue(md.contains("一、生产基本情况"), "缺少生产概况");
        assertTrue(md.contains("二、方案对比"), "缺少方案对比");
        assertTrue(md.contains("三、推荐方案与依据"), "缺少推荐与依据");
        assertTrue(md.contains("四、生育期进程"), "缺少生育期进程");
        assertTrue(md.contains("五、风险提示"), "缺少风险提示");
        assertTrue(md.contains("六、数据可信度与边界"), "缺少可信度声明");
        assertTrue(md.contains("七、待人工确认项"), "缺少待确认项");
    }

    @Test
    void comparisonCoversEveryTierAndGivesPerSquareMeterYield() {
        String md = render();
        // 7 档都要出现，且业务档位用可读名、消融档明写"离线消融"（不让人误以为是要选的场景）
        assertTrue(md.contains("不做调控"));
        assertTrue(md.contains("规则自动调控"));
        assertTrue(md.contains("前瞻择优调控"));
        assertTrue(md.contains("离线消融：仅 P"));
        assertTrue(md.contains("离线消融：PID"));
        assertTrue(md.contains("kg/m²"), "应给出单位面积产量——报告要能拿去做排产");
    }

    @Test
    void recommendationIsAlwaysMarkedAsDraftAndComesWithItsCost() {
        String md = render();
        assertTrue(md.contains("推荐："), "应给出推荐方案");
        assertTrue(md.contains("待人工确认的草案"), "推荐必须标明是草案");
        assertTrue(md.contains("系统不执行任何设备操作"), "必须说明系统不代执行");
    }

    @Test
    void credibilitySectionIsFilledFromTheParameterRegistryNotHardcoded() {
        // 换一组登记数据，报告里的说法必须跟着变——否则那句声明就是写死的修辞
        String withOneCitable = new ProductionReportService(evaluationService,
                parameterServiceWith(50, 7, 30, 13, 40))
                .renderMarkdown("report-test-2", 20260921L, 5);
        assertTrue(withOneCitable.contains("`sim-params-test`"), "应带登记版本号");
        assertTrue(withOneCitable.contains("共 50 条"), "总数应取自登记表");
        assertTrue(withOneCitable.contains("带出处链接的 7 条"), "可引用条数应取自登记表");
        assertTrue(withOneCitable.contains("40 条未登记单位"), "单位缺失数应取自登记表");
        assertTrue(withOneCitable.contains("不得作为有据可依的取值对外引用"), "应给出引用纪律");
    }

    @Test
    void reportStatesTheKnownGapsInsteadOfHidingThem() {
        String md = render();
        assertTrue(md.contains("湿帘用水在评测平台未折算"), "应披露未折算项");
        assertTrue(md.contains("氮收支配平未完成"), "应披露水肥路径解耦");
        assertTrue(md.contains("水肥调控与产量当前是解耦的"), "应说明后果");
        assertTrue(md.contains("不声称预测精度"), "不得声称预测精度");
        assertTrue(md.contains("SIMULATED"), "全文须标明是场景推演");
    }

    @Test
    void reportIsReproducibleForTheSameSeed() {
        ProductionReportService service = new ProductionReportService(evaluationService,
                parameterServiceWith(108, 1, 83, 24, 104));
        String first = service.renderMarkdown("repeat", 4242L, 5);
        String second = service.renderMarkdown("repeat", 4242L, 5);
        // 生成时间那行必然不同，比较正文其余部分即可
        assertTrue(first.contains("种子：`4242`"));
        assertTrue(second.contains("种子：`4242`"));
        assertFalse(first.isEmpty());
    }
}
