package com.example.Ece.agent.plan;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.agent.support.JsonBlockScanner;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.service.DeepSeekService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自然语言描述 → 结构化农情。
 *
 * <p><b>核心原则：模型只负责"抽"，不负责"判"。</b>一切数值合法性、单位剥离、范围校验、
 * 缺失判定都在本类的 Java 侧完成。让模型顺便"判断一下这个温度合不合理"，
 * 等于把校验交给一个会编数字的组件。</p>
 *
 * <p><b>逐字段回带原话</b>是这里最关键的设计：没有原话支撑的字段一律丢弃。
 * 这与仓库"引用必须可追溯到原文"是同一条纪律，只是对象从知识库换成了用户输入。
 * 模型漏抽一个字段只是少一个数，编一个字段会让整份方案建立在不存在的棚况上。</p>
 */
@Service
public class AgriSituationNlParser {

    private static final Logger log = LoggerFactory.getLogger(AgriSituationNlParser.class);

    private final DeepSeekService deepSeekService;

    public AgriSituationNlParser(DeepSeekService deepSeekService) {
        this.deepSeekService = deepSeekService;
    }

    public SituationDraft parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new SituationDraft(new AgriSituationInput(), null, null, null,
                    Collections.singletonList("没有收到描述内容。"));
        }

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(message("system", systemPrompt()));
        messages.add(message("user", text.trim()));

        String raw;
        try {
            AiChatResponse response = deepSeekService.chat(messages,
                    DeepSeekService.ChatOptions.composingWithoutThinking());
            raw = response == null ? null : response.getContent();
        } catch (RuntimeException error) {
            log.warn("农情描述抽取调用失败：{}", error.toString());
            return new SituationDraft(new AgriSituationInput(), null, null, null,
                    Collections.singletonList("描述抽取失败（AI 调用异常），请改用手填表单。"));
        }

        JSONObject parsed = JsonBlockScanner.firstObject(raw, object -> object.containsKey("fields"));
        if (parsed == null) {
            return new SituationDraft(new AgriSituationInput(), null, null, null,
                    Collections.singletonList("没能从描述里识别出结构化农情，请改用手填表单或补充更具体的数值。"));
        }
        return validate(parsed.getJSONArray("fields"));
    }

    /**
     * 确定性校验。**这里是唯一的"判"的地方。**
     *
     * <p>逐条处理抽取结果，四种情况分别对待：登记表里没有的字段丢弃；没有原话支撑的丢弃；
     * 数值解析不出的丢弃；超出物理区间的丢弃。**全部按"未提供"处理并在 notes 里说明原因**，
     * 而不是夹取、不是猜、也不是整份拒绝。</p>
     */
    private SituationDraft validate(JSONArray rawFields) {
        AgriSituationInput input = new AgriSituationInput();
        List<SituationDraft.FieldValue> fields = new ArrayList<SituationDraft.FieldValue>();
        List<String> notes = new ArrayList<String>();
        List<String> conflicts = new ArrayList<String>();
        Map<String, String> firstValueByKey = new LinkedHashMap<String, String>();
        Map<String, String> evidence = new LinkedHashMap<String, String>();

        if (rawFields != null) {
            for (int index = 0; index < rawFields.size(); index++) {
                JSONObject item = rawFields.getJSONObject(index);
                if (item == null) {
                    continue;
                }
                String key = item.getString("key");
                String rawText = item.getString("rawText");
                SituationField spec = SituationFields.byKey(key);
                if (spec == null) {
                    if (key != null && !key.trim().isEmpty()) {
                        notes.add("忽略了未登记的字段「" + key + "」");
                    }
                    continue;
                }
                // 没有原话支撑的字段一律丢弃——这是防幻觉的主要闸门。
                if (rawText == null || rawText.trim().isEmpty()) {
                    notes.add(spec.getLabel() + "：抽取结果没有给出依据原话，已按未提供处理");
                    continue;
                }
                String rawValue = item.getString("value");
                if (rawValue == null || rawValue.trim().isEmpty()) {
                    // 用户提到了、但没能变成可用取值（典型是相对时间"上个月定植的"）。
                    // **报出来让用户自己确认，而不是丢掉**——这条信息模型不该猜，
                    // 但用户确实说了，悄悄丢掉等于让方案缺一个已知的输入。
                    notes.add(spec.getLabel() + "：你提到「" + rawText.trim()
                            + "」，但无法直接换算成可用取值，请手动确认");
                    continue;
                }
                if (spec.getKind() == SituationField.Kind.NUMBER) {
                    Double parsed = parseNumber(rawValue);
                    if (parsed == null) {
                        notes.add(spec.getLabel() + "：无法从「" + rawValue.trim()
                                + "」解析出数值，已按未提供处理");
                        continue;
                    }
                    if (!spec.isInRange(parsed.doubleValue())) {
                        // 不夹取、不"修正"，直接判为不可用——夹取会把 2500 悄悄变成 60。
                        notes.add(spec.getLabel() + "：「" + rawValue.trim() + "」超出合理区间 "
                                + spec.rangeText() + "，已按未提供处理");
                        continue;
                    }
                    input.put(key, parsed);
                } else {
                    input.put(key, rawValue.trim());
                    if (input.get(key) == null) {
                        notes.add(spec.getLabel() + "：「" + rawValue.trim()
                                + "」无法解析为可用取值，已按未提供处理");
                        continue;
                    }
                }

                String normalized = String.valueOf(input.get(key));
                String previous = firstValueByKey.get(key);
                if (previous == null) {
                    firstValueByKey.put(key, normalized);
                    evidence.put(key, rawText.trim());
                } else if (!previous.equals(normalized)) {
                    // 同一字段被提到两次且不一致：交用户裁决，不替他选一个。
                    conflicts.add(spec.getLabel() + "：「" + previous + "」与「" + normalized
                            + "」不一致，当前采用先出现的值，请确认");
                }
            }
        }

        for (SituationField spec : SituationFields.all()) {
            Object value = input.get(spec.getKey());
            if (value == null) {
                continue;
            }
            fields.add(new SituationDraft.FieldValue(spec.getKey(), spec.getLabel(), spec.getUnit(),
                    value, SituationFields.SOURCE_PARSED, evidence.get(spec.getKey())));
        }
        return new SituationDraft(input, fields, input.missingLabels(), conflicts, notes);
    }

    /** 只剥离一个合法数字前缀，不做模糊匹配。与 {@code AgriSituationInput} 的归一保持一致。 */
    private Double parseNumber(String text) {
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isDigit(current) || current == '.'
                    || (current == '-' && digits.length() == 0)) {
                digits.append(current);
            } else if (digits.length() > 0) {
                break;
            } else {
                return null;
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(digits.toString());
            return Double.isFinite(parsed) ? Double.valueOf(parsed) : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String systemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append("你是一个**只做字段抽取**的解析器。从用户的农情描述里，"
                + "把他说过的信息填进下面的字段表。\n\n");
        builder.append("## 可抽取的字段\n\n").append(SituationFields.describeForExtraction());
        builder.append("\n## 输出格式\n\n只输出一个 JSON 对象，不要任何其他文字：\n");
        builder.append("{\"fields\":[{\"key\":\"字段名\",\"value\":\"取值\",\"rawText\":\"支撑它的用户原话\"}]}\n\n");
        builder.append("## 抽取规则（必须严格遵守）\n\n");
        builder.append("1. **只填用户明确说过的字段**。没说的**不要输出该字段**，"
                + "不要填 null，更不要给\"通常情况下\"的典型值。\n");
        builder.append("2. **rawText 必须是用户原话里的一小段，逐字引用**，能让人看出这个值是从哪句话来的。"
                + "如果你写不出这样的原话，说明用户没说，就**不要输出这个字段**。\n");
        builder.append("3. 数值型字段的 value **只填数字本身**，不带单位、不带说明"
                + "（用户说\"25度左右\"，value 填 25）。\n");
        builder.append("4. **相对时间不要换算成日期，但要把它报出来**。用户说\"上个月定植的\"，"
                + "不要把定植日期算出来——缺少锚点，任何换算都是猜。"
                + "这种情况请输出该字段、`value` 留空字符串、`rawText` 填用户原话。"
                + "系统据此提醒用户手动确认，比直接丢掉这条信息有用。\n");
        builder.append("5. 用户没提的、含糊的、需要推断的，一律不输出。**宁可少填，不要猜。**\n");
        return builder.toString();
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
