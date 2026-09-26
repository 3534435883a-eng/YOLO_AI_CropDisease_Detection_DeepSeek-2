package com.example.Ece.agent.agri;

import com.example.Ece.config.AgriEnvironmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 当地农情取数的测试。
 *
 * <p>核心不变量：**来源必须如实标注**。取到实时数据要说 OBSERVED，
 * 退回模拟地要说 SIMULATED_LOCATION 并给出原因——把写死的演示档案讲成当地实测，
 * 是本项目最不能犯的错。因此这里把四类"走到模拟地"的路径与一类"真的取到"的路径都覆盖到。</p>
 *
 * <p>不联网：RestTemplate 由测试注入，实时取数的成功与各种失败分支都是构造出来的。</p>
 */
class AgriEnvironmentServiceTest {

    private AgriEnvironmentProperties fullyConfigured() {
        AgriEnvironmentProperties properties = new AgriEnvironmentProperties();
        properties.setApiUrl("http://weather.example.com/api");
        properties.setAppId("app-1");
        properties.setAppSecret("secret-1");
        properties.setCityId("101270101");
        properties.setSimulatedLocationName("成都（演示用模拟地）");
        properties.setSimulatedTemperatureC(24.0);
        properties.setSimulatedHumidityPct(78.0);
        return properties;
    }

    private RestTemplate returning(String body) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), eq(String.class)))
                .thenReturn(new ResponseEntity<String>(body, HttpStatus.OK));
        return restTemplate;
    }

    private RestTemplate blowingUp() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));
        return restTemplate;
    }

    @Test
    void liveResponseYieldsObservedSourceNotASimulatedOne() {
        String body = "{\"city\":\"成都\",\"tem\":\"22\",\"humidity\":\"72\",\"wea\":\"多云\",\"win\":\"北风2级\"}";

        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(fullyConfigured(), returning(body)).capture();

        assertEquals(AgriEnvironmentObservation.Source.OBSERVED, observation.getSource());
        assertEquals("成都", observation.getLocation());
        assertEquals(22.0, observation.getTemperatureC());
        assertEquals(72.0, observation.getHumidityPct());
        assertTrue(observation.getSourceName().contains("weather.example.com"), "应记录取数来源");
        assertTrue(observation.getNote().contains("不提供光照"), "应说明该源没有哪些农情字段");
    }

    @Test
    void nestedDataObjectIsAlsoAccepted() {
        // 不同服务把数据放在 data 下；两种结构都要认
        String body = "{\"code\":200,\"data\":{\"city\":\"成都\",\"tem\":25,\"humidity\":60}}";

        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(fullyConfigured(), returning(body)).capture();

        assertEquals(AgriEnvironmentObservation.Source.OBSERVED, observation.getSource());
        assertEquals(25.0, observation.getTemperatureC());
        assertEquals(60.0, observation.getHumidityPct());
    }

    @Test
    void unconfiguredLiveSourceFallsBackWithTheReasonStated() {
        AgriEnvironmentProperties properties = fullyConfigured();
        properties.setAppId("");  // 四项缺一即视为未配置

        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(properties, blowingUp()).capture();

        assertEquals(AgriEnvironmentObservation.Source.SIMULATED_LOCATION, observation.getSource());
        assertTrue(observation.getNote().contains("未配置实时数据源"), "必须说明为什么走了模拟地");
        assertTrue(observation.getNote().contains("不是当地实测"), "必须标明非实测");
        assertEquals(24.0, observation.getTemperatureC());
    }

    @Test
    void demoModeSkipsTheLiveCallEvenWhenFullyConfigured() {
        AgriEnvironmentProperties properties = fullyConfigured();
        properties.setDemoMode(true);

        // 实时源会抛异常：若演示模式没有拦住，就会返回"取数失败"而不是"演示模式"
        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(properties, blowingUp()).capture();

        assertEquals(AgriEnvironmentObservation.Source.SIMULATED_LOCATION, observation.getSource());
        assertTrue(observation.getNote().contains("演示模式"), "演示模式下应说明是演示模式，而不是取数失败");
    }

    @Test
    void disabledCaptureDoesNotEvenTryTheNetwork() {
        AgriEnvironmentProperties properties = fullyConfigured();
        properties.setEnabled(false);

        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(properties, blowingUp()).capture();

        assertEquals(AgriEnvironmentObservation.Source.SIMULATED_LOCATION, observation.getSource());
        assertTrue(observation.getNote().contains("已在配置中关闭"));
    }

    @Test
    void networkFailureFallsBackInsteadOfBlowingUpTheDemo() {
        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(fullyConfigured(), blowingUp()).capture();

        assertEquals(AgriEnvironmentObservation.Source.SIMULATED_LOCATION, observation.getSource());
        assertTrue(observation.getNote().contains("实时取数失败"), "应说明失败原因，便于排查");
    }

    @Test
    void unrecognizableFieldsFallBackRatherThanReportingZeroesAsAnObservation() {
        // 字段约定与预期不符：若原样返回，会得到一条温度湿度都是 0 的"观测"，那比报错更糟
        String body = "{\"unexpected\":\"shape\"}";

        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(fullyConfigured(), returning(body)).capture();

        assertEquals(AgriEnvironmentObservation.Source.SIMULATED_LOCATION, observation.getSource(),
                "解析不出任何可用字段时必须退回模拟地，不得返回 0/0 的伪观测");
        assertTrue(observation.getNote().contains("实时取数失败"));
    }

    @Test
    void emptyBodyAlsoFallsBack() {
        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(fullyConfigured(), returning("")).capture();
        assertEquals(AgriEnvironmentObservation.Source.SIMULATED_LOCATION, observation.getSource());
    }

    @Test
    void simulatedSourceLabelAlwaysSaysItIsNotMeasured() {
        AgriEnvironmentObservation observation =
                new AgriEnvironmentService(fullyConfigured(), blowingUp()).capture();
        assertTrue(observation.getSource().getLabel().contains("非实测"),
                "模拟地的来源标签本身就要写明非实测，不能只写在附注里");
        assertFalse(observation.getSourceName().isEmpty());
    }
}
