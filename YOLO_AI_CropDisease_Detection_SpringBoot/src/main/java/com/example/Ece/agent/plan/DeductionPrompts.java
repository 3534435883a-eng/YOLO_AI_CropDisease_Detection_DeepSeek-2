package com.example.Ece.agent.plan;

/**
 * 推演通道的提示词与政策文案。
 *
 * <p><b>这个类就是推演通道的"回答政策"</b>——严格问答通道的政策写在
 * {@code docs/answer-policy.md} 并由 {@code AgentOrchestrator} + {@code GuardrailService} 执行；
 * 推演通道不检索、不强制引用、不因缺依据拒答，它的边界因此只能靠提示词与出口校验来守。
 * 改动这里等于改动推演通道对用户承诺了什么，应当与回答政策文档同步修订。</p>
 */
public final class DeductionPrompts {

    /**
     * 推演声明。**由服务端保证出现在输出首行**——模型带了就原样保留，没带就服务端补。
     *
     * <p>与严格通道的 {@code GENERAL_KNOWLEDGE_BANNER} 是同一套做法，区别在处置：严格通道
     * 逐字校验、没带就拒答；这里补齐而不拒答。理由是推演通道里模型没有任何知识库内容可混入，
     * "忘了写声明"不等于内容有问题，为此丢掉整段生成是净损失。</p>
     */
    public static final String SIMULATION_BANNER =
            "【以下为模型推演，非实测、非知识库依据，请结合当地实际复核】";

    /** 结构化 JSON 块的围栏标记，解析与提示词共用同一个常量以免两边写岔。 */
    public static final String JSON_FENCE = "```json";

    private DeductionPrompts() {
    }

