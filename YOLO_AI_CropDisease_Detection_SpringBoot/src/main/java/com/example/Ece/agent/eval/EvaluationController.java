package com.example.Ece.agent.eval;

import com.example.Ece.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评测接口：跑一批四档对照，并取回指标矩阵与逐日序列。
 * 批次结果在内存中保留最近若干批，便于前端"跑一次、反复看"。
 */
@RestController
@RequestMapping("/eval")
public class EvaluationController {

    private static final int MAX_CACHED_BATCHES = 5;

    private final PerformanceEvaluationService service;
    private final Map<String, EvaluationBatch> cache = new ConcurrentHashMap<String, EvaluationBatch>();

    public EvaluationController(PerformanceEvaluationService service) {
        this.service = service;
    }

    @PostMapping("/runs")
    public Result<?> run(@RequestBody(required = false) EvaluationRunRequest request) {
        EvaluationRunRequest actual = request == null ? new EvaluationRunRequest() : request;
        long seed = actual.getSeed() == null ? 20260921L : actual.getSeed().longValue();
        int days = actual.getDays() == null ? PerformanceEvaluationService.DEFAULT_DAYS : actual.getDays().intValue();
        EvaluationBatch batch = service.runBatch(actual.getBatchId(), seed, days);
        remember(batch);
        return Result.success(batch);
    }

    @GetMapping("/{batchId}/matrix")
    public Result<?> matrix(@PathVariable String batchId) {
        EvaluationBatch batch = cache.get(batchId);
        if (batch == null) {
            return Result.error("EVAL_BATCH_NOT_FOUND", "评测批次不存在或已过期，请重新运行评测");
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("batchId", batch.getBatchId());
        payload.put("seed", Long.valueOf(batch.getSeed()));
        payload.put("days", Integer.valueOf(batch.getDays()));
        payload.put("elapsedMillis", Long.valueOf(batch.getElapsedMillis()));
        Map<String, Object> outcomes = new LinkedHashMap<String, Object>();
        for (Map.Entry<EvaluationStrategy, EvaluationOutcome> entry : batch.getOutcomes().entrySet()) {
            EvaluationOutcome outcome = entry.getValue();
            Map<String, Object> metrics = new LinkedHashMap<String, Object>();
            metrics.put("wFruit", Double.valueOf(outcome.getWFruit()));
            metrics.put("singleFruitWeightG", Double.valueOf(outcome.getSingleFruitWeightG()));
            metrics.put("fruitSetRate", Double.valueOf(outcome.getFruitSetRate()));
            metrics.put("yieldKg", Double.valueOf(outcome.getYieldKg()));
            metrics.put("marketableYieldKg", Double.valueOf(outcome.getMarketableYieldKg()));
            metrics.put("waterUsedM3", Double.valueOf(outcome.getWaterUsedM3()));
            metrics.put("energyKWh", Double.valueOf(outcome.getEnergyKWh()));
            metrics.put("co2UsedKg", Double.valueOf(outcome.getCo2UsedKg()));
            metrics.put("fertilizerUsedKg", Double.valueOf(outcome.getFertilizerUsedKg()));
            metrics.put("costYuan", Double.valueOf(outcome.getCostYuan()));
            metrics.put("revenueYuan", Double.valueOf(outcome.getRevenueYuan()));
            metrics.put("profitYuan", Double.valueOf(outcome.getProfitYuan()));
            metrics.put("highTemperatureMinutes", Long.valueOf(outcome.getHighTemperatureMinutes()));
            metrics.put("highHumidityMinutes", Long.valueOf(outcome.getHighHumidityMinutes()));
            metrics.put("highVpdMinutes", Long.valueOf(outcome.getHighVpdMinutes()));
            metrics.put("diseasePressureIntegral", Double.valueOf(outcome.getDiseasePressureIntegral()));
            metrics.put("constraintViolations", Integer.valueOf(outcome.getConstraintViolations()));
            metrics.put("finalSeverityTotal", Double.valueOf(outcome.getFinalSeverityTotal()));
            outcomes.put(entry.getKey().name(), metrics);
        }
        payload.put("outcomes", outcomes);
        return Result.success(payload);
    }

    @GetMapping("/{batchId}/series")
    public Result<?> series(@PathVariable String batchId) {
        EvaluationBatch batch = cache.get(batchId);
        if (batch == null) {
            return Result.error("EVAL_BATCH_NOT_FOUND", "评测批次不存在或已过期，请重新运行评测");
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("batchId", batch.getBatchId());
        payload.put("days", Integer.valueOf(batch.getDays()));
        Map<String, Object> series = new LinkedHashMap<String, Object>();
        for (Map.Entry<EvaluationStrategy, EvaluationOutcome> entry : batch.getOutcomes().entrySet()) {
            series.put(entry.getKey().name(), entry.getValue().getSeries());
        }
        payload.put("series", series);
        java.util.List<String> strategies = new java.util.ArrayList<String>();
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            strategies.add(strategy.name());
        }
        payload.put("strategies", strategies);
        return Result.success(payload);
    }

    private void remember(EvaluationBatch batch) {
        cache.put(batch.getBatchId(), batch);
        while (cache.size() > MAX_CACHED_BATCHES) {
            Iterator<String> keys = cache.keySet().iterator();
            if (!keys.hasNext()) {
                break;
            }
            String oldest = keys.next();
            if (!oldest.equals(batch.getBatchId())) {
                cache.remove(oldest);
            } else {
                break;
            }
        }
    }
}
