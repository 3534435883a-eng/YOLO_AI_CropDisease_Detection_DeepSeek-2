package com.example.Ece.agent.tool;

import com.example.Ece.agent.agri.AgriEnvironmentObservation;
import com.example.Ece.agent.agri.AgriEnvironmentService;
import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当地农情环境工具：让智能体"看得见棚外"。
 *
 * <p>在此之前智能体只能读到**仿真运行**里的棚内状态，答不出"现在当地什么天气、适不适合通风"
 * 这类必须结合棚外条件的问题。本工具把当地实时环境接给它——取不到时退回演示用模拟地，
 * 但**来源标识一定跟着走**，模型因此不可能把模拟地讲成当地实测。</p>
 *
 * <p>走标准证据通道（产出 citations 且非 lowScore），否则编排层的完成条件不认、只能拒答。
 * 摘要把来源标识放在最前 160 字内——模型在证据块里只看得到这么多。</p>
 */
@Component
public class LocalEnvironmentTool implements AgentTool {

    public static final String NAME = "platform.localEnvironment";

    private static final String TITLE = "当地农情环境";

    private final AgriEnvironmentService environmentService;
    private final PlatformSnapshotEvidence evidence;

    public LocalEnvironmentTool(AgriEnvironmentService environmentService, PlatformSnapshotEvidence evidence) {
        this.environmentService = environmentService;
        this.evidence = evidence;
    }

    public String name() {
        return NAME;
    }

    public String description() {
        return "读取种植地当前的当地环境：温度、湿度、天气现象、风向风力，并标明数据来源。"
                + "用于回答「现在外面什么天气」「这种天气要不要通风/盖帘」「适不适合喷药」这类需要棚外条件的问题。"
                + "注意：来源可能是实时观测，也可能是演示用模拟地，输出里会明确标注，不得混同。";
    }

    public ToolPermission permission() {
        return ToolPermission.READ_ONLY;
    }

    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }

    public Map<String, Object> execute(Map<String, Object> input) throws ToolException {
        AgriEnvironmentObservation observation = environmentService.capture();
        String summary = summaryText(observation);

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("location", observation.getLocation());
        output.put("temperatureC", Double.valueOf(observation.getTemperatureC()));
        output.put("humidityPct", Double.valueOf(observation.getHumidityPct()));
        output.put("condition", observation.getCondition());
        output.put("wind", observation.getWind());
        output.put("observedAt", observation.getObservedAt());
        output.put("source", observation.getSource().name());
        output.put("sourceLabel", observation.getSource().getLabel());
        output.put("sourceName", observation.getSourceName());
        output.put("note", observation.getNote());
        output.put("summary", summary);

        // 可核对引用：来源类型与取数时间是回查标识。
        List<ScoredChunk> items = evidence.items(
                Math.abs(observation.getSource().name().hashCode()), 0, "当地", TITLE, summary,
                "AGR-" + observation.getSource().name(),
                observation.getSourceName(),
                "source=" + observation.getSource().name() + ";at=" + observation.getObservedAt());
        output.put("items", items);
        output.put("citations", evidence.citations(items));
        output.put("lowScore", Boolean.FALSE);
        output.put("stepSummary", NAME + " 取得当地环境（" + observation.getSource().getLabel() + "）");
        return output;
    }

    /**
     * 摘要正文：**来源标识必须在最前**。
     *
     * <p>模型在证据块里只看得到前 160 字，而"这是模拟地不是实测"是本工具最不能丢的信息——
     * 它若被截掉，模型就会把一份演示档案当成当地天气讲出去。</p>
     */
    private String summaryText(AgriEnvironmentObservation observation) {
        StringBuilder text = new StringBuilder();
        text.append(observation.getSource().getLabel()).append("：")
                .append(observation.getLocation()).append(' ')
                .append(format(observation.getTemperatureC(), 1)).append("℃ 湿度")
                .append(format(observation.getHumidityPct(), 0)).append('%');
        if (!observation.getCondition().isEmpty()) {
            text.append(' ').append(observation.getCondition());
        }
        if (!observation.getWind().isEmpty()) {
            text.append(' ').append(observation.getWind());
        }
        text.append("；取自 ").append(observation.getObservedAt()).append('。');
        if (observation.getSource() == AgriEnvironmentObservation.Source.SIMULATED_LOCATION) {
            text.append("非实测，不得当作当地观测引用。");
        }
        text.append("该源不提供光照/PPFD。");
        return text.toString();
    }

    private String format(double value, int scale) {
        return String.format("%." + Math.max(0, scale) + "f", Double.valueOf(value));
    }
}
