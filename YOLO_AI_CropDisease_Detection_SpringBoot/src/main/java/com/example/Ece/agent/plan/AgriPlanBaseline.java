package com.example.Ece.agent.plan;

import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.eval.EvaluationBatch;
import com.example.Ece.agent.eval.EvaluationOutcome;
import com.example.Ece.agent.eval.EvaluationStrategy;
import com.example.Ece.agent.eval.PerformanceEvaluationService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推演通道的机理模型参考基线。
 *
 * <p><b>它不采用用户的农情输入，这一点必须说清楚。</b>基线来自
 * {@link PerformanceEvaluationService#runBatch}，而该服务的初始条件是写死的
 * （{@code PerformanceEvaluationService.simulate} 里的 {@code SIMULATION_START, 22.0, 72.0, …}），
 * 七档策略之间比较的是控制策略，不是棚况。所以它是一份"标准情景基线"，
 * 用来给模型的推演一个**可复算的对照物**，而不是"你棚里会发生什么"的预测。</p>
 *
 * <p><b>为什么不做个性化基线</b>：曾打算用用户输入的温湿光驱动 {@code TomatoCropGrowthModel}
 * 逐日推进，复核后放弃——光照缺失时该模型干物质增量归零，但 GDD、生育期、单果重仍在推进，
 * 产出的是一条"物理上已死却不报错"的曲线；且单点读数无法驱动逐日推进、幼苗初值也对不上
 * 用户棚里的实际阶段。详见 {@code docs/tomato-greenhouse-agent.md} 的说明。
 * 拿假输入换来的"个性化"不是个性化，是把未知伪装成已知。</p>
 */
@Component
public class AgriPlanBaseline {

    /** 缓存上限。评测一批是 7 档 × 天数 × 96 步，重复算的代价很高。 */
    private static final int CACHE_CAPACITY = 8;

    private final PerformanceEvaluationService evaluationService;

    private final Map<String, EvaluationBatch> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, EvaluationBatch>(CACHE_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, EvaluationBatch> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });

    public AgriPlanBaseline(PerformanceEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * 取标准情景基线。
     *
     * @param seed 天气相位种子；同种子同天数必然逐值复现
     * @param days 推演天数
     */
    public Baseline compute(long seed, int days) {
        int effectiveDays = days <= 0 ? PerformanceEvaluationService.DEFAULT_DAYS : days;
        String key = seed + "|" + effectiveDays;
        EvaluationBatch batch;
        synchronized (cache) {
            batch = cache.get(key);
            if (batch == null) {
                batch = evaluationService.runBatch(null, seed, effectiveDays);
                cache.put(key, batch);
            }
        }
        return Baseline.from(batch);
    }

    /** 一次基线计算的结果：给模型看的一段文字 + 给界面看的结构化数值。 */
    public static class Baseline {

        private final String batchId;
        private final long seed;
        private final int days;
        private final boolean available;
        private final String unavailableReason;
        private final String strategyLabel;
        private final String strategyCode;
        private final double marketableYieldKg;
        private final double yieldPerSquareMeter;
        private final double profitYuan;
        private final double waterUsedM3;
        private final double energyKwh;
        private final long highTemperatureMinutes;
        private final String promptBlock;

        private Baseline(String batchId, long seed, int days, boolean available, String unavailableReason,
                         String strategyLabel, String strategyCode, double marketableYieldKg,
                         double yieldPerSquareMeter, double profitYuan, double waterUsedM3,
                         double energyKwh, long highTemperatureMinutes, String promptBlock) {
            this.batchId = batchId;
            this.seed = seed;
            this.days = days;
            this.available = available;
            this.unavailableReason = unavailableReason;
            this.strategyLabel = strategyLabel;
            this.strategyCode = strategyCode;
            this.marketableYieldKg = marketableYieldKg;
            this.yieldPerSquareMeter = yieldPerSquareMeter;
            this.profitYuan = profitYuan;
            this.waterUsedM3 = waterUsedM3;
            this.energyKwh = energyKwh;
            this.highTemperatureMinutes = highTemperatureMinutes;
            this.promptBlock = promptBlock;
        }

        static Baseline unavailable(long seed, int days, String reason) {
            return new Baseline(null, seed, days, false, reason, null, null,
                    0, 0, 0, 0, 0, 0, null);
        }

        static Baseline from(EvaluationBatch batch) {
            EvaluationStrategy best = null;
            EvaluationOutcome bestOutcome = null;
            for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
                EvaluationOutcome candidate = batch.getOutcomes().get(strategy);
                if (candidate == null || !strategy.isProductTier()) {
                    continue;
                }
                if (bestOutcome == null || candidate.getProfitYuan() > bestOutcome.getProfitYuan()) {
                    best = strategy;
                    bestOutcome = candidate;
                }
            }
            if (best == null || bestOutcome == null) {
                return unavailable(batch.getSeed(), batch.getDays(), "本批评测数据不足，没有可用的参考方案。");
            }

            double perSquareMeter = bestOutcome.getMarketableYieldKg() / SoilParameters.BED_AREA_M2;
            return new Baseline(batch.getBatchId(), batch.getSeed(), batch.getDays(), true, null,
                    best.getLabel(), best.name(), bestOutcome.getMarketableYieldKg(), perSquareMeter,
                    bestOutcome.getProfitYuan(), bestOutcome.getWaterUsedM3(),
                    bestOutcome.getEnergyKWh(), bestOutcome.getHighTemperatureMinutes(),
                    promptBlockOf(batch, best, bestOutcome, perSquareMeter));
        }

        /**
         * 给模型看的一段文字。
         *
         * <p>把"不采用你的输入"放在**最前面**：模型读到的顺序影响它怎么引用这些数字，
         * 而这份数字最容易被误当成"你棚里的预测"。同时给出复算标识（批次号 + 种子），
         * 让任何引用都可回查。</p>
         */
        private static String promptBlockOf(EvaluationBatch batch, EvaluationStrategy best,
                                            EvaluationOutcome outcome, double perSquareMeter) {
            StringBuilder builder = new StringBuilder();
            builder.append("以下数值来自 **Horti-M3 2025 / CK / 广辉201 参数参考场景**（未导入原始观测、未校准）的机理模型评测，")
                    .append("**不采用你提供的农情输入**，因此不是对你棚况的预测，")
                    .append("只能作为方案取值的量级参照。\n");
            builder.append("- 参考情景推荐策略：").append(best.getLabel())
                    .append("（").append(best.name()).append("）\n");
            builder.append("- ").append(batch.getDays()).append(" 天商品产量：")
                    .append(format(outcome.getMarketableYieldKg(), 1)).append(" kg，折合 ")
                    .append(format(perSquareMeter, 1)).append(" kg/m²\n");
            builder.append("- 利润：").append(format(outcome.getProfitYuan(), 0)).append(" 元\n");
            builder.append("- 耗水：").append(format(outcome.getWaterUsedM3(), 2)).append(" m³；耗电：")
                    .append(format(outcome.getEnergyKWh(), 1)).append(" kWh\n");
            builder.append("- 高温暴露时长：").append(outcome.getHighTemperatureMinutes()).append(" 分钟\n");
            builder.append("- 复算标识：批次 ").append(batch.getBatchId()).append("，种子 ")
                    .append(batch.getSeed()).append("（同一批次与种子可逐值复现）\n");
            String windowNote = windowNoteOf(outcome.getProfitYuan());
            if (windowNote != null) {
                builder.append(windowNote).append('\n');
            }
            builder.append("注意：该情景的初始条件与设备参数是演示取值，")
                    .append("840 株和 252 m² 是全试验区模拟规模，非 CK 处理实测；不得当作工程设计值或真实耗量引用。\n");
            return builder.toString();
        }

        /**
         * 推演窗口不足时的提示；不需要提示时返回 {@code null}。
         *
         * <p><b>这条是实测补上的</b>（2026-09-28 真实推演 60 天窗口）：利润算出来是 <b>-1447.70 元</b>。
         * 原因是番茄坐果在推演期后段才发生，短窗口里产量没上来而资源成本已经计满，
         * 于是"利润为负"主要反映的是窗口长度，不是经营亏损。</p>
         *
         * <p>{@code ProductionReportService} 只对"零产量"做了提示，覆盖不到这种
         * "有产量但利润为负"的中间地带。而模型看到 -1447 这个数，很容易把它当成
         * 一个真实的经营信号写进方案。**数字没错，但它的含义需要解释**，所以由这里补上。</p>
         */
        private static String windowNoteOf(double profitYuan) {
            if (profitYuan >= 0) {
                return null;
            }
            return "**注意：该窗口内利润为负（" + format(profitYuan, 0)
                    + " 元），这是推演期太短导致的**——番茄坐果在推演期后段才发生，"
                    + "短窗口里产量尚未上来、资源成本却已计满。这个负数**不代表经营亏损**，"
                    + "不要据此写成本结论；当前 M3 观测窗口不等于完整生长季；经济性需补充完整季观测后校准。";
        }

        public String getBatchId() { return batchId; }

        public long getSeed() { return seed; }

        public int getDays() { return days; }

        public boolean isAvailable() { return available; }

        public String getUnavailableReason() { return unavailableReason; }

        public String getStrategyLabel() { return strategyLabel; }

        public String getStrategyCode() { return strategyCode; }

        public double getMarketableYieldKg() { return marketableYieldKg; }

        public double getYieldPerSquareMeter() { return yieldPerSquareMeter; }

        public double getProfitYuan() { return profitYuan; }

        public double getWaterUsedM3() { return waterUsedM3; }

        public double getEnergyKwh() { return energyKwh; }

        public long getHighTemperatureMinutes() { return highTemperatureMinutes; }

        /** 供提示词使用的文案；不可用时为 {@code null}。 */
        public String getPromptBlock() { return promptBlock; }

        /**
         * 推演窗口不足的提示，需要时下发给界面。
         *
         * <p>前端必须**显著展示**它：界面旁边就是一个 -1447 的利润数字，
         * 不解释的话，看到的人只会得到一个反向的结论。</p>
         */
        public String getWindowNote() {
            return available ? windowNoteOf(profitYuan) : null;
        }

        private static String format(double value, int scale) {
            return String.format("%." + Math.max(0, scale) + "f", Double.valueOf(value));
        }
    }
}
