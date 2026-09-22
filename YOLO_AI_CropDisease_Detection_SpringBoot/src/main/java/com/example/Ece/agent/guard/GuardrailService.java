package com.example.Ece.agent.guard;

import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安全守门（spec §11 六条规则）。判定顺序即优先级：
 * ② 设备自动执行声明 > ③ 无引用不得给专业结论 > ① 涉药补人工确认 > ④ 降级披露 > ⑤ 仿真不得称实测 > ⑥ 超长截断。
 *
 * 设计立场：宁可回答保守一点，也不让系统说出"我已经替你打了药"这类越权结论。
 */
@Component
public class GuardrailService {

    public static final String REASON_NO_EVIDENCE = "NO_EVIDENCE";
    public static final String REASON_AUTO_EXECUTION_CLAIM = "AUTO_EXECUTION_CLAIM";

    private static final int MAX_ANSWER_CHARS = 4000;

    private static final String[] AUTO_EXECUTION_PATTERNS = {
            "已自动", "已经自动", "自动开启", "自动执行", "已执行", "已开启", "已启动", "已替你"
    };

    private static final String[] PESTICIDE_KEYWORDS = {
            "药剂", "农药", "用药", "喷施", "喷洒", "拌种", "倍液", "有效成分",
            "多菌灵", "代森锰锌", "百菌清", "戊唑醇", "嘧霉胺", "苯醚甲环唑", "异菌脲", "腐霉利", "霜脲氰"
    };

    private static final String[] MEASURED_CLAIM_PATTERNS = {
            "实测", "现场检测到", "实测数据", "实际测量", "现场实测"
    };

    private static final String MANUAL_CONFIRM_NOTE =
            "\n（以上涉及药剂或投入品使用，需人工确认后执行，并遵循当地登记与用药规范）";
    private static final String DEGRADED_NOTE =
            "\n（本次为降级检索：仅关键词匹配，请人工核对出处）";
    private static final String TRUNCATED_NOTE = "\n（回答过长已截断，请追问具体环节）";

    public GuardrailCheck check(String answer, List<ScoredChunk> citations, boolean degraded) {
        String text = answer == null ? "" : answer.trim();
        boolean hasCitation = citations != null && !citations.isEmpty();

        // 规则②：不得声称已经执行设备动作或代为处置
        for (String pattern : AUTO_EXECUTION_PATTERNS) {
            if (text.contains(pattern)) {
                return GuardrailCheck.reject(REASON_AUTO_EXECUTION_CLAIM, text);
            }
        }

        // 规则③：没有引用就不得给出含药剂/用量的专业结论
        if (!hasCitation && containsAny(text, PESTICIDE_KEYWORDS)) {
            return GuardrailCheck.reject(REASON_NO_EVIDENCE, text);
        }

        String rewritten = text;

        // 规则①：涉药必须显式提示人工确认
        if (containsAny(rewritten, PESTICIDE_KEYWORDS) && !rewritten.contains("需人工确认")) {
            rewritten = rewritten + MANUAL_CONFIRM_NOTE;
        }

        // 规则④：降级必须披露
        if (degraded && !rewritten.contains("降级")) {
            rewritten = rewritten + DEGRADED_NOTE;
        }

        // 规则⑤：仿真不得表述为实测
        for (String pattern : MEASURED_CLAIM_PATTERNS) {
            if (rewritten.contains(pattern)) {
                rewritten = rewritten.replace(pattern, "推演显示");
            }
        }

        // 规则⑥：长度上限，防止刷屏与 token 失控
        if (rewritten.length() > MAX_ANSWER_CHARS) {
            rewritten = rewritten.substring(0, MAX_ANSWER_CHARS) + TRUNCATED_NOTE;
        }

        return rewritten.equals(text) ? GuardrailCheck.allow(text) : GuardrailCheck.rewrite(rewritten);
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
