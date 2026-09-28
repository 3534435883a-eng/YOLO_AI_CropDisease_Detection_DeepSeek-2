package com.example.Ece.agent.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.function.Predicate;

/**
 * 从模型的自由文本输出里取出 JSON 对象。
 *
 * <p>真实模型（尤其思考模式）常把 JSON 包在 ```json 围栏里，或在前面加一句"好的，下一步："。
 * 直接 {@code parseObject(raw.trim())} 会把这类输出整体判为不可解析——接入真实模型后
 * 这是最容易出现的故障。本类扫描第一个**花括号配对完整**的对象，且扫描时跳过字符串字面量内的括号，
 * 避免 {@code {"input":{"q":"a{b"}}} 这类内容被截断。</p>
 *
 * <p><b>为什么单独成类</b>：这段逻辑原先私有在 {@code AgentOrchestrator.parsePlan} 里，
 * 农事规划推演链路同样需要它。它的失败模式很隐蔽（截断后仍能得到一个"合法但缺字段"的 JSON，
 * 上层看到的只是"某个字段没解析出来"），复制第二份等于等着两份实现漂移。
 * 抽出来后两条链路共用，且行为由既有的 {@code AgentOrchestratorTest} 覆盖守住。</p>
 */
public final class JsonBlockScanner {

    private JsonBlockScanner() {
    }

    /** 取第一个能解析出来的 JSON 对象；没有则返回 {@code null}。 */
    public static JSONObject firstObject(String raw) {
        return firstObject(raw, null);
    }

    /**
     * 取第一个"能解析且被 {@code accept} 接受"的 JSON 对象；没有则返回 {@code null}。
     *
     * <p>筛选是**逐个候选**做的：某个 JSON 块解析成功但没通过 {@code accept} 时继续往后找，
     * 而不是整体判失败。模型在正式结论前先给一段示例 JSON 是常态，那种情况下第一个块
     * 解析得出来但字段不对。</p>
     *
     * @param accept 判定条件；传 {@code null} 表示不筛选
     */
    public static JSONObject firstObject(String raw, Predicate<JSONObject> accept) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = matchingBrace(text, start);
            if (end > start) {
                JSONObject object = tryParse(text.substring(start, end + 1));
                if (object != null && (accept == null || accept.test(object))) {
                    return object;
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /**
     * 从 {@code start} 处的 {@code &#123;} 开始找与之配对的花括号下标，找不到返回 -1。
     *
     * <p>扫描时跟踪字符串字面量状态与转义，字符串内的花括号不计入深度——否则
     * {@code {"q":"a{b"}} 会在字符串里的 {@code &#123;} 处提前加深度，得到的区间不完整。</p>
     */
    public static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static JSONObject tryParse(String candidate) {
        try {
            return JSON.parseObject(candidate);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
