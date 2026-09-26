package com.example.Ece.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 农情（当地实时环境）数据源的配置。
 *
 * <p>分两种取数方式，**由配置决定，且取到的数据必须标明是哪一种**：</p>
 * <ul>
 *   <li><b>实时捕捉</b>：调用可配置的天气数据服务（写法对齐公开的天气 API，默认按 tianqiapi 的字段约定解析）。
 *       需要 appId/appSecret/cityId；任一项缺失即视为未配置。</li>
 *   <li><b>演示用模拟地</b>：{@code demoMode=true} 或未配置实时源、或实时调用失败时，返回一个
 *       **明确标注为模拟**的固定地点档案。演示不依赖网络与配额。</li>
 * </ul>
 *
 * <p><b>不把模拟冒充实测</b>：两种来源在返回值里以 {@code source} 字段区分
 * （{@code OBSERVED} / {@code SIMULATED_LOCATION}），面板与报告都必须显示来源。
 * 这与项目对仿真的处理一致——仿真值可以用于推演，但不能当作实测。</p>
 */
@ConfigurationProperties(prefix = "agent.agri.environment")
public class AgriEnvironmentProperties {

    /** 是否启用实时捕捉。关闭后一律走模拟地。 */
    private boolean enabled = true;
    /** 天气数据服务地址（按 tianqiapi 的字段约定解析，可换成任何同形接口）。 */
    private String apiUrl = "http://www.tianqiapi.com/api";
    /** 服务商分配的 appId；与 appSecret 任一为空即视为未配置。 */
    private String appId = "";
    /** 服务商分配的 appSecret。 */
    private String appSecret = "";
    /** 城市编码（tianqiapi 约定，如成都 101270101）。 */
    private String cityId = "";
    /** 超时（毫秒）。取数失败要走模拟地，不能把演示卡在网络超时上。 */
    private int timeoutMs = 3000;
    /** 强制演示模式：跳过实时捕捉，直接返回模拟地。 */
    private boolean demoMode = false;

    // ---- 演示用模拟地档案（示例参数，非实测） ----
    private String simulatedLocationName = "成都（演示用模拟地）";
    private double simulatedTemperatureC = 24.0;
    private double simulatedHumidityPct = 78.0;
    private String simulatedCondition = "多云";
    private String simulatedWind = "东北风 2 级";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getCityId() {
        return cityId;
    }

    public void setCityId(String cityId) {
        this.cityId = cityId;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    public void setDemoMode(boolean demoMode) {
        this.demoMode = demoMode;
    }

    public String getSimulatedLocationName() {
        return simulatedLocationName;
    }

    public void setSimulatedLocationName(String simulatedLocationName) {
        this.simulatedLocationName = simulatedLocationName;
    }

    public double getSimulatedTemperatureC() {
        return simulatedTemperatureC;
    }

    public void setSimulatedTemperatureC(double simulatedTemperatureC) {
        this.simulatedTemperatureC = simulatedTemperatureC;
    }

    public double getSimulatedHumidityPct() {
        return simulatedHumidityPct;
    }

    public void setSimulatedHumidityPct(double simulatedHumidityPct) {
        this.simulatedHumidityPct = simulatedHumidityPct;
    }

    public String getSimulatedCondition() {
        return simulatedCondition;
    }

    public void setSimulatedCondition(String simulatedCondition) {
        this.simulatedCondition = simulatedCondition;
    }

    public String getSimulatedWind() {
        return simulatedWind;
    }

    public void setSimulatedWind(String simulatedWind) {
        this.simulatedWind = simulatedWind;
    }

    /** 实时源是否配置完整。缺任一项就不能去调——否则只会拿到一个 4xx。 */
    public boolean hasLiveSource() {
        return isNotBlank(apiUrl) && isNotBlank(appId) && isNotBlank(appSecret) && isNotBlank(cityId);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
