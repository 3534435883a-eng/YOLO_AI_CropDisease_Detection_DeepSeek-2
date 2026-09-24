package com.example.Ece.agent.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/** Reviewed, paraphrased records with one traceable publication per record. */
@Component
public class CuratedCropKnowledgeReader {

    private static final ResourceSpec[] RESOURCES = {
            new ResourceSpec("/knowledge/curated-tomato.json", "curated_tomato", "番茄"),
            new ResourceSpec("/knowledge/curated-apple.json", "curated_apple", "苹果")
    };

    public List<SourceEntry> readAll() {
        List<SourceEntry> entries = new ArrayList<SourceEntry>();
        Set<String> codes = new HashSet<String>();
        for (ResourceSpec spec : RESOURCES) {
            readResource(spec, codes, entries);
        }
        return entries;
    }

    private void readResource(ResourceSpec spec, Set<String> codes, List<SourceEntry> entries) {
        InputStream stream = getClass().getResourceAsStream(spec.path);
        if (stream == null) {
            throw new IllegalStateException("未找到权威知识清单 " + spec.path);
        }
        try (InputStream input = stream; Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name())) {
            String text = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
            JSONObject root = JSON.parseObject(text);
            JSONArray rows = root == null ? null : root.getJSONArray("entries");
            if (rows == null || rows.isEmpty()) {
                throw new IllegalStateException("权威知识清单为空 " + spec.path);
            }
            Set<Long> ids = new HashSet<Long>();
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                long id = row.getLongValue("id");
                String code = required(row, "sourceCode", spec.path);
                String url = required(row, "sourceUrl", spec.path);
                if (id <= 0 || !ids.add(Long.valueOf(id)) || !codes.add(code)
                        || !(url.startsWith("https://") || url.startsWith("http://"))) {
                    throw new IllegalStateException("权威知识清单的 ID、来源代码或 URL 无效：" + code);
                }
                String crop = required(row, "crop", spec.path);
                if (!spec.crop.equals(crop) || !"B".equals(required(row, "sourceType", spec.path))) {
                    throw new IllegalStateException("权威知识清单的作物或来源层级无效：" + code);
                }
                KnowledgeSource source = new KnowledgeSource(code, required(row, "sourceName", spec.path),
                        "B", row.getIntValue("authorityLevel"), url,
                        required(row, "licenseNote", spec.path), required(row, "sourceVersion", spec.path));
                Map<KnowledgeChunk.FieldType, String> fields = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
                fields.put(KnowledgeChunk.FieldType.SYMPTOM, required(row, "symptom", spec.path));
                fields.put(KnowledgeChunk.FieldType.CAUSE, required(row, "cause", spec.path));
                fields.put(KnowledgeChunk.FieldType.CONTROL, required(row, "control", spec.path));
                IngestRecord record = new IngestRecord(spec.table, id, crop,
                        required(row, "name", spec.path), fields);
                entries.add(new SourceEntry(source, record));
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("读取权威知识清单失败 " + spec.path, error);
        }
    }

    private String required(JSONObject row, String key, String path) {
        String value = row.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("权威知识清单缺少 " + key + "：" + path);
        }
        return value.trim();
    }

    private static final class ResourceSpec {
        private final String path;
        private final String table;
        private final String crop;

        private ResourceSpec(String path, String table, String crop) {
            this.path = path;
            this.table = table;
            this.crop = crop;
        }
    }

    public static final class SourceEntry {
        private final KnowledgeSource source;
        private final IngestRecord record;

        SourceEntry(KnowledgeSource source, IngestRecord record) {
            this.source = source;
            this.record = record;
        }

        public KnowledgeSource getSource() { return source; }
        public IngestRecord getRecord() { return record; }
    }
}
