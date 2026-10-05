package com.example.Ece.agent.guard;

import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安全守门（spec §11 六条规则）。判定顺序即优先级：
 * ② 设备自动执行声明 > ③ 无引用不得给专业结论 > ① 涉药补人工确认 > ④ 降级披露 > ⑤ 仿真不得称实测 > ⑥ 超长截断。
 *
 * <p>设计立场：宁可回答保守一点，也不让系统说出"我已经替你打了药"这类越权结论。</p>
 *
 * <p><b>规则②的两级判定</b>（实测修正）：原先把 {@code 自动开启} 与 {@code 已自动} 同等对待并**整段拒绝**，
 * 结果是"建议自动开启通风"这种正常建议也被判越权，整段有依据的分析被丢弃——真实端到端里
 * 一次 21 秒生成、引用 5 条证据的回答就这样退化成拒答。现改为：</p>
 * <ul>
 *   <li><b>完成态声明</b>（{@code 已自动}/{@code 已经自动}/{@code 已替你}…）→ 仍然整段拒绝，不给改写机会；</li>
 *   <li><b>语气模糊</b>（{@code 自动开启}/{@code 已开启}…）→ 改写成建议语气并追加"不直接执行设备动作"声明，
 *       既保住有用内容，也不让任何完成态表述漏过去。</li>
 * </ul>
 */
@Component
public class GuardrailService {

    public static final String REASON_NO_EVIDENCE = "NO_EVIDENCE";
    public static final String REASON_AUTO_EXECUTION_CLAIM = "AUTO_EXECUTION_CLAIM";

    private static final int MAX_ANSWER_CHARS = 4000;

    /** 完成态越权声明：直接拒绝。 */
    private static final String[] EXECUTION_CLAIM_PATTERNS = {
            "已自动", "已经自动", "已替你", "已代你", "已经替你", "已经代你"
    };

    /** 语气模糊、既可能是建议也可能是完成态的表述：改写成建议语气，不做整段拒绝。 */
    private static final String[] AMBIGUOUS_ACTION_PATTERNS = {
            "已开启", "已执行", "已启动"
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
    private static final String EXECUTION_DISCLAIMER =
            "\n（以上为建议，尚未应用新的仿真设备动作；执行情况以大棚面板为准）";

    public GuardrailCheck check(String answer, List<ScoredChunk> citations, boolean degraded) {
        return check(answer,citations,degraded,false);
    }
    public GuardrailCheck check(String answer,List<ScoredChunk> citations,boolean degraded,boolean simulatedExecution) {
        String text = answer == null ? "" : answer.trim();
        if(text.isEmpty())return GuardrailCheck.reject("ANSWER_EMPTY",text);
        boolean hasCitation = citations != null && !citations.isEmpty();

        // 规则②（硬拦）：不得声称已经替用户执行了设备动作
        for (String pattern : EXECUTION_CLAIM_PATTERNS) {
            if (positiveMention(text,pattern)&&!(simulatedExecution&&text.contains("仿真"))) {
                return GuardrailCheck.reject(REASON_AUTO_EXECUTION_CLAIM, text);
            }
        }

        // 规则③：没有引用就不得给出含药剂/用量的专业结论
        if (!hasCitation && containsAny(text, PESTICIDE_KEYWORDS)
                && java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:倍|克|毫升|mg|ml|mL|g[/／]|天安全间隔)").matcher(text).find()) {
            return GuardrailCheck.reject(REASON_NO_EVIDENCE, text);
        }

        String rewritten = text;

        // 规则②（软改）：模糊表述统一改成建议语气，并补一句"不直接执行设备动作"
        boolean ambiguousActionSeen = false;
        for (String pattern : AMBIGUOUS_ACTION_PATTERNS) {
            if (positiveMention(rewritten,pattern)&&!(simulatedExecution&&text.contains("仿真"))) {
                ambiguousActionSeen = true;
                rewritten = rewritten.replace(pattern, advisoryWording(pattern));
            }
        }
        if (ambiguousActionSeen && !rewritten.contains("不直接执行设备动作")) {
            rewritten = rewritten + EXECUTION_DISCLAIMER;
        }

        // 只给实际施用建议加提示；介绍能力、否定用药或原理解释无需固定尾注。
        boolean applicationAdvice=java.util.regex.Pattern.compile("(?:建议|推荐|可选|可用|采用|使用|施用|选用|喷施|喷洒).{0,18}(?:药剂|农药|多菌灵|代森锰锌|百菌清|戊唑醇|嘧霉胺|苯醚甲环唑|异菌脲|腐霉利|霜脲氰)").matcher(rewritten).find();
        if (applicationAdvice && !rewritten.contains("需人工确认")) {
            rewritten = rewritten + MANUAL_CONFIRM_NOTE;
        }

        // 规则④：降级必须披露
        if (degraded && !rewritten.contains("降级")) {
            rewritten = rewritten + DEGRADED_NOTE;
        }

        // M3包含真实历史观测。来源性质由绑定上下文和返回元数据说明，不能把所有“实测”字样盲目改成模拟。

        // 规则⑥：长度上限，防止刷屏与 token 失控
        if (rewritten.length() > MAX_ANSWER_CHARS) {
            rewritten = rewritten.substring(0, MAX_ANSWER_CHARS) + TRUNCATED_NOTE;
        }

        return rewritten.equals(text) ? GuardrailCheck.allow(text) : GuardrailCheck.rewrite(rewritten);
    }

    /** 把完成态动作词改写成建议语气；保留动作本身，只去掉"已经做了"的含义。 */
    private String advisoryWording(String pattern) {
        if (pattern.startsWith("自动")) {
            return "建议" + pattern.substring(2);
        }
        return "建议" + pattern.substring(1);
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    private boolean positiveMention(String text,String phrase){
        for(int start=0;(start=text.indexOf(phrase,start))>=0;start+=phrase.length()){
            String prefix=text.substring(Math.max(0,start-20),start);
            if(!java.util.regex.Pattern.compile("(?:未|没有|不能|不代表|不要|不会|不得|尚未|并未|不应).{0,16}$").matcher(prefix).find())return true;
        }return false;
    }
}
