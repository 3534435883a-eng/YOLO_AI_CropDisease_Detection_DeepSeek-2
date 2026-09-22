package com.example.Ece.agent.tool;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.EmbeddingUnavailableException;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchToolTest {

    private KnowledgeSearchTool toolWith(final boolean degraded) {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) throws EmbeddingUnavailableException {
                if (degraded) {
                    throw new EmbeddingUnavailableException("down");
                }
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(Arrays.asList(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1")));
        return new KnowledgeSearchTool(retriever, new CitationFormatter());
    }

    @Test
    void exposesMetadata() {
        KnowledgeSearchTool tool = toolWith(false);
        assertEquals("knowledge.search", tool.name());
        assertEquals(ToolPermission.READ_ONLY, tool.permission());
        assertTrue(tool.inputSchemaJson().contains("query"));
    }

    @Test
    void returnsCitationsAndDegradedFlag() throws ToolException {
        Map<String, Object> output = toolWith(true).execute(new HashMap<String, Object>(
                Collections.singletonMap("query", "褐色轮纹斑")));
        assertEquals(Boolean.TRUE, output.get("degraded"));
        assertEquals("EMBEDDING_UNAVAILABLE", output.get("degradedReason"));
        assertFalse(((List<?>) output.get("citations")).isEmpty());
    }

    @Test
    void rejectsMissingQuery() {
        assertThrows(ToolException.class, () -> toolWith(false).execute(new HashMap<String, Object>()));
    }
}
