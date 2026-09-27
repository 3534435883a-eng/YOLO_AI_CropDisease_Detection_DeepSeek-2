package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「问了一个语料里根本没有的病/虫」检测的单元测试。
 *
 * <p>用固定例子锁住机制，避免只靠评测集数字判断——评测集是开发集，
 * 而这里的例子钉的是**判定规则本身**（哪里算名字结尾、哪里不算）。</p>
 */
class UnknownEntityDetectorTest {

    private static Set<String> inventory(String... names) {
        return new LinkedHashSet<String>(Arrays.asList(names));
    }

    // ---------------- 候选串抽取 ----------------

    @Test
    void extractsPestNameFollowedByQuestionWord() {
        List<String> tokens = UnknownEntityDetector.candidateTokens("玉米黄秆虫怎么防治？");
        assertTrue(tokens.contains("玉米黄秆虫"), "应抽出虫名，实际 " + tokens);
    }

    /** 「病斑」是病**部**位而不是病名——若把它当名字，全部症状描述都会被误判。 */
    @Test
    void doesNotTreatSymptomWordAsDiseaseName() {
        assertEquals(0, UnknownEntityDetector.candidateTokens(
                "番茄下面老叶上有一圈一圈同心轮纹的褐色病斑").size());
        assertEquals(0, UnknownEntityDetector.candidateTokens(
                "水稻叶片有梭形病斑中间灰白边缘褐色").size());
    }

    /** 「有虫」「若虫」「成虫」这些是描述，不是名字。 */
    @Test
    void doesNotTreatStageOrGenericWordsAsNames() {
        assertEquals(0, UnknownEntityDetector.candidateTokens("玉米心叶被咬出一排排孔，穗子上有虫").size());
        assertEquals(0, UnknownEntityDetector.candidateTokens("若虫和成虫刺吸叶片汁液").size());
        assertEquals(0, UnknownEntityDetector.candidateTokens("幼虫取食叶肉").size());
    }

    /** 虫态字跟在虫名之后时，名到「虫」为止，虫态字不并入。 */
    @Test
    void stopsAtNameEndWhenStageWordFollows() {
        List<String> tokens = UnknownEntityDetector.candidateTokens("玉米秋军虫幼虫怎么防治？");
        assertTrue(tokens.contains("玉米秋军虫"), "应在虫态字前截断，实际 " + tokens);
        assertFalse(tokens.contains("玉米秋军虫幼虫"), "虫态字不应并入名字，实际 " + tokens);
    }

    // ---------------- 存在性判定 ----------------

    /** 同病名换作物就是没依据：库里有「棉花黑根腐病」，不等于有「苹果黑根腐病」。 */
    @Test
    void sameNameUnderAnotherCropIsNotEvidence() {
        assertFalse(UnknownEntityDetector.isKnown("苹果黑根腐病", inventory("苹果锈病", "锈病")));
        assertTrue(UnknownEntityDetector.isKnown("苹果黑根腐病", inventory("棉花黑根腐病", "黑根腐病")));
    }

    /**
     * 双向子串：病名带修饰或前缀时仍应判为已知。
     *
     * <p>症状原文里常写「与早疫病相似」「比早疫病轻」，截出来的候选串会含多余的字；
     * 若要求严格相等，这些正常提问会被误拒。</p>
     */
    @Test
    void substringMatchToleratesModifiers() {
        Set<String> tomato = inventory("番茄早疫病", "早疫病", "番茄晚疫病", "晚疫病");
        assertTrue(UnknownEntityDetector.isKnown("与早疫病", tomato));
        assertTrue(UnknownEntityDetector.isKnown("番茄早疫病的", tomato));
        assertTrue(UnknownEntityDetector.isKnown("早疫病", tomato));
    }

    /** 清单为空（语料为空）时不判未知——不因缺数据而加重拒答。 */
    @Test
    void emptyInventoryDoesNotFlagAnything() {
        assertTrue(UnknownEntityDetector.isKnown("黄秆虫", new LinkedHashSet<String>()));
        assertNull(UnknownEntityDetector.firstUnknownToken("黄秆虫怎么防治", new LinkedHashSet<String>()));
    }

    @Test
    void returnsFirstUnknownTokenAndNullWhenAllKnown() {
        Set<String> corn = inventory("玉米大斑病", "大斑病", "玉米螟", "螟");
        assertEquals("玉米黄秆虫",
                UnknownEntityDetector.firstUnknownToken("玉米黄秆虫怎么防治？", corn));
        assertNull(UnknownEntityDetector.firstUnknownToken("玉米大斑病怎么防治？", corn));
    }

    /** 不含任何病/虫名的问句不该被本检测触碰——它要由别的判据处理。 */
    @Test
    void questionWithoutEntityIsLeftAlone() {
        Set<String> tomato = inventory("番茄早疫病", "早疫病");
        assertNull(UnknownEntityDetector.firstUnknownToken(
                "棚内湿度高但没有病害症状，是否需要改变灌溉策略？", tomato));
        assertNull(UnknownEntityDetector.firstUnknownToken(
                "番茄第一穗果开始膨大了，现在该怎么浇水、追什么肥？", tomato));
    }
}
