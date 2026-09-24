package com.example.Ece.agent.guard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 校验回答中的 [n] 是否全部指向当前检索结果。 */
public class CitationReferenceValidator {
    private static final Pattern REFERENCE = Pattern.compile("\\[(\\d+)\\]");

    public boolean hasValidReferences(String answer, int citationCount) {
        if (answer == null || citationCount <= 0) {
            return false;
        }
        Matcher matcher = REFERENCE.matcher(answer);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException error) {
                return false;
            }
            if (index < 1 || index > citationCount) {
                return false;
            }
        }
        return found;
    }
}
