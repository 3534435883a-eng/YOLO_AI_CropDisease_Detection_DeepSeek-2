package com.example.Ece.agent.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/**
 * 症状口语词表：把一线人员的口语症状描述映射到知识库使用的规范用词。
 *
 * <p><b>为什么必须有</b>（实测）：农户不会说"同心轮纹"，他说"一圈一圈"；不会说"瘤状物"，
 * 他说"根上长小疙瘩"。而检索是中文 bigram 匹配，**口语词与规范词几乎没有一个 bigram 重叠**，
 * 再怎么排序也召不回。实测差距摆在那里：照抄原文的症状前缀题 Top-1 **95.0%**，
 * 而农户真实说法的口语化题 Top-1 只有 **73.3%**——前者与语料同词，后者不同词。</p>
 *
 * <p><b>与作物别名词典的分工</b>：{@link KnowledgeEntityLexicon} 归一化的是**作物**
 *（土豆→马铃薯），本类归一化的是**症状**（一圈一圈→同心轮纹）。两者共用同一策略——
 * <b>追加而非替换</b>，用户原话仍参与匹配，只是补上知识库真正使用的写法。</p>
 *
 * <p><b>来源如实标注</b>：口语侧是人工整理的一线说法，**不是权威来源**；
 * 规范侧在知识库原文中的出现次数、以及口语侧实际为 0 的次数，都逐条记在
 * {@code symptom-aliases.json} 的 evidence 字段里，可复核。</p>
 */
@Component
public class SymptomLexicon {

    private static final Logger LOGGER = LoggerFactory.getLogger(SymptomLexicon.class);
    private static final String RESOURCE = "/knowledge/symptom-aliases.json";

    private static final class Mapping {
        private final List<String> colloquial;
        private final List<String> canonical;

        private Mapping(List<String> colloquial, List<String> canonical) {
            this.colloquial = colloquial;
            this.canonical = canonical;
        }
    }

    private final List<Mapping> mappings = new ArrayList<Mapping>();
    private String version = "";

    public SymptomLexicon() {
        load();
    }

    private void load() {
        InputStream stream = SymptomLexicon.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            LOGGER.warn("未找到症状口语词表 {}，症状归一化将不可用", RESOURCE);
            return;
        }
        try {
            Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            JSONObject root = JSON.parseObject(scanner.hasNext() ? scanner.next() : "{}");
            version = root.getString("version") == null ? "" : root.getString("version");
            JSONArray rows = root.getJSONArray("mappings");
            if (rows == null) {
                return;
            }
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                List<String> colloquial = strings(row.getJSONArray("colloquial"));
                List<String> canonical = strings(row.getJSONArray("canonical"));
                if (!colloquial.isEmpty() && !canonical.isEmpty()) {
                    mappings.add(new Mapping(colloquial, canonical));
                }
            }
        } catch (RuntimeException error) {
            LOGGER.warn("症状口语词表解析失败，症状归一化将不可用：{}", error.toString());
        }
    }

    private List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<String>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.size(); i++) {
            String value = array.getString(i);
            // 单字口语词不收录：如"烂""倒"会在任意含该字的文本里误匹配
            if (value != null && value.trim().length() >= 2) {
                result.add(value.trim());
            }
        }
        return result;
    }

    /**
     * 查询扩展：命中口语症状词时，把对应的规范用词追加到查询末尾。
     *
     * <p>已经出现过的规范词不重复追加。</p>
     */
    public String expandQuery(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        StringBuilder expansion = new StringBuilder();
        for (String canonical : canonicalTermsFor(query)) {
            if (query.contains(canonical) || expansion.indexOf(canonical) >= 0) {
                continue;
            }
            if (expansion.length() > 0) {
                expansion.append(" ");
            }
            expansion.append(canonical);
        }
        return expansion.length() == 0 ? query : query + " " + expansion;
    }

    /** 本次查询会追加的规范词（保序、去重；诊断与测试用）。 */
    public List<String> canonicalTermsFor(String query) {
        Set<String> result = new LinkedHashSet<String>();
        if (query == null || query.isEmpty()) {
            return new ArrayList<String>(result);
        }
        for (Mapping mapping : mappings) {
            for (String colloquial : mapping.colloquial) {
                if (query.contains(colloquial)) {
                    result.addAll(mapping.canonical);
                    break;
                }
            }
        }
        return new ArrayList<String>(result);
    }

    public boolean isEmpty() {
        return mappings.isEmpty();
    }

    public int size() {
        return mappings.size();
    }

    public String getVersion() {
        return version;
    }
}
