package com.example.Ece.agent.tool;

import com.example.Ece.agent.agri.AgriEnvironmentService;
import com.example.Ece.agent.rag.CitationFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.example.Ece.config.AgriEnvironmentProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 当地农情工具的测试。
 *
 * <p>盯死一条：**模拟地绝不能被讲成当地实测**。来源标识必须在摘要最前 160 字内——
 * 模型在证据块里只看得到这么多，一旦被截掉，它就会把一份演示档案当成当地天气说出去。</p>
 */
class LocalEnvironmentToolTest {

    private AgriEnvironmentProperties configured() {
        AgriEnvironmentProperties properties = new AgriEnvironmentProperties();
        properties.setApiUrl("http://weather.example.com/api");
        properties.setAppId("app-1");
        properties.setAppSecret("secret-1");
        properties.setCityId("101270101");
        return properties;
    }

    private AgriEnvironmentService liveService(String body) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), eq(String.class)))
                .thenReturn(new ResponseEntity<String>(body, HttpStatus.OK));
        return new AgriEnvironmentService(configured(), restTemplate);
    }

    private AgriEnvironmentService simulatedService() {
        AgriEnvironmentProperties properties = configured();
        properties.setDemoMode(true);
        return new AgriEnvironmentService(properties, mock(RestTemplate.class));
    }

    private LocalEnvironmentTool tool(AgriEnvironmentService service) {
        return new LocalEnvironmentTool(service, new PlatformSnapshotEvidence(new CitationFormatter()));
    }

    private Map<String, Object> run(AgriEnvironmentService service) throws Exception {
        return tool(service).execute(new LinkedHashMap<String, Object>());
    }

    @Test
    void simulatedCaptureIsLabelledAsNotMeasuredAtTheVeryFront() throws Exception {
        Map<String, Object> output = run(simulatedService());
        String summary = String.valueOf(output.get("summary"));

        assertTrue(summary.startsWith("演示用模拟地（非实测）"),
                "来源标识必须在最前，否则会被 160 字的证据块截掉。实得：" + summary);
        assertTrue(summary.length() <= 160, "摘要 " + summary.length() + " 字超出证据块预算");
        assertEquals("SIMULATED_LOCATION", output.get("source"));
        // 刻意不断言 executed 字段：本工具是 READ_ONLY，压根没有执行语义，
        // 那个字段只有 DRAFT 工具（处方、报告）才需要用来声明"没执行"。
    }

    @Test
    void observedCaptureIsLabelledAsRealAndDoesNotCarryTheNotMeasuredWarning() throws Exception {
        Map<String, Object> output = run(liveService("{\"city\":\"成都\",\"tem\":22,\"humidity\":72}"));
        String summary = String.valueOf(output.get("summary"));

        assertTrue(summary.startsWith("实时观测"), "实时取到时应标为实时观测。实得：" + summary);
        assertFalse(summary.contains("非实测"), "实时观测不该带「非实测」字样");
        assertEquals("OBSERVED", output.get("source"));
    }

    @Test
    void toolPassesTheEvidenceGateSoTheAgentCanAnswerFromIt() throws Exception {
        Map<String, Object> output = run(simulatedService());
        assertEquals(Boolean.FALSE, output.get("lowScore"));
        assertFalse(((List<?>) output.get("items")).isEmpty(), "守门需要非空的 ScoredChunk 列表");
        assertFalse(((List<?>) output.get("citations")).isEmpty(), "编排层需要 citations 才会置 reliableEvidence");
    }

    @Test
    void citationIdentifiesTheSourceKindSoItCanBeAudited() throws Exception {
        Map<String, Object> output = run(simulatedService());
        Map<?, ?> citation = (Map<?, ?>) ((List<?>) output.get("citations")).get(0);
        String version = String.valueOf(citation.get("sourceVersion"));
        assertTrue(version.contains("source=SIMULATED_LOCATION"), "版本串应带来源类型");
        assertTrue(version.contains("at="), "版本串应带取数时间");
        assertTrue(String.valueOf(citation.get("sourceName")).contains("模拟地"), "出处须写明是模拟地");
    }

    @Test
    void unavailableFieldsAreDisclosedRatherThanDefaultedToZero() throws Exception {
        Map<String, Object> output = run(simulatedService());
        String note = String.valueOf(output.get("note"));
        assertTrue(String.valueOf(output.get("summary")).contains("不提供光照"),
                "应说明该数据源不提供光照，避免调用方把缺字段读成 0");
        assertFalse(note.isEmpty(), "必须带来源说明");
    }

    @Test
    void toolMetadataIsReadOnlyAndDeclaresTheSourceAmbiguity() {
        LocalEnvironmentTool tool = tool(simulatedService());
        assertEquals(ToolPermission.READ_ONLY, tool.permission());
        assertEquals("platform.localEnvironment", tool.name());
        assertTrue(tool.description().contains("不得混同"), "工具描述里就要提醒来源可能不同");
    }
}