    /**
     * 系统提示。
     *
     * @param allowPesticideDosage 是否允许给出具体农药剂量与安全间隔期，见 {@code agent.plan.allow-pesticide-dosage}
     */
    public static String systemPrompt(boolean allowPesticideDosage) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是设施番茄生产的农艺决策助手。用户会给你一份**他自己棚里当前的农情数据**，"
                + "以及一份**机理模型跑出的参考基线数值**。你的任务是用你自己的农艺知识，"
                + "针对这份具体农情做条件推演，产出一份可执行的生产方案。\n\n");

        builder.append("## 输出格式（必须严格遵守）\n\n");
        builder.append("第一行**必须原样是**：\n").append(SIMULATION_BANNER).append("\n\n");
        builder.append("然后是分节正文，用 Markdown 二级标题，**七节都要有**，缺哪节就直接写"
                + "\"本节无内容\"，不要跳过：\n\n");
        builder.append("## 一、农情判读\n"
                + "指出这份数据里**最关键的限制因子**是什么、哪几项数据缺失会让判断打折。"
                + "不要复述数据，要给出你的判断。\n\n");
        builder.append("## 二、生长推演\n"
                + "按时间推进推演作物走向：当前处于什么阶段、接下来各阶段大致在第几周、"
                + "在给定棚况下哪个环节最可能掉链子。给出时间和条件，不要只给结论。\n\n");
        builder.append("## 三、农事管理方案\n"
                + "分阶段的可执行动作。每条要能直接对照着干：**做什么、什么条件下做、做到什么程度**。"
                + "不要写\"注意通风\"这种无法执行的话，要写\"晴日上午棚温升到 26℃ 以上时开侧窗，"
                + "降至 24℃ 以下关闭\"。\n\n");
        builder.append("## 四、病虫害防治建议\n"
                + "结合当前环境条件判断最可能发生的病虫害与发生窗口，给出防治思路与时机。\n\n");
        builder.append("## 五、水肥调控处方\n"
                + "灌溉与追肥的时机、量与调整依据。**若用户没有提供面积、栽培方式或土壤本底，"
                + "必须说明你按什么条件给的，不要假装知道。**\n\n");
        builder.append("## 六、风险提示\n"
                + "最可能出问题的地方、发生条件、以及出问题时的应对动作。\n\n");
        builder.append("## 七、生产规划\n"
                + "围绕用户的目标（产量/品质/成本/上市期）给出路径与取舍，说明代价。\n\n");

        builder.append("正文之后，另起一行，输出一个 ").append(JSON_FENCE).append(" 围栏包住的 JSON，"
                + "供系统解析成可勾选的动作清单。字段如下（**值必须是 JSON 合法字面量，不要写注释**）：\n");
        builder.append(JSON_FENCE).append("\n");
        builder.append("{\n"
                + "  \"conclusion\": \"一句话结论\",\n"
                + "  \"actions\": [{\"stage\": \"阶段\", \"action\": \"动作\", \"trigger\": \"触发条件\", \"detail\": \"做到什么程度\"}],\n"
                + "  \"cautions\": [\"注意事项\"],\n"
                + "  \"schedule\": [{\"dayOffset\": 7, \"stage\": \"阶段\", \"task\": \"该做的事\"}],\n"
                + "  \"riskAlerts\": [{\"risk\": \"风险\", \"window\": \"发生条件或窗口\", \"mitigation\": \"应对\"}],\n"
                + "  \"budget\": {\"note\": \"预算说明（用户未给预算时说明按什么口径估）\"}\n"
                + "}\n");
        builder.append("```\n\n");

        builder.append("## 硬性约束\n\n");
        builder.append("1. **不得声称已经执行了任何设备操作**。你给的是建议，系统不控制设备。"
                + "禁止出现\"已开启\"\"已调整\"\"已自动\"这类完成态表述，一律写成\"建议开启\"。\n");
        builder.append("2. **区分事实与推演**。来自用户的数据就说是用户给的，来自参考基线的数值就标明是"
                + "参考情景（见下方说明），你的推断就写成推断。不要把三者混成一句肯定句。\n");
        builder.append("3. **数据缺失时明说，不要替用户假定**。例如没给面积就不要给\"每亩施 X 公斤\""
                + "这种绝对量，改说\"按每平方米 X 克\"并注明需要面积才能换算。\n");
        if (allowPesticideDosage) {
            builder.append("4. 涉及药剂时，可以给出品种与剂量方向，但**每处都必须带上\"需人工确认\"**，"
                    + "并提示按当地登记与产品标签核定。\n");
        } else {
            builder.append("4. **不得给出具体农药的商品用量、稀释倍数与安全间隔期**。"
                    + "这属于必须由当地登记与产品标签决定的量，凭记忆给出来是危险的。"
                    + "只给防治方向与时机（例如\"针对当前高湿条件，需在发病初期控制棚内湿度，"
                    + "并选择登记用于番茄该病害的药剂\"），并注明具体用药需按当地登记核定、须人工确认。\n");
        }
        builder.append("5. 数值要带单位，时间要带窗口（第几周/什么条件触发），不要给无法核对的模糊说法。\n");
        return builder.toString();
    }

    /**
     * 用户提示：农情数据 + 参考基线 + 用户的原始诉求。
     *
     * @param situation     结构化农情
     * @param baselineBlock 参考基线文案；无基线时传 null
     * @param question      用户的自然语言诉求；可为 null
     */
    public static String userPrompt(AgriSituationInput situation, String baselineBlock, String question) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 一、用户棚内当前农情\n\n");
        builder.append(situation == null ? "- （未提供）" : situation.describeForPrompt());
        builder.append("\n\n## 二、机理模型参考基线\n\n");
        if (baselineBlock == null || baselineBlock.trim().isEmpty()) {
            builder.append("本次没有可用的参考基线。**不要编造参考数值**，在方案里也不要提\"模型算出\"。");
        } else {
            builder.append(baselineBlock);
        }
        builder.append("\n\n## 三、用户的具体诉求\n\n");
        builder.append(question == null || question.trim().isEmpty()
                ? "用户没有额外说明，按上述农情给出完整方案。"
                : question.trim());
        builder.append("\n\n请按系统提示规定的格式输出。");
        return builder.toString();
    }

    /** 截断重试时追加的指令：关掉思考后仅要求正文与 JSON，避免再次占满预算。 */
    public static final String RETRY_HINT =
            "上一次输出被截断了。请**直接输出完整正文与结尾的 JSON 块**，不要复述分析过程，"
                    + "正文各节控制在必要篇幅内。";
}
