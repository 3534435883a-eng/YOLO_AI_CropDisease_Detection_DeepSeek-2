package com.example.Ece.agent.rag;

/** 带分数的检索命中，rank 从 1 开始。 */
public class ScoredChunk {

    private final KnowledgeChunk chunk;
    private final double score;
    private int rank;

    public ScoredChunk(KnowledgeChunk chunk, double score, int rank) {
        this.chunk = chunk;
        this.score = score;
        this.rank = rank;
    }

    public KnowledgeChunk getChunk() { return chunk; }

    public double getScore() { return score; }

    public int getRank() { return rank; }

    public void setRank(int rank) { this.rank = rank; }
}
