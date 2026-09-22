package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存 BM25 索引（中文 bigram）。语料规模为百级，无需倒排落盘或向量库。
 * k1 = 1.2、b = 0.75 为文献常用取值。
 */
@Component
public class Bm25Index {

    public static final double K1 = 1.2;
    public static final double B = 0.75;

    private final ChineseBigramTokenizer tokenizer;
    private final List<KnowledgeChunk> documents = new ArrayList<KnowledgeChunk>();
    private final List<Map<String, Integer>> termFrequencies = new ArrayList<Map<String, Integer>>();
    private final List<Integer> documentLengths = new ArrayList<Integer>();
    private final Map<String, List<Integer>> inverted = new HashMap<String, List<Integer>>();
    private double averageLength = 0.0;

    public Bm25Index() {
        this(new ChineseBigramTokenizer());
    }

    public Bm25Index(ChineseBigramTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public void rebuild(List<KnowledgeChunk> chunks) {
        documents.clear();
        termFrequencies.clear();
        documentLengths.clear();
        inverted.clear();
        int totalLength = 0;
        if (chunks != null) {
            for (KnowledgeChunk chunk : chunks) {
                if (chunk == null) {
                    continue;
                }
                List<String> tokens = tokenizer.tokenize(chunk.getContent());
                Map<String, Integer> frequencies = new HashMap<String, Integer>();
                for (String token : tokens) {
                    Integer count = frequencies.get(token);
                    frequencies.put(token, count == null ? 1 : count + 1);
                }
                int documentIndex = documents.size();
                documents.add(chunk);
                termFrequencies.add(frequencies);
                documentLengths.add(tokens.size());
                totalLength += tokens.size();
                for (String token : frequencies.keySet()) {
                    List<Integer> postings = inverted.get(token);
                    if (postings == null) {
                        postings = new ArrayList<Integer>();
                        inverted.put(token, postings);
                    }
                    postings.add(documentIndex);
                }
            }
        }
        averageLength = documents.isEmpty() ? 0.0 : (double) totalLength / documents.size();
    }

    public List<ScoredChunk> search(String query, int topK) {
        List<ScoredChunk> results = new ArrayList<ScoredChunk>();
        List<String> queryTokens = tokenizer.tokenize(query);
        if (queryTokens.isEmpty() || documents.isEmpty() || topK <= 0) {
            return results;
        }
        int total = documents.size();
        Set<String> uniqueTokens = new LinkedHashSet<String>(queryTokens);
        Map<Integer, Double> scores = new HashMap<Integer, Double>();
        for (String token : uniqueTokens) {
            List<Integer> postings = inverted.get(token);
            if (postings == null || postings.isEmpty()) {
                continue;
            }
            double idf = Math.log(1.0 + (total - postings.size() + 0.5) / (postings.size() + 0.5));
            for (Integer documentIndex : postings) {
                Integer frequency = termFrequencies.get(documentIndex).get(token);
                int termFrequency = frequency == null ? 0 : frequency;
                int length = documentLengths.get(documentIndex);
                double denominator = termFrequency + K1 * (1.0 - B + B * length / Math.max(averageLength, 1.0));
                if (denominator <= 0.0) {
                    continue;
                }
                double contribution = idf * (termFrequency * (K1 + 1.0)) / denominator;
                Double current = scores.get(documentIndex);
                scores.put(documentIndex, current == null ? contribution : current + contribution);
            }
        }
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            results.add(new ScoredChunk(documents.get(entry.getKey()), entry.getValue(), 0));
        }
        Collections.sort(results, new Comparator<ScoredChunk>() {
            public int compare(ScoredChunk left, ScoredChunk right) {
                int byScore = Double.compare(right.getScore(), left.getScore());
                if (byScore != 0) {
                    return byScore;
                }
                return left.getChunk().getContentHash().compareTo(right.getChunk().getContentHash());
            }
        });
        List<ScoredChunk> limited = results.size() > topK
                ? new ArrayList<ScoredChunk>(results.subList(0, topK)) : results;
        for (int i = 0; i < limited.size(); i++) {
            limited.get(i).setRank(i + 1);
        }
        return limited;
    }
}
