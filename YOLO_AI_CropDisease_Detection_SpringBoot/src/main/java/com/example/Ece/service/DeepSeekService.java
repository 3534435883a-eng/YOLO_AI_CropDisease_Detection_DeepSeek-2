package com.example.Ece.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.config.DeepSeekProperties;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeepSeekService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekService.class);
    private static final int MAX_MESSAGES = 30;
    private static final int MAX_CONTENT_LENGTH = 12_000;
    private static final List<String> ALLOWED_ROLES = Arrays.asList("system", "user", "assistant");
    private static final int LEGACY_MAX_TOKENS = 1200;
    private static final int PLANNING_MAX_TOKENS = 600;
    private static final int COMPOSING_PLAIN_MAX_TOKENS = 3000;
    private static final int COMPOSING_MAX_TOKENS = 8000;

    /**
     * 推演步的输出预算。
     *
     * <p><b>12000 是实测出来的，不是拍的</b>（2026-09-28 对 {@code deepseek-flash} 实打）：
     * 该模型对 {@code max_tokens} 取 8192 / 12000 / 16000 均返回 200，网络上流传的
     * "deepseek-chat 系输出上限 8192"在这个模型上不成立。取 12000 是因为推演要一次吐出
     * 分节方案 + 尾部结构化 JSON 块，比普通作答长。</p>
     *
     * <p>即便如此仍保留截断兜底（{@link DeepSeekException} 的 {@code AI_OUTPUT_TRUNCATED}
     * 与调用侧关思考重试）：<b>换模型或上游改口径时这个数字会失效</b>，而失效的表现是
     * 用户拿到半截方案却不自知。实测数据要跟着模型走，不能当成永久事实。</p>
     */
    private static final int SIMULATING_MAX_TOKENS = 12000;

    /**
     * 单次调用的可选参数。
     *
     * <p>思考模式按官方 API 默认是**开启**的，但并非所有调用都该开：</p>
     * <ul>
     *   <li><b>规划步</b>关闭思考：只需要吐一个严格 JSON 动作，思考既拖慢每一步往返，
     *       又会与正文争夺 {@code max_tokens} 输出预算（推理占满时 {@code content} 会是空的）。</li>
     *   <li><b>作答步</b>开启思考并放宽预算：要组织带引用的中文长答，值得多花推理。</li>
     * </ul>
     *
     * <p><b>兼容性约束</b>：{@code thinking} 为 {@code null} 时**完全不发送该字段**，
     * 使既有调用方（旧版问答接口）的请求体逐字节不变——上游若不认识该字段会直接 400。</p>
     */
    public static final class ChatOptions {

        private final Boolean thinking;
        private final int maxTokens;

        private ChatOptions(Boolean thinking, int maxTokens) {
            this.thinking = thinking;
            this.maxTokens = maxTokens;
        }

        /** 规划步：关闭思考、收紧输出预算。 */
        public static ChatOptions planning() {
            return new ChatOptions(Boolean.FALSE, PLANNING_MAX_TOKENS);
        }

        /** 作答步：开启思考、放宽输出预算。 */
        public static ChatOptions composing() {
            return new ChatOptions(Boolean.TRUE, COMPOSING_MAX_TOKENS);
        }

        /**
         * 作答步的退路：**关闭思考**。
         *
         * <p>实测（2026-09-23，deepseek-flash）：思考模式下作答 20s 以上时，推理可能吃光输出预算，
         * 返回 {@code finish_reason=length} 且 {@code content} 为空（报 AI_OUTPUT_TRUNCATED）。
         * 关掉思考后没有推理占用预算，正文一定拿得到——用质量换可用性，且只在必要时才走这条路。</p>
         */
        public static ChatOptions composingWithoutThinking() {
            return new ChatOptions(Boolean.FALSE, COMPOSING_PLAIN_MAX_TOKENS);
        }

        /** 既有行为：不发送 thinking 字段、沿用原 token 上限。 */
        public static ChatOptions legacy() {
            return new ChatOptions(null, LEGACY_MAX_TOKENS);
        }

        /**
         * 推演步：开启思考、预算放到 {@link #SIMULATING_MAX_TOKENS}。
         *
         * <p>推演要模型用自己的知识做条件推理（"这个棚况下第几周会出什么问题"），
         * 思考带来的质量提升比作答步更明显，因此这里同样开启思考。</p>
         */
        public static ChatOptions simulating() {
            return new ChatOptions(Boolean.TRUE, SIMULATING_MAX_TOKENS);
        }
    }

    /**
     * 流式增量的接收方。
     *
     * <p>用返回值而不是抛异常来表达"中止"：调用方（SSE 控制器）在客户端断开时返回 {@code false}，
     * 流会立即关闭并释放上游连接。用异常的话，中止与"上游出错"两条路径会在 catch 里混在一起，
     * 分不清是该记 {@code AI_STREAM_CANCELLED} 还是故障。</p>
     */
    @FunctionalInterface
    public interface DeltaConsumer {

        /**
         * @param text      本次增量文本
         * @param reasoning 该增量属于思考过程（{@code reasoning_content}）还是正文（{@code content}）
         * @return {@code false} 表示调用方要求中止，流会立即关闭
         */
        boolean accept(String text, boolean reasoning);
    }

    private final DeepSeekProperties properties;
    private final RestTemplate restTemplate;

    /**
     * 流式专用 RestTemplate（读超时无限）。
     *
     * <p><b>刻意不放进构造器</b>：{@code DeepSeekServiceTest} 有 14 处直接 {@code new}
     * 两参构造器，加一个参数会让它们全部编译失败——那是为了让生产代码少写一个 setter
     * 而在改测试。setter 注入是本仓库的既有先例（见 {@code KnowledgeSearchTool} 的知识图谱注入）。</p>
     */
    private RestTemplate streamingRestTemplate;

    public DeepSeekService(DeepSeekProperties properties,
                           @Qualifier("deepSeekRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Autowired(required = false)
    public void setStreamingRestTemplate(
            @Qualifier("deepSeekStreamingRestTemplate") RestTemplate streamingRestTemplate) {
        this.streamingRestTemplate = streamingRestTemplate;
    }

    /**
     * 流式通道是否已接线。
     *
     * <p>暴露出来是为了让测试能断言它——setter 注入失败的表现是**静默降级**（推演照样出结果，
     * 只是永远走非流式），本仓库已四次出现"建好了但零调用"，不留静默降级。</p>
     */
    public boolean isStreamingWired() {
        return streamingRestTemplate != null;
    }

    @SuppressWarnings("rawtypes")
    public AiChatResponse chat(List<ChatMessage> messages) {
        return chat(messages, ChatOptions.legacy());
    }

    @SuppressWarnings("rawtypes")
    public AiChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
        return chat(messages, options, restTemplate);
    }

    /**
     * 非流式调用，但走**流式专用**的 RestTemplate（读超时无限）。
     *
     * <p>给推演通道的降级路径用：推演的降级是"上游流式失败 → 整段重来一次"，而整段重来
     * 恰恰是最长的一次请求，用 60s 读超时的 {@code deepSeekRestTemplate} 会撞线。
     * 又不能用流式模板去发一个"假流式"请求——那需要解析 SSE 帧，而 {@code stream=false}
     * 返回的是单个 JSON 对象。</p>
     *
     * <p>超时仍受控：读超时无限只意味着"不因静默而中断"，整段时长由调用方的
     * SSE emitter 超时预算兜底。</p>
     */
    public AiChatResponse chatWithStreamingTimeout(List<ChatMessage> messages, ChatOptions options) {
        if (streamingRestTemplate == null) {
            // 没接线时退回普通超时，而不是装作有长超时——失败会以网络错误的形状暴露出来。
            return chat(messages, options, restTemplate);
        }
        return chat(messages, options, streamingRestTemplate);
    }

    @SuppressWarnings("rawtypes")
    private AiChatResponse chat(List<ChatMessage> messages, ChatOptions options, RestTemplate template) {
        ChatOptions actualOptions = options == null ? ChatOptions.legacy() : options;
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        try {
            if (!properties.hasApiKey()) {
                throw new DeepSeekException(DeepSeekException.CODE_NOT_CONFIGURED, "AI 服务尚未配置，请联系管理员设置 API Key");
            }
            validateMessages(messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey().trim());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getModel());
            body.put("messages", messages);
            body.put("stream", false);
            body.put("max_tokens", Integer.valueOf(actualOptions.maxTokens));
            if (actualOptions.thinking != null) {
                Map<String, Object> thinking = new LinkedHashMap<String, Object>();
                thinking.put("type", actualOptions.thinking.booleanValue() ? "enabled" : "disabled");
                body.put("thinking", thinking);
            }

            String upstreamUrl = normalizedBaseUrl() + "/chat/completions";
            ResponseEntity<Map> response = template.exchange(
                    upstreamUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            AiChatResponse result = parseResponse(response.getBody());
            logOutcome(requestId, "AI_SUCCESS", startedAt);
            return result;
        } catch (DeepSeekException error) {
            logOutcome(requestId, error.getCode(), startedAt);
            throw error;
        } catch (HttpStatusCodeException error) {
            DeepSeekException mapped = mapHttpError(error);
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        } catch (ResourceAccessException error) {
            DeepSeekException mapped = new DeepSeekException(DeepSeekException.CODE_NETWORK_ERROR, "AI 服务连接超时，请稍后重试");
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        } catch (RestClientException error) {
            DeepSeekException mapped = new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务暂时不可用，请稍后重试");
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        }
    }

    /**
     * 流式对话：逐增量回调，返回拼好的正文。
     *
     * <p>与非流式的 {@link #chat(List, ChatOptions)} 共用同一套校验、错误映射与错误码，
     * 差别只在传输方式。"流"在这里是**真的 token 级流**——不是"若干个阶段事件 + 结尾一次性给正文"。</p>
     *
     * <p><b>思考与正文分两路回调</b>（{@code reasoning=true} 表示思考）：实测
     * {@code deepseek-flash} 的帧序是 {@code reasoning_content} 先、{@code content} 后，
     * 两者在同一帧里可以一个为 {@code null}、另一个有值。思考过程本身就是推演的展示素材，
     * 所以不丢，但调用方可以决定不展示。</p>
     *
     * <p><b>截断仍按失败处理</b>：收到 {@code finish_reason=length} 时抛
     * {@code AI_OUTPUT_TRUNCATED}，与 {@code parseResponse} 同一口径。理由一致——
     * 半截方案被当成完整方案交给用户，是这条链路上最需要避免的失败。调用方据此降级重试。</p>
     *
     * @param onDelta 增量接收方；返回 {@code false} 要求中止（如客户端已断开）
     * @return 拼好的正文（不含思考）
     */
    public String chatStream(List<ChatMessage> messages, ChatOptions options, DeltaConsumer onDelta) {
        ChatOptions actualOptions = options == null ? ChatOptions.simulating() : options;
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        if (streamingRestTemplate == null) {
            throw new DeepSeekException(DeepSeekException.CODE_STREAM_UNAVAILABLE, "流式通道未配置，请改用非流式");
        }
        try {
            if (!properties.hasApiKey()) {
                throw new DeepSeekException(DeepSeekException.CODE_NOT_CONFIGURED, "AI 服务尚未配置，请联系管理员设置 API Key");
            }
            validateMessages(messages);

            final String upstreamUrl = normalizedBaseUrl() + "/chat/completions";
            final StreamAccumulator accumulator = new StreamAccumulator();
            streamingRestTemplate.execute(upstreamUrl, HttpMethod.POST,
                    new RequestCallback() {
                        public void doWithRequest(ClientHttpRequest request) throws IOException {
                            writeStreamRequest(request, messages, actualOptions);
                        }
                    },
                    new ResponseExtractor<Void>() {
                        public Void extractData(ClientHttpResponse response) throws IOException {
                            consumeStream(response, accumulator, onDelta);
                            return null;
                        }
                    });

            // 截断按失败报，**即便正文非空**：半截方案被当成完整方案交给用户，
            // 是这条链路上最需要避免的失败。与非流式 parseResponse 同一口径。
            if (accumulator.truncated) {
                throw new DeepSeekException(DeepSeekException.CODE_OUTPUT_TRUNCATED,
                        "AI 输出被截断（推理占用了输出预算），请提高 max_tokens 或关闭思考模式");
            }
            if (accumulator.content.length() == 0) {
                // 正文空的两种成因分开报：思考吃光预算 / 上游真的异常。
                // 截断已在上一步拦掉，所以这里只需再分"只回了思考"与"什么都没有"。
                if (accumulator.reasoning.length() > 0) {
                    throw new DeepSeekException(DeepSeekException.CODE_OUTPUT_TRUNCATED,
                            "AI 只输出了推理过程、正文为空，请提高 max_tokens 或关闭思考模式");
                }
                throw new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务返回格式异常，请稍后重试");
            }
            logOutcome(requestId, accumulator.cancelled ? "AI_STREAM_CANCELLED" : "AI_STREAM_SUCCESS", startedAt);
            return accumulator.content.toString();
        } catch (DeepSeekException error) {
            logOutcome(requestId, error.getCode(), startedAt);
            throw error;
        } catch (HttpStatusCodeException error) {
            DeepSeekException mapped = mapHttpError(error);
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        } catch (ResourceAccessException error) {
            DeepSeekException mapped = new DeepSeekException(DeepSeekException.CODE_NETWORK_ERROR, "AI 服务连接超时，请稍后重试");
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        } catch (RestClientException error) {
            DeepSeekException mapped = new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务暂时不可用，请稍后重试");
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        }
    }

    /** 流式累计状态。用具名类而不是一串数组，是因为这里要跨三个回调传递四个量。 */
    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private boolean truncated;
        private boolean cancelled;
    }

    /** 写流式请求体。 */
    private void writeStreamRequest(ClientHttpRequest request, List<ChatMessage> messages,
                                    ChatOptions options) throws IOException {
        HttpHeaders headers = request.getHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey().trim());
        // 不协商 gzip：一旦上游或中间代理返回 Content-Encoding: gzip，JDK 的 GZIPInputStream
        // 会预读填充，token 级流会退化成"停顿—一阵突发"，前端的打字机效果就没了。
        headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("stream", Boolean.TRUE);
        body.put("max_tokens", Integer.valueOf(options.maxTokens));
        if (options.thinking != null) {
            Map<String, Object> thinking = new LinkedHashMap<String, Object>();
            thinking.put("type", options.thinking.booleanValue() ? "enabled" : "disabled");
            body.put("thinking", thinking);
        }

        byte[] bytes = JSON.toJSONString(body).getBytes(StandardCharsets.UTF_8);
        headers.setContentLength(bytes.length);
        request.getBody().write(bytes);
    }

    /**
     * 读上游 SSE 并逐帧回调。
     *
     * <p>帧格式：{@code data: {json}} 行，空行分隔；结尾是 {@code data: [DONE]}；
     * 以 {@code :} 开头的是注释/心跳，要跳过。本接口不用 {@code event:} 命名事件。</p>
     *
     * <p><b>单个畸形帧跳过而不是终止整条流</b>：上游偶发的截断帧不该让用户丢掉整段已生成的推演。</p>
     */
    private void consumeStream(ClientHttpResponse response, StreamAccumulator accumulator,
                               DeltaConsumer onDelta) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == ':') {
                continue;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if ("[DONE]".equals(payload)) {
                return;
            }
            JSONObject chunk = parseChunk(payload);
            if (chunk == null) {
                continue;
            }
            // finish_reason 必须**先于**取 delta 检查：它出现在最后一帧，而那一帧的 delta
            // 常常是空对象、有的上游干脆不带 delta 键。若把它放在 "delta 为空就 continue" 之后，
            // 截断就永远不会被检出——而这正是最需要检出的情形（半截方案冒充完整方案）。
            if ("length".equals(finishReason(chunk))) {
                accumulator.truncated = true;
            }
            JSONObject delta = firstDelta(chunk);
            if (delta == null) {
                continue;
            }
            String reasoningDelta = text(delta, "reasoning_content");
            if (!reasoningDelta.isEmpty()) {
                accumulator.reasoning.append(reasoningDelta);
                if (!onDelta.accept(reasoningDelta, true)) {
                    accumulator.cancelled = true;
                    return;
                }
            }
            String contentDelta = text(delta, "content");
            if (!contentDelta.isEmpty()) {
                accumulator.content.append(contentDelta);
                if (!onDelta.accept(contentDelta, false)) {
                    accumulator.cancelled = true;
                    return;
                }
            }
        }
    }

    private JSONObject parseChunk(String payload) {
        try {
            return JSON.parseObject(payload);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private JSONObject firstChoice(JSONObject chunk) {
        JSONArray choices = chunk.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getJSONObject(0);
    }

    private JSONObject firstDelta(JSONObject chunk) {
        JSONObject choice = firstChoice(chunk);
        return choice == null ? null : choice.getJSONObject("delta");
    }

    private String finishReason(JSONObject chunk) {
        JSONObject choice = firstChoice(chunk);
        if (choice == null) {
            return null;
        }
        String reason = choice.getString("finish_reason");
        return reason == null ? "" : reason;
    }

    private String text(JSONObject object, String key) {
        String value = object.getString(key);
        return value == null ? "" : value;
    }

    private void validateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new DeepSeekException(DeepSeekException.CODE_INVALID_REQUEST, "请提供至少一条 AI 消息");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw new DeepSeekException(DeepSeekException.CODE_INVALID_REQUEST, "AI 消息数量不能超过 30 条");
        }
        for (ChatMessage message : messages) {
            if (message == null || message.getRole() == null || !ALLOWED_ROLES.contains(message.getRole())) {
                throw new DeepSeekException(DeepSeekException.CODE_INVALID_REQUEST, "AI 消息角色不合法");
            }
            String content = message.getContent();
            if (content == null || content.trim().isEmpty()) {
                throw new DeepSeekException(DeepSeekException.CODE_INVALID_REQUEST, "AI 消息内容不能为空");
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new DeepSeekException(DeepSeekException.CODE_INVALID_REQUEST, "单条 AI 消息不能超过 12000 个字符");
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private AiChatResponse parseResponse(Map response) {
        if (response == null || !(response.get("choices") instanceof List)) {
            throw new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务返回格式异常，请稍后重试");
        }
        List choices = (List) response.get("choices");
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            throw new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务返回格式异常，请稍后重试");
        }
        Map choice = (Map) choices.get(0);
        Object messageObject = choice.get("message");
        if (!(messageObject instanceof Map)) {
            throw new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务返回格式异常，请稍后重试");
        }
        Map message = (Map) messageObject;
        Object contentObject = message.get("content");
        if (!(contentObject instanceof String) || ((String) contentObject).trim().isEmpty()) {
            // 空 content 有两种常见成因，必须与"上游格式异常"分开报，否则会把"被截断"误导成"服务坏了"：
            // 1) finish_reason=length：输出预算被耗尽；
            // 2) 只回了 reasoning_content：思考模式把预算全花在推理上，正文没来得及输出。
            boolean truncated = "length".equals(choice.get("finish_reason"));
            Object reasoning = message.get("reasoning_content");
            boolean reasoningOnly = reasoning instanceof String && !((String) reasoning).trim().isEmpty();
            if (truncated || reasoningOnly) {
                throw new DeepSeekException(DeepSeekException.CODE_OUTPUT_TRUNCATED,
                        "AI 输出被截断（推理占用了输出预算），请提高 max_tokens 或关闭思考模式");
            }
            throw new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务返回格式异常，请稍后重试");
        }
        Object modelObject = response.get("model");
        String model = modelObject instanceof String ? (String) modelObject : properties.getModel();
        return new AiChatResponse((String) contentObject, model);
    }

    private DeepSeekException mapHttpError(HttpStatusCodeException error) {
        int status = error.getRawStatusCode();
        if (status == 401 || status == 403) {
            return new DeepSeekException(DeepSeekException.CODE_UPSTREAM_AUTH, "AI 服务认证失败，请检查服务器配置");
        }
        if (status == 429) {
            return new DeepSeekException(DeepSeekException.CODE_RATE_LIMIT, "AI 服务请求过于频繁，请稍后重试");
        }
        return new DeepSeekException(DeepSeekException.CODE_UPSTREAM_ERROR, "AI 服务暂时不可用，请稍后重试");
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.isEmpty()) {
            throw new DeepSeekException(DeepSeekException.CODE_NOT_CONFIGURED, "AI 服务地址尚未配置，请联系管理员");
        }
        return baseUrl;
    }

    private void logOutcome(String requestId, String outcome, long startedAt) {
        LOGGER.info("DeepSeek requestId={} outcome={} durationMs={}", requestId, outcome,
                System.currentTimeMillis() - startedAt);
    }
}
