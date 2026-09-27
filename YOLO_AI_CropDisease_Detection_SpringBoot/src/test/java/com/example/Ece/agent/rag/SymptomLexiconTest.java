package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 症状口语词表的测试。
 *
 * <p>锁住的是**机制**：口语词要能补出规范词、已经写对的不要重复补、单字口语词不收录
 * （会在任意含该字的文本里误匹配）。数值效果由 {@code RetrievalEvalTest} 的口语化组度量。</p>
 */
class SymptomLexiconTest {

    private final SymptomLexicon lexicon = new SymptomLexicon();

    @Test
    void loadsMappingsFromResource() {
        assertFalse(lexicon.isEmpty(), "症状词表未加载——口语化检索会退回到接线前的水平");
        assertTrue(lexicon.size() >= 8, "映射条数过少，实测 " + lexicon.size());
        assertEquals("2026-09-27", lexicon.getVersion());
    }

    /** 农户说"一圈一圈"，语料写"同心轮纹"——必须补出后者。 */
    @Test
    void appendsCanonicalTermForColloquialPhrase() {
        String expanded = lexicon.expandQuery("番茄老叶上有一圈一圈的褐色病斑");
        assertTrue(expanded.contains("同心轮纹"), "未补出规范词：" + expanded);
        assertTrue(expanded.startsWith("番茄老叶上有一圈一圈"), "必须保留用户原话（追加而非替换）：" + expanded);
    }

    /** 已经用了规范词的问句不应被重复追加——重复会让词频失真。 */
    @Test
    void doesNotDuplicateCanonicalTermAlreadyPresent() {
        String query = "番茄老叶上有同心轮纹的褐色病斑";
        assertEquals(query, lexicon.expandQuery(query));
    }

    /** 单个口语字（如"烂""倒"）不收录：它们会在任意含该字的文本里误匹配。 */
    @Test
    void ignoresSingleCharacterColloquialTerms() {
        assertTrue(lexicon.canonicalTermsFor("叶子烂了").isEmpty(),
                "单字口语词不得触发扩展");
    }

    @Test
    void returnsDeduplicatedCanonicalTermsInOrder() {
        assertTrue(lexicon.canonicalTermsFor("根上长小疙瘩，也不长个")
                .containsAll(Arrays.asList("瘤状物", "矮化")));
        assertEquals(lexicon.canonicalTermsFor("小疙瘩").size(),
                new java.util.LinkedHashSet<String>(lexicon.canonicalTermsFor("小疙瘩")).size(),
                "规范词不得重复");
    }

    /** 空输入与不含口语词的问句都必须原样返回，不得抛错。 */
    @Test
    void handlesEmptyAndUnrelatedQueries() {
        assertEquals(null, lexicon.expandQuery(null));
        assertEquals("", lexicon.expandQuery(""));
        String unrelated = "番茄早疫病用什么药";
        assertEquals(unrelated, lexicon.expandQuery(unrelated));
        assertTrue(lexicon.canonicalTermsFor(null).isEmpty());
    }
}
