package com.example.Ece.agent.vision;

import com.example.Ece.agent.vision.VisionLabels.Detection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用例形态全部取自 imgrecords 表 634 条真实记录的实测形态。 */
class VisionLabelsTest {

    /** BigDecimal 的 equals 会比较 scale（1 与 1.0000 不相等），数值断言必须用 compareTo。 */
    private void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual, "期望 " + expected + " 但为 null");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)), "期望 " + expected + " 实际 " + actual);
    }

    @Test
    void resolvesCropFromKindOrModelFile() {
        assertEquals("玉米", VisionLabels.cropOf("corn", "corn_best.pt"));
        assertEquals("玉米", VisionLabels.cropOf("corn1", "corn1_best.pt"), "第二个玉米模型应归到玉米");
        assertEquals("草莓", VisionLabels.cropOf(null, "strawberry_best.pt"), "kind 缺失时应回退到模型文件名");
        assertEquals("番茄", VisionLabels.cropOf("TOMATO", null), "代码大小写不敏感");
        assertNull(VisionLabels.cropOf("unknown_crop", "weird.pt"), "判定不出必须返回 null，不猜");
    }

    @Test
    void parsesSingleLabelArray() {
        List<Detection> detections = VisionLabels.parse("[\"Rust(玉米锈病)\"]", "[\"99.11%\"]");
        assertEquals(1, detections.size());
        assertEquals("Rust(玉米锈病)", detections.get(0).getLabel());
        assertDecimal("0.9911", detections.get(0).getConfidence());
    }

    /** 真实记录里存在 JSON 的 unicode 转义写法（反斜杠加 u 加四位十六进制）。 */
    @Test
    void parsesUnicodeEscapedLabel() {
        List<Detection> detections = VisionLabels.parse("[\"Rust(\\u7389\\u7c73\\u9508\\u75c5)\"]", "[\"99.11%\"]");
        assertEquals(1, detections.size());
        assertEquals("Rust(玉米锈病)", detections.get(0).getLabel());
    }

    /** 苹果的记录里是单个带引号的英文字符串，不是数组。 */
    @Test
    void parsesQuotedPlainString() {
        List<Detection> detections = VisionLabels.parse("\"Alternaria leaf spot\"", "\"88.5%\"");
        assertEquals(1, detections.size());
        assertEquals("Alternaria leaf spot", detections.get(0).getLabel());
        assertDecimal("0.885", detections.get(0).getConfidence());
    }

    @Test
    void parsesPlainTextWithoutQuotes() {
        List<Detection> detections = VisionLabels.parse("预测失败", null);
        assertEquals(1, detections.size());
        assertEquals("预测失败", detections.get(0).getLabel());
        assertNull(detections.get(0).getConfidence());
    }

    @Test
    void parsesMultipleLabelsAndDeduplicates() {
        List<Detection> two = VisionLabels.parse("[\"Blight(枯萎病)\",\"Rust(玉米锈病)\"]", "[\"91.89%\",\"93.24%\"]");
        assertEquals(2, two.size());
        assertEquals("Rust(玉米锈病)", VisionLabels.primary(two).getLabel(), "主检测应取置信度更高的那条");

        List<Detection> duplicated = VisionLabels.parse("[\"FAW_Lv(秋军虫幼虫病)\",\"FAW_Lv(秋军虫幼虫病)\"]",
                "[\"93.24%\",\"91.49%\"]");
        assertEquals(1, duplicated.size(), "同一行重复标签只保留一条");
    }

    /** 真实数据里有全角括号（blight（疫病）），与半角写法混用。 */
    @Test
    void normalizesFullWidthParentheses() {
        assertEquals("blight(疫病)", VisionLabels.normalizeLabel("blight（疫病）"));
        List<Detection> detections = VisionLabels.parse("[\"blight（疫病）\"]", null);
        assertEquals("blight(疫病)", detections.get(0).getLabel());
    }

    @Test
    void convertsPercentAndDecimalConfidence() {
        assertDecimal("0.9911", VisionLabels.toConfidence("99.11%"));
        assertDecimal("0.16", VisionLabels.toConfidence("0.16"));
        assertDecimal("1", VisionLabels.toConfidence("100%"));
        assertNull(VisionLabels.toConfidence("不可解析"));
        assertNull(VisionLabels.toConfidence(null));
    }

    /** 置信度缺失时主检测取第一条，而不是丢掉整条记录。 */
    @Test
    void primaryFallsBackWhenConfidenceMissing() {
        List<Detection> detections = Arrays.asList(
                new Detection("A", null), new Detection("B", new BigDecimal("0.9")));
        assertEquals("B", VisionLabels.primary(detections).getLabel());

        List<Detection> noConfidence = Arrays.asList(new Detection("A", null), new Detection("B", null));
        assertEquals("A", VisionLabels.primary(noConfidence).getLabel());
        assertNull(VisionLabels.primary(Arrays.<Detection>asList()));
    }

    @Test
    void keepsLabelsAndConfidencesAligned() {
        List<Detection> detections = VisionLabels.parse("[\"A\",\"B\",\"C\"]", "[\"90%\",\"80%\"]");
        assertEquals(3, detections.size());
        assertNotNull(detections.get(0).getConfidence());
        assertNotNull(detections.get(1).getConfidence());
        assertNull(detections.get(2).getConfidence(), "置信度数组短于标签时应为 null 而不是错位");
        assertTrue(detections.get(0).getConfidence().compareTo(detections.get(1).getConfidence()) > 0);
    }
}