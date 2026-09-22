package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * 单个知识块对查询词的覆盖率：该块自身含有多少比例的查询词。
     *
     * <p>与语料级覆盖率的关键区别是**要求命中词共现**。语料级覆盖率会把散落在不同块里的
     * 偶然 bigram 碰撞累加起来——实测"玉米价格今天多少"靠 玉米/天多/多少 三个词在语料不同位置
     * 的命中拿到 0.31，而一个真正的提问可能只有 0.21。逐块覆盖率问的是"证据本身是否支持这个结论"，
     * 这才是回答充分性该问的问题。</p>
     */
    public double chunkCoverage(String query, KnowledgeChunk chunk) {
        int count = queryTermCount(query);
        return count <= 0 ? 0.0 : (double) chunkMatchedTermCount(query, chunk) / count;
    }

    /**
     * 单个知识块中**共现**的查询词个数（绝对数量，未做长度归一）。
     *
     * <p>这是判断"证据是否支持结论"最干净的信号。实测该值对负样本恒 ≤1（只命中作物名或
     * 偶然 bigram），对真实提问普遍 ≥2；而覆盖率、融合分都会因长问句被稀释或按构造失真。</p>
     */
    public int chunkMatchedTermCount(String query, KnowledgeChunk chunk) {
        Set<String> queryTerms = new LinkedHashSet<String>(tokenizer.tokenize(query));
        if (queryTerms.isEmpty() || chunk == null) {
            return 0;
        }
        Set<String> chunkTerms = new HashSet<String>(tokenizer.tokenize(chunk.getContent()));
        int hit = 0;
        for (String term : queryTerms) {
            if (chunkTerms.contains(term)) {
                hit++;
            }
        }
        return hit;
    }
    /** 命中的查询词（去重，按出现顺序），用于诊断与覆盖率解释。 */
    public List<String> matchedTerms(String query) {
        List<String> matched = new ArrayList<String>();
        for (String token : new LinkedHashSet<String>(tokenizer.tokenize(query))) {
            List<Integer> postings = inverted.get(token);
            if (postings != null && !postings.isEmpty()) {
                matched.add(token);
            }
        }
        return matched;
    }

    /** 查询词总数（去重）。 */
    public int queryTermCount(String query) {
        return new LinkedHashSet<String>(tokenizer.tokenize(query)).size();
    }
    /**
     * 查询词覆盖率（IDF 加权）：命中词的权重和 / 全部查询词权重和。
     *
     * <p>用于回答"这个问题到底有多少内容落在语料里"。仅判断"有没有命中"过于宽松——
     * {@code 如何给番茄施肥} 含"番茄"就能命中大量病害块，实测负样本拒答率只有 40%。
     * 加权后，常见词（如"番茄"）权重低、稀有词（如"轮纹"）权重高，判定才符合直觉。</p>
     */
    public double queryCoverage(String query) {
        List<String> queryTokens = tokenizer.tokenize(query);
        if (queryTokens.isEmpty() || documents.isEmpty()) {
            return 0.0;
        }
        Set<String> unique = new LinkedHashSet<String>(queryTokens);
        double unknownIdf = Math.log(1.0 + (documents.size() - 0.5) / 0.5);
        double totalWeight = 0.0;
        double matchedWeight = 0.0;
        for (String token : unique) {
            List<Integer> postings = inverted.get(token);
            boolean matched = postings != null && !postings.isEmpty();
            double weight = matched
                    ? Math.log(1.0 + (documents.size() - postings.size() + 0.5) / (postings.size() + 0.5))
                    : unknownIdf;
            totalWeight += weight;
            if (matched) {
                matchedWeight += weight;
            }
        }
        return totalWeight <= 0.0 ? 0.0 : matchedWeight / totalWeight;
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
