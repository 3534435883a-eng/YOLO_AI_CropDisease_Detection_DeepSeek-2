package com.example.Ece.agent.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 作物实体别名词典：把农户口语里的作物别称归一化到知识库使用的规范作物名。
 *
 * <p><b>为什么必须有</b>（实测）：调用方若把作物写成别名（如"土豆"），
 * {@code filterByCrop} 里的 {@code chunkCrop.contains(cropType)} 判断（"马铃薯".contains("土豆")）为假，
 * 结果**一条证据都召不回**——实测该场景 2/2 零召回，智能体只能拒答。</p>
 *
 * <p><b>来源如实标注</b>：别称来自人工整理的通用农艺术语，**不是权威来源**；
 * 词典里逐条标注了该别名是否在知识库原文中出现过（如"冬小麦"×5、"苹果树"×7）。
 * 未标注者表示库内 0 次出现，属词典补充而非语料派生。单字别名（如"棉"）不收录，避免误匹配。</p>
 */
@Component
public class KnowledgeEntityLexicon {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeEntityLexicon.class);
    private static final String RESOURCE = "/knowledge/crop-aliases.json";

    private final Map<String, String> aliasToCrop = new LinkedHashMap<String, String>();
    private final Map<String, String> cropToAliasText = new LinkedHashMap<String, String>();
    private String version = "";

    public KnowledgeEntityLexicon() {
        load();
    }

    private void load() {
        InputStream stream = KnowledgeEntityLexicon.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            LOGGER.warn("未找到作物别名词典 {}，别名归一化将不可用", RESOURCE);
            return;
        }
        try {
            Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            JSONObject root = JSON.parseObject(scanner.hasNext() ? scanner.next() : "{}");
            version = root.getString("version") == null ? "" : root.getString("version");
            JSONArray crops = root.getJSONArray("crops");
            if (crops != null) {
                for (int i = 0; i < crops.size(); i++) {
                    JSONObject entry = crops.getJSONObject(i);
                    String crop = entry.getString("crop");
                    if (crop == null || crop.trim().isEmpty()) {
                        continue;
                    }
                    crop = crop.trim();
                    aliasToCrop.put(crop, crop);
                    JSONArray aliases = entry.getJSONArray("aliases");
                    StringBuilder text = new StringBuilder();
                    if (aliases != null) {
                        for (int j = 0; j < aliases.size(); j++) {
                            String alias = aliases.getString(j);
                            if (alias == null || alias.trim().length() < 2) {
                                continue;
                            }
                            aliasToCrop.put(alias.trim(), crop);
                            if (text.length() > 0) {
                                text.append("、");
                            }
                            text.append(alias.trim());
                        }
                    }
                    cropToAliasText.put(crop, text.toString());
                }
            }
        } catch (RuntimeException error) {
            LOGGER.warn("作物别名词典解析失败，别名归一化将不可用：{}", error.toString());
        }
    }

    /** 别名或规范名 → 规范作物名；未知输入原样返回。 */
    public String canonicalizeCrop(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        String canonical = aliasToCrop.get(trimmed);
        if (canonical != null) {
            return canonical;
        }
        // 调用方可能写"番茄（大棚）"这类复合值，做一次包含匹配
        for (Map.Entry<String, String> entry : aliasToCrop.entrySet()) {
            if (trimmed.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return value;
    }

    /**
     * 查询归一化：问题里出现作物别名时，把规范作物名追加到查询串末尾。
     *
     * <p>追加而非替换——用户的原始用词仍参与匹配，只是补上知识库真正使用的写法
     * （知识块头部带"作物：马铃薯"，因此补上规范名后关键词与向量都能对上）。</p>
     */
    public String expandQuery(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        StringBuilder expansion = new StringBuilder();
        for (String alias : aliasToCrop.keySet()) {
            if (alias.length() < 2 || !query.contains(alias)) {
                continue;
            }
            String canonical = aliasToCrop.get(alias);
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

    public boolean isEmpty() {
        return aliasToCrop.isEmpty();
    }

    public String getVersion() {
        return version;
    }

    /** 规范作物名 → 别名文本（用于展示）。 */
    public Map<String, String> getAliasTextByCrop() {
        return new LinkedHashMap<String, String>(cropToAliasText);
    }
}