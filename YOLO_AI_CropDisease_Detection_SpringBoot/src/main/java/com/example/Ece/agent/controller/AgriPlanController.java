package com.example.Ece.agent.controller;

import com.alibaba.fastjson.JSON;
import com.example.Ece.agent.dto.AgriPlanRequest;
import com.example.Ece.agent.plan.AgriSituationCsvReader;
import com.example.Ece.agent.plan.AgriSituationInput;
import com.example.Ece.agent.plan.AgriSituationNlParser;
import com.example.Ece.agent.plan.DeductionCancelledException;
import com.example.Ece.agent.plan.DeductionEvent;
import com.example.Ece.agent.plan.DeductionResult;
import com.example.Ece.agent.plan.PlanDeductionService;
import com.example.Ece.agent.plan.SituationDraft;
import com.example.Ece.agent.plan.SituationField;
import com.example.Ece.agent.plan.SituationFields;
import com.example.Ece.agent.repository.AgriPlanRunRepository;
import com.example.Ece.agent.service.AgriPlanHistoryService;
import com.example.Ece.common.Result;
import com.example.Ece.config.AgriPlanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 农事规划推演的 SSE 入口。
 *
 * <p>形状参照 {@code AgentChatController}，但有**三处刻意不照抄**，都是长推演特有的问题：</p>
 * <ol>
 *   <li><b>超时自定</b>：编排器那条链路用 {@code TOTAL_TIMEOUT_MS}（90s）当 emitter 超时，
 *       推演明显更长，照抄必然在中途被掐。这里取配置值（默认 240s），并保证
 *       {@code emitter 超时 > 上游预算}。</li>
 *   <li><b>有界线程池而非每请求裸 Thread</b>：短问答挂 90s 无所谓，推演挂几分钟，
 *       并发几个用户就是无界线程。队列满时明确拒绝，而不是悄悄排队。</li>
 *   <li><b>断开即取消上游</b>：原实现只吞掉发送异常、工作线程继续跑完——
 *       用户关了页面，系统还在为这次推演付费。这里用 {@code closed} 标志让增量回调返回 false，
 *       由 {@code DeepSeekService.chatStream} 关闭上游连接。</li>
 * </ol>
 */
@RestController
@RequestMapping("/ai/agri/plan")
public class AgriPlanController {

    private static final Logger log = LoggerFactory.getLogger(AgriPlanController.class);

    /** 同时进行的推演数上限。推演是长任务，宁可拒绝也不无限堆。 */
    private static final int POOL_SIZE = 2;

    /** 排队上限。超出即拒绝，让用户立即知道而不是等一个不知道多久的队列。 */
    private static final int QUEUE_CAPACITY = 8;

    @Resource
    private PlanDeductionService deductionService;

    @Resource
    private AgriSituationCsvReader csvReader;

    @Resource
    private AgriSituationNlParser nlParser;

    @Resource
    private AgriPlanHistoryService historyService;

    @Resource
    private AgriPlanProperties properties;

