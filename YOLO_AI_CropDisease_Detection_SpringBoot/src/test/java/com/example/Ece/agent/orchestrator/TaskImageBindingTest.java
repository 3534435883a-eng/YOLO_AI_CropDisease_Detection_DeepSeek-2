package com.example.Ece.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskImageBindingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode context() throws Exception {
        return (ObjectNode) mapper.readTree("{\"task\":{\"crop\":\"番茄\",\"evidence\":[{\"id\":\"photo-1\",\"type\":\"IMAGE\",\"label\":\"Early_Blight(早疫病)\",\"candidates\":[{\"label\":\"Early_Blight(早疫病)\",\"score\":\"86.77%\"},{\"label\":\"Late_Blight(晚疫病)\",\"score\":\"2%\"}]}]}}");
    }
    @Test void symptomCannotBecomeDetectorClassAndFrozenEvidenceIsUnchanged() throws Exception {
        ObjectNode frozen = context(), original = frozen.deepCopy();
        Map<String,Object> input = new LinkedHashMap<>(); input.put("classLabel", "褐斑"); input.put("crop", "苹果");
        AgentOrchestrator.bindTaskImage(input, frozen);
        assertEquals("Early_Blight(早疫病)", input.get("classLabel"));
        assertEquals("番茄", input.get("crop")); assertEquals("photo-1", input.get("evidenceId"));
        assertEquals(original, frozen);
    }
    @Test void requestedSavedAlternativeCandidateIsPreserved() throws Exception {
        Map<String,Object> input = new LinkedHashMap<>(); input.put("classLabel", "Late_Blight(晚疫病)");
        AgentOrchestrator.bindTaskImage(input, context());
        assertEquals("Late_Blight(晚疫病)", input.get("classLabel"));
        assertEquals("photo-1", input.get("evidenceId"));
    }
    @Test void withoutImageIndependentToolArgumentsAreUnchanged() {
        Map<String,Object> input = new LinkedHashMap<>(); input.put("classLabel", "用户指定类别");
        Map<String,Object> original = new LinkedHashMap<>(input);
        AgentOrchestrator.bindTaskImage(input, mapper.createObjectNode()); assertEquals(original, input);
    }
}
