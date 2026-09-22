package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 内存向量索引：语料为百级，直接逐条余弦相似度即可，无需向量数据库。 */
@Component
public class VectorIndex {

    private final List<KnowledgeChunk> documents = new ArrayList<KnowledgeChunk>();
    private final List<double[]> vectors = new ArrayList<double[]>();
    private boolean available = false;

    public void rebuild(List<KnowledgeChunk> chunks, List<double[]> embeddings) {
        documents.clear();
        vectors.clear();
        available = false;
        if (chunks == null || embeddings == null || chunks.size() != embeddings.size()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            double[] vector = embeddings.get(i);
            if (chunk == null || vector == null || vector.length == 0) {
                return;
            }
            documents.add(chunk);
            vectors.add(normalize(vector));
        }
        available = !documents.isEmpty();
    }

    public boolean isAvailable() { return available; }

    public List<ScoredChunk> search(double[] query, int topK) {
        List<ScoredChunk> results = new ArrayList<ScoredChunk>();
        if (!available || query == null || query.length == 0 || topK <= 0) {
            return results;
        }
        double[] normalizedQuery = normalize(query);
        if (isZero(normalizedQuery)) {
            return results;
        }
        for (int i = 0; i < documents.size(); i++) {
            double similarity = cosine(normalizedQuery, vectors.get(i));
            if (similarity > 0.0) {
                results.add(new ScoredChunk(documents.get(i), similarity, 0));
            }
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

    private double cosine(double[] left, double[] right) {
        int length = Math.min(left.length, right.length);
        double sum = 0.0;
        for (int i = 0; i < length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }

    private double[] normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        double[] result = new double[vector.length];
        if (norm == 0.0) {
            return result;
        }
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] / norm;
        }
        return result;
    }

    private boolean isZero(double[] vector) {
        for (double value : vector) {
            if (value != 0.0) {
                return false;
            }
        }
        return true;
    }
}
