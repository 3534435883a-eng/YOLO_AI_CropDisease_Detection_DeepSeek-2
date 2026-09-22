package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：score = Σ 1/(k + rank)。
 * 与分数绝对值无关，便于把 BM25 与余弦相似度两路排序融合。
 */
@Component
public class RrfFusion {

    public List<ScoredChunk> fuse(List<List<ScoredChunk>> rankedLists, int k, int topN) {
        List<ScoredChunk> results = new ArrayList<ScoredChunk>();
        if (rankedLists == null || rankedLists.isEmpty() || topN <= 0) {
            return results;
        }
        Map<String, ScoredChunk> byHash = new LinkedHashMap<String, ScoredChunk>();
        Map<String, Double> scores = new LinkedHashMap<String, Double>();
        for (List<ScoredChunk> list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (int position = 0; position < list.size(); position++) {
                ScoredChunk hit = list.get(position);
                if (hit == null || hit.getChunk() == null) {
                    continue;
                }
                int rank = hit.getRank() > 0 ? hit.getRank() : position + 1;
                String key = hit.getChunk().getContentHash();
                if (!byHash.containsKey(key)) {
                    byHash.put(key, hit);
                    scores.put(key, 0.0);
                }
                scores.put(key, scores.get(key) + 1.0 / (k + rank));
            }
        }
        for (Map.Entry<String, ScoredChunk> entry : byHash.entrySet()) {
            results.add(new ScoredChunk(entry.getValue().getChunk(), scores.get(entry.getKey()), 0));
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
        List<ScoredChunk> limited = results.size() > topN
                ? new ArrayList<ScoredChunk>(results.subList(0, topN)) : results;
        for (int i = 0; i < limited.size(); i++) {
            limited.get(i).setRank(i + 1);
        }
        return limited;
    }
}
