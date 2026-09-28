package com.example.Ece.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.agent.support.JsonBlockScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型推演输出解析后的形状：正文 + 结构化动作清单。
 *
 * <p>解析只做"拆包"，不做"补救"：模型没写分节就如实反映出缺节，没给 JSON 块就标
 * {@code structuredParsed=false}。**不替模型补内容**——补出来的东西和模型真写的在用户眼里一样，
 * 但责任归属完全不同。</p>
 */
public class DeductionOutput {

    private final String markdown;
    private final boolean bannerInjected;
    private final JSONObject structured;
    private final List<String> sections;

    private DeductionOutput(String markdown, boolean bannerInjected, JSONObject structured,
                            List<String> sections) {
        this.markdown = markdown;
        this.bannerInjected = bannerInjected;
        this.structured = structured;
        this.sections = Collections.unmodifiableList(new ArrayList<String>(sections));
    }

    /** 正文（含推演声明，且已剔除 JSON 块，避免前端把结构化数据重复渲染一遍）。 */
    public String getMarkdown() { return markdown; }

    /**
     * 声明是否是**服务端补的**。
     *
     * <p>要如实记录：它既是"模型没按格式输出"的信号（用于评测与提示词迭代），
     * 也提醒运维——若这个值长期为 true，说明提示词没能把格式约束住。</p>
     */
    public boolean isBannerInjected() { return bannerInjected; }

    /** 结构化动作清单；模型没给可解析的 JSON 块时为 {@code null}。 */
    public JSONObject getStructured() { return structured; }

    public boolean isStructuredParsed() { return structured != null; }

    /** 正文里实际出现的二级标题，按出现顺序。用于"分节骨架是否齐全"的检查。 */
    public List<String> getSections() { return sections; }

    /**
     * 拆包。
     *
     * @param raw 模型原始输出
     */
    public static DeductionOutput parse(String raw) {
        String text = raw == null ? "" : raw.trim();

        // 声明可能出现在开头，也可能根本没写。先剥掉，再统一放到最前面——
        // 模型偶尔会把声明写在中间或结尾，那种位置起不到"进门先看见"的作用。
        boolean hadBanner = text.contains(DeductionPrompts.SIMULATION_BANNER);
        String body = text.replace(DeductionPrompts.SIMULATION_BANNER, "").trim();

        String fencedJson = extractFencedJson(body);
        if (fencedJson != null) {
            // 结构化数据在前端单独渲染成清单，正文里就不再重复一份原始 JSON。
            body = body.replace(fencedJson, "").replace(DeductionPrompts.JSON_FENCE, "").trim();
        }
        JSONObject structured = parseJson(fencedJson);
        if (structured == null) {
            // 没围栏就退回通用扫描——模型有时忘写 ```json 直接给裸 JSON。
            structured = JsonBlockScanner.firstObject(body, new java.util.function.Predicate<JSONObject>() {
                public boolean test(JSONObject candidate) {
                    return candidate.containsKey("actions") || candidate.containsKey("conclusion");
                }
            });
            if (structured != null) {
                body = body.replace(structured.toJSONString(), "").trim();
            }
        }

        // 去掉可能残留的孤立围栏标记
        body = body.replace(DeductionPrompts.JSON_FENCE, "").trim();

        String markdown = DeductionPrompts.SIMULATION_BANNER + "\n\n" + body;
        return new DeductionOutput(markdown.trim(), !hadBanner, structured, collectSections(body));
    }

    /** 取 ```json 围栏里的内容。用花括号配对而不是"找下一个 ```"，避免正文里出现围栏时截错。 */
    private static String extractFencedJson(String text) {
        int fence = text.indexOf(DeductionPrompts.JSON_FENCE);
        if (fence < 0) {
            return null;
        }
        int start = text.indexOf('{', fence);
        if (start < 0) {
            return null;
        }
        int end = JsonBlockScanner.matchingBrace(text, start);
        return end > start ? text.substring(start, end + 1) : null;
    }

    private static JSONObject parseJson(String candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            return JSON.parseObject(candidate);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static List<String> collectSections(String body) {
        List<String> sections = new ArrayList<String>();
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                sections.add(trimmed.substring(3).trim());
            }
        }
        return sections;
    }
}
