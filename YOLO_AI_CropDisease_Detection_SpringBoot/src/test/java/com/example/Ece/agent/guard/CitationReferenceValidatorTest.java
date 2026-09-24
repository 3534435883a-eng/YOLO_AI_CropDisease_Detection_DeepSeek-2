package com.example.Ece.agent.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationReferenceValidatorTest {
    private final CitationReferenceValidator validator = new CitationReferenceValidator();

    @Test
    void requiresAtLeastOneCurrentReference() {
        assertFalse(validator.hasValidReferences("建议复核叶片症状。", 2));
        assertTrue(validator.hasValidReferences("建议复核叶片症状。[1]", 2));
    }

    @Test
    void rejectsAnyFabricatedReferenceNumber() {
        assertFalse(validator.hasValidReferences("可能为早疫病。[1][3]", 2));
        assertFalse(validator.hasValidReferences("可能为早疫病。[0]", 2));
    }
}
