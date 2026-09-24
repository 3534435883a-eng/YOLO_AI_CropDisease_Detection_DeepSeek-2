package com.example.Ece.agent.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 开发者保留集：只作回归护栏，不宣称独立农艺验证。 */
class DeveloperHoldoutEvalTest {
    @Test
    void evaluatesHoldoutSchemaAndRetrievalBoundary() throws Exception {
        List<JSONObject> corpus = readCorpus();
        List<KnowledgeChunk> chunks = new ArrayList<KnowledgeChunk>();
        KnowledgeChunker chunker = new KnowledgeChunker();
        for (JSONObject record : corpus) {
            Map<KnowledgeChunk.FieldType, String> fields = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
            put(fields, KnowledgeChunk.FieldType.SYMPTOM, record.getString("symptom"));
            put(fields, KnowledgeChunk.FieldType.CAUSE, record.getString("cause"));
            put(fields, KnowledgeChunk.FieldType.CONTROL, record.getString("control"));
            chunks.addAll(chunker.chunk(record.getLongValue("id"), record.getString("crop"),
                    record.getString("name"), fields));
        }
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) throws EmbeddingUnavailableException {
                throw new EmbeddingUnavailableException("holdout-bm25-only");
            }
        }, new KnowledgeEntityLexicon());
        retriever.rebuild(chunks);

        int expectedAnswer = 0;
        int expectedRefusal = 0;
        int retrievalFalseAccepts = 0;
        for (JSONObject row : readHoldout()) {
            assertTrue(row.containsKey("id"));
            assertTrue(row.containsKey("crop"));
            assertTrue(row.containsKey("question"));
            assertTrue(row.containsKey("expected_topic"));
            assertTrue(row.containsKey("should_refuse"));
            RetrievalResult result = retriever.retrieve(row.getString("question"), row.getString("crop"), 3);
            boolean lowScore = retriever.isLowScore(result);
            if (row.getBooleanValue("should_refuse")) {
                expectedRefusal++;
                if (!lowScore) {
                    retrievalFalseAccepts++;
                    System.out.println("[developer-holdout][retrieval false accept] " + row.getString("id")
                            + " first=" + (result.getItems().isEmpty() ? "none"
                            : result.getItems().get(0).getChunk().getDiseaseName()));
                }
            } else {
                expectedAnswer++;
                assertTrue(!lowScore, row.getString("id") + " should have retrievable evidence");
                assertEquals(row.getString("expected_topic"), result.getItems().get(0).getChunk().getDiseaseName(),
                        row.getString("id") + " top hit changed");
            }
        }
        assertEquals(2, expectedAnswer);
        assertEquals(2, expectedRefusal);
        System.out.println("[developer-holdout] BM25-only negative false accepts: "
                + retrievalFalseAccepts + "/" + expectedRefusal);
    }

    private List<JSONObject> readCorpus() throws Exception {
        return readJsonArray("/corpus/disease-corpus.json");
    }

    private List<JSONObject> readHoldout() throws Exception {
        Path file = Paths.get("..", "docs", "eval", "developer_holdout.jsonl");
        assertTrue(Files.isRegularFile(file), "missing developer holdout: " + file.toAbsolutePath());
        List<JSONObject> result = new ArrayList<JSONObject>();
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                result.add(JSON.parseObject(line));
            }
        }
        reader.close();
        return result;
    }

    private List<JSONObject> readJsonArray(String resource) throws Exception {
        InputStream stream = getClass().getResourceAsStream(resource);
        assertTrue(stream != null, "missing corpus");
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            text.append(line);
        }
        reader.close();
        com.alibaba.fastjson.JSONArray array = JSON.parseArray(text.toString());
        List<JSONObject> result = new ArrayList<JSONObject>();
        for (int i = 0; i < array.size(); i++) {
            result.add(array.getJSONObject(i));
        }
        return result;
    }

    private void put(Map<KnowledgeChunk.FieldType, String> fields, KnowledgeChunk.FieldType type, String value) {
        if (value != null && !value.trim().isEmpty()) {
            fields.put(type, value);
        }
    }
}
