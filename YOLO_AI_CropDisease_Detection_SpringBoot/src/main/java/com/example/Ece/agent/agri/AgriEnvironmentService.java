package com.example.Ece.agent.agri;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.config.AgriEnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 当地农情环境取数：实时捕捉优先，取不到就退回**演示用模拟地**，并且如实标明是哪一种。
 *
 * <p><b>为什么一定要有模拟地兜底</b>：演示现场常常没有网络、或天气服务的免费配额用尽
 * （项目根目录的 {@code 3.更换天气api以及修改城市.txt} 就是为配额耗尽准备的更换教程）。
 * 若取不到就报错，演示会在最关键的时候开天窗。退回模拟地不是偷懒，但**必须标明**——
 * 否则就是把一份写死的档案当成当地实时数据讲出去。</p>
 *
 * <p><b>字段解析保持宽容</b>：不同天气服务的键名不完全一致（{@code tem}/{@code temp}、
 * {@code humidity}/{@code sd}…），这里按候选键依次尝试，取不到就留空。
 * <b>不猜</b>：拿不到光照就不编一个 PPFD，而是明确说明该源不提供。</p>
 */
@Service
public class AgriEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(AgriEnvironmentService.class);

    private static final DateTimeFormatter CAPTURED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 候选键：不同天气服务的字段名不一致，按顺序尝试，取不到就留空而不是猜。 */
    private static final String[] TEMPERATURE_KEYS = {"tem", "temp", "temperature", "tempf"};
    private static final String[] HUMIDITY_KEYS = {"humidity", "sd", "hum"};
    private static final String[] CONDITION_KEYS = {"wea", "weather", "condition", "wea_day"};
    private static final String[] WIND_KEYS = {"win", "wind", "winddirection", "win_speed"};
    private static final String[] LOCATION_KEYS = {"city", "cityname", "location", "area"};

    private final AgriEnvironmentProperties properties;
    private final RestTemplate restTemplate;

    /**
     * Spring 用的构造器：自建带超时的 RestTemplate（与 {@code HttpEmbeddingClient} 同一写法）。
     *
     * <p><b>多构造器必须显式 {@code @Autowired}</b>：{@code docs/knowledge-entity-lexicon.md} §五 记录过
     * 漏标导致 Spring 找不到无参构造器、**应用整体启动失败**的教训，且只有全上下文测试能抓到。
     * 这里两个构造器的参数个数不同，Spring 本可推断，但仍显式标注以免重蹈覆辙。</p>
     */
    @Autowired
    public AgriEnvironmentService(AgriEnvironmentProperties properties) {
        this(properties, buildRestTemplate(properties));
    }

    /** 便于单元测试：注入可控的 RestTemplate，从而在不联网的情况下覆盖实时取数与各种失败分支。 */
    public AgriEnvironmentService(AgriEnvironmentProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate buildRestTemplate(AgriEnvironmentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 取数失败要走模拟地，不能把演示卡在网络超时上——所以超时设得短。
        factory.setConnectTimeout(Math.max(500, properties.getTimeoutMs()));
        factory.setReadTimeout(Math.max(500, properties.getTimeoutMs()));
        return new RestTemplate(factory);
    }

    /** 捕捉当前环境。实时源可用时取实时，否则返回模拟地并说明原因。 */
    public AgriEnvironmentObservation capture() {
        if (!properties.isEnabled()) {
            return simulated("实时捕捉已在配置中关闭");
        }
        if (properties.isDemoMode()) {
            return simulated("当前为演示模式");
        }
        if (!properties.hasLiveSource()) {
            return simulated("未配置实时数据源（需要 apiUrl / appId / appSecret / cityId 四项齐全）");
        }
        try {
            return fetchLive();
        } catch (RuntimeException error) {
            log.warn("当地农情实时取数失败，改用演示用模拟地：{}", error.toString());
            return simulated("实时取数失败：" + error.getClass().getSimpleName());
        }
    }

    private AgriEnvironmentObservation fetchLive() {
        String url = UriComponentsBuilder.fromHttpUrl(properties.getApiUrl())
                .queryParam("appid", properties.getAppId())
                .queryParam("appsecret", properties.getAppSecret())
                .queryParam("cityid", properties.getCityId())
                .queryParam("version", "v1")
                .build(true).toUriString();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        String body = response.getBody();
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalStateException("EMPTY_BODY");
        }
        JSONObject json = JSON.parseObject(body);
        // 部分服务把数据放在 data 下；两种都试。
        JSONObject payload = json.containsKey("data") && json.getJSONObject("data") != null
                ? json.getJSONObject("data") : json;

        String location = firstString(payload, LOCATION_KEYS);
        Double temperature = firstDouble(payload, TEMPERATURE_KEYS);
        Double humidity = firstDouble(payload, HUMIDITY_KEYS);
        if (temperature == null && humidity == null) {
            // 一个可用字段都没解析出来，说明字段约定与预期不符——退回模拟地并留痕，
            // 而不是返回一条温度湿度都是 0 的"观测"。
            throw new IllegalStateException("NO_RECOGNIZABLE_FIELDS");
        }
        return new AgriEnvironmentObservation(
                location.isEmpty() ? "城市编码 " + properties.getCityId() : location,
                temperature == null ? 0.0 : temperature.doubleValue(),
                humidity == null ? 0.0 : humidity.doubleValue(),
                firstString(payload, CONDITION_KEYS),
                firstString(payload, WIND_KEYS),
                LocalDateTime.now().format(CAPTURED_AT),
                AgriEnvironmentObservation.Source.OBSERVED,
                "外部天气数据服务（" + hostOf(properties.getApiUrl()) + "）",
                "实时取数成功。该源不提供光照/PPFD 等农情字段，报告与推演不得据此外推。");
    }

    private AgriEnvironmentObservation simulated(String reason) {
        return new AgriEnvironmentObservation(
                properties.getSimulatedLocationName(),
                properties.getSimulatedTemperatureC(),
                properties.getSimulatedHumidityPct(),
                properties.getSimulatedCondition(),
                properties.getSimulatedWind(),
                LocalDateTime.now().format(CAPTURED_AT),
                AgriEnvironmentObservation.Source.SIMULATED_LOCATION,
                "演示用模拟地档案（示例参数）",
                reason + "。本行数值为**演示用固定档案，不是当地实测**，不得当作观测数据引用。");
    }

    private String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException ignored) {
            return url;
        }
    }

    private String firstString(JSONObject payload, String[] keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private Double firstDouble(JSONObject payload, String[] keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Number) {
                return Double.valueOf(((Number) value).doubleValue());
            }
            if (value instanceof String) {
                try {
                    return Double.valueOf(Double.parseDouble(((String) value).trim()));
                } catch (NumberFormatException ignored) {
                    // 换下一个候选键
                }
            }
        }
        return null;
    }
}
