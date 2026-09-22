package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文 bigram 分词：连续汉字按 2-gram 切分，ASCII 字母数字作整词小写。
 * 零外部依赖，保证检索结果可复现。
 */
@Component
public class ChineseBigramTokenizer {

    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) {
            return tokens;
        }
        StringBuilder ascii = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isIdeographic(current)) {
                flushAscii(ascii, tokens);
                han.append(current);
            } else if (Character.isLetterOrDigit(current)) {
                flushHan(han, tokens);
                ascii.append(Character.toLowerCase(current));
            } else {
                flushAscii(ascii, tokens);
                flushHan(han, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private void flushAscii(StringBuilder builder, List<String> tokens) {
        if (builder.length() > 0) {
            tokens.add(builder.toString());
            builder.setLength(0);
        }
    }

    private void flushHan(StringBuilder builder, List<String> tokens) {
        if (builder.length() == 0) {
            return;
        }
        if (builder.length() == 1) {
            tokens.add(builder.toString());
        } else {
            for (int i = 0; i + 1 < builder.length(); i++) {
                tokens.add(builder.substring(i, i + 2));
            }
        }
        builder.setLength(0);
    }
}