    private final ExecutorService executor = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
            new ThreadFactory() {
                private int counter = 0;

                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "agri-plan-" + (++counter));
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.AbortPolicy());

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @PostMapping(value = "/deduce", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter deduce(@RequestBody(required = false) AgriPlanRequest request) {
        final AgriPlanRequest actual = request == null ? new AgriPlanRequest() : request;
        final SseEmitter emitter = new SseEmitter(Long.valueOf(properties.getTimeoutMs()));
        final AtomicBoolean closed = new AtomicBoolean(false);
        // 客户端断开的三条路径都要认，否则 closed 只在一部分情况下生效。
        emitter.onCompletion(new Runnable() {
            public void run() { closed.set(true); }
        });
        emitter.onTimeout(new Runnable() {
            public void run() {
                closed.set(true);
                emitter.complete();
            }
        });
        emitter.onError(error -> closed.set(true));

        try {
            executor.execute(new Runnable() {
                public void run() {
                    runDeduction(actual, emitter, closed);
                }
            });
        } catch (RejectedExecutionException rejected) {
            // 明确报"忙"，而不是让请求悄悄排队——用户有权知道要等还是重试。
            log.warn("推演并发已满，拒绝本次请求");
            sendQuietly(emitter, DeductionEvent.TYPE_ERROR,
                    payloadOf("message", "推演任务已满，请稍后重试"));
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 自然语言描述 → 结构化农情。**返回的是待用户确认的草稿，不是最终输入。**
     *
     * <p>前端把它渲染成可编辑表单，用户改完确认后才调 {@code /deduce}。
     * 少了这一步，模型的抽取错误会静默进入推演。</p>
     */
    @PostMapping("/situation/parse")
    public Result<?> parseSituation(@RequestBody(required = false) Map<String, Object> request) {
        Object raw = request == null ? null : request.get("text");
        String text = raw == null ? null : String.valueOf(raw);
        return Result.success(nlParser.parse(text).toPayload());
    }

    /** CSV 上传 → 结构化农情。原地解析，不落盘。 */
    @PostMapping("/situation/upload")
    public Result<?> uploadSituation(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("AGRI_SITUATION_INVALID", "没有收到文件。");
        }
        try (InputStream stream = file.getInputStream()) {
            AgriSituationCsvReader.CsvParseResult parsed = csvReader.read(stream);
            if (!parsed.isOk()) {
                return Result.error("AGRI_SITUATION_INVALID", parsed.getError());
            }
            Map<String, Object> payload = SituationDraft
                    .of(parsed.getInput(), null, parsed.getNotes())
                    .toPayload();
            // 列映射报告要一并给出去：用户得知道自己传的哪些列**被忽略了**，
            // 否则他会以为那些数据已经进了推演。
            payload.put("mappedColumns", parsed.getMappedColumns());
            payload.put("unrecognizedColumns", parsed.getUnrecognizedColumns());
            payload.put("dataRowCount", Integer.valueOf(parsed.getDataRowCount()));
            return Result.success(payload);
        } catch (IOException error) {
            log.warn("CSV 读取失败：{}", error.toString());
            return Result.error("AGRI_SITUATION_INVALID", "文件读取失败，请确认是 UTF-8 或 GBK 编码的 CSV。");
        }
    }

    /**
     * 列名模板下载。带上 UTF-8 BOM，Excel 双击打开不乱码。
     *
     * <p>把"该传什么"变成一个可下载的确定答案，比在界面上写一段说明有效。</p>
     */
    @GetMapping("/template.csv")
    public void template(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"agri-situation-template.csv\"");
        // BOM
        response.getOutputStream().write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(buildTemplate().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private String buildTemplate() {
        List<String> headers = new ArrayList<String>();
        for (SituationField field : SituationFields.all()) {
            headers.add(field.getUnit() == null
                    ? field.getLabel() : field.getLabel() + "(" + field.getUnit() + ")");
        }
        StringBuilder builder = new StringBuilder();
        builder.append(String.join(",", headers)).append('\n');
        builder.append("番茄,粉贝贝,日光温室,600,2026-08-15,坐果期,基质栽培,"
                + "25,78,62,600,450,1.1,滴灌每2天1次,平衡肥每周1次,上周整枝,"
                + "下部叶片有少量黄斑,无,轻度,以产量为先,2026-11-20,30000\n");
        return builder.toString();
    }

    private void runDeduction(AgriPlanRequest request, SseEmitter emitter, AtomicBoolean closed) {
        AgriSituationInput situation = request.toSituation();
        try {
            DeductionResult result = deductionService.deduce(situation, request.getQuestion(),
                    request.getSeed(), request.getDays(),
                    event -> {
                        // 已断开时不再往下发。**上游的中止靠 closed 传进 service 完成**——
                        // 只在这里 return 是不够的，那样上游会继续生成、继续计费。
                        if (closed.get()) {
                            return;
                        }
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("type", event.getType());
                        payload.putAll(event.getPayload());
                        sendQuietly(emitter, event.getType(), payload);
                    },
                    closed::get);
            // 落库是旁路：record 内部自己吞掉全部异常，不会让一次成功的推演变成失败。
            // 放在 deduce 之后而不是其中，是为了**取消时不落库**——cancel 会抛异常，走不到这里。
            Long runId = historyService.record(situation, request.getQuestion(), result);
            if (runId != null) {
                // 补发一条记录编号，界面据此提供导出与回查。
                sendQuietly(emitter, "record", payloadOf("id", runId));
            }
            emitter.complete();
        } catch (DeductionCancelledException cancelled) {
            // 用户主动取消不是故障：不下发 error，也不落库。
            // 但**用 INFO 而不是 DEBUG**：推演一次要跑几十秒、消耗大量 token，
            // "用户多频繁地中途放弃"是运维该看得见的信息；埋到 DEBUG 等于看不见。
            log.info("推演被客户端取消，已中止上游调用且不落库");
            emitter.complete();
        } catch (RuntimeException error) {
            log.warn("推演执行失败：{}", error.toString());
            sendQuietly(emitter, DeductionEvent.TYPE_ERROR,
                    payloadOf("message", "推演执行失败，请稍后重试"));
            emitter.complete();
        }
    }

    /**
     * 农情字段定义。
     *
     * <p>暴露出来是为了让**前端表单也由同一份登记表驱动**。前端再抄一份字段清单，
     * 加字段时就必然出现"后端有、前端没有"或反之；而这类不一致的表现是
     * "某个字段在界面上根本填不了"，不会报错。</p>
     */
    @GetMapping("/fields")
    public Result<?> fields() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (SituationField field : SituationFields.all()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", field.getKey());
            item.put("label", field.getLabel());
            item.put("group", field.getGroup());
            item.put("unit", field.getUnit());
            item.put("kind", field.getKind().name());
            item.put("aliases", field.getAliases());
            item.put("rangeText", field.rangeText());
            items.add(item);
        }
        return Result.success(items);
    }

    /** 最近的推演记录，倒序。 */
    @GetMapping("/history")
    public Result<?> history(@RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (AgriPlanRunRepository.PlanRunRow row : historyService.listRecent(limit)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.id);
            item.put("createdAt", row.createdAt);
            item.put("seed", Long.valueOf(row.seed));
            item.put("days", Integer.valueOf(row.days));
            item.put("question", row.question);
            item.put("streamMode", row.streamMode);
            item.put("bannerInjected", Boolean.valueOf(row.bannerInjected));
            item.put("baselineBatchId", row.baselineBatchId);
            item.put("sections", row.split(row.sections));
            item.put("missingFields", row.split(row.missingFields));
            item.put("elapsedMs", Long.valueOf(row.elapsedMs));
            // 列表不带正文：一条推演上千字，全带下来会让列表接口变得很重。
            items.add(item);
        }
        return Result.success(items);
    }

    /**
     * 导出 Markdown：推演正文 + 内嵌可复算基线。
     *
     * <p>与 {@code /ai/agent/report} 同样是**已说明的例外，不套 Result 信封**——
     * 导出的是文档本身，包一层 JSON 反而让下载方要多解一次。</p>
     */
    @GetMapping(value = "/{id}/export", produces = "text/markdown;charset=UTF-8")
    public void export(@PathVariable("id") long id, HttpServletResponse response) throws IOException {
        String markdown = historyService.exportMarkdown(id);
        if (markdown == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("没有编号为 " + id + " 的推演记录。");
            return;
        }
        response.setContentType("text/markdown;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"agri-plan-" + id + ".md\"");
        response.getWriter().write(markdown);
        response.getWriter().flush();
    }

    private Map<String, Object> payloadOf(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }

    private void sendQuietly(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(JSON.toJSONString(payload), MediaType.APPLICATION_JSON));
        } catch (Exception error) {
            // 客户端断开时 send 会抛。这里不能上抛：推演的终态仍要落库供审计。
            log.debug("推演事件下发失败（客户端可能已断开）：{}", error.toString());
        }
    }
}
