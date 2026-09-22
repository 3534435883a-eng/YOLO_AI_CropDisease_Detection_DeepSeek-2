package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseBigramTokenizerTest {

    private final ChineseBigramTokenizer tokenizer = new ChineseBigramTokenizer();

    @Test
    void splitsChineseIntoBigrams() {
        assertEquals(Arrays.asList("番茄", "茄早", "早疫", "疫病"), tokenizer.tokenize("番茄早疫病"));
    }

    @Test
    void keepsAsciiWordsLowercasedAndDigits() {
        List<String> tokens = tokenizer.tokenize("Tomato 早疫病 2025");
        assertTrue(tokens.contains("tomato"));
        assertTrue(tokens.contains("2025"));
        assertTrue(tokens.contains("早疫"));
        assertFalse(tokens.contains("Tomato"));
    }

    @Test
    void blankInputYieldsNothing() {
        assertTrue(tokenizer.tokenize("   ").isEmpty());
        assertTrue(tokenizer.tokenize(null).isEmpty());
    }
}
