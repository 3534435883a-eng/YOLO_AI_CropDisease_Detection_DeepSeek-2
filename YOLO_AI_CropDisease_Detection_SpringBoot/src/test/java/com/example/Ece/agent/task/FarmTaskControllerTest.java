package com.example.Ece.agent.task;

import com.example.Ece.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FarmTaskControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reportsValidationAndPersistenceErrorsThroughBusinessEnvelope() throws Exception {
        FarmTaskService service = mock(FarmTaskService.class);
        FarmTaskController controller = new FarmTaskController(service);
        String id = UUID.randomUUID().toString();
        when(service.refresh(id)).thenThrow(new IOException("任务暂不可读"));
        Result<?> unavailable = controller.read(id);
        assertEquals("FARM_TASK_UNAVAILABLE", unavailable.getCode());
        assertEquals("任务暂不可读", unavailable.getMsg());
        when(service.refresh("../bad")).thenThrow(new IllegalArgumentException("编号无效"));
        assertEquals("FARM_TASK_INVALID", controller.read("../bad").getCode());
    }

    @Test
    void observationRouteAppendsThenReturnsRefreshedArchive() throws Exception {
        FarmTaskService service = mock(FarmTaskService.class);
        FarmTaskController controller = new FarmTaskController(service);
        String id = UUID.randomUUID().toString();
        com.fasterxml.jackson.databind.node.ObjectNode input = mapper.createObjectNode().put("note", "已复查"), refreshed = mapper.createObjectNode().put("id", id);
        when(service.refresh(id)).thenReturn(refreshed);
        assertEquals(refreshed, controller.observations(id, input).getData());
        org.mockito.InOrder order = inOrder(service);
        order.verify(service).addObservation(id, input);
        order.verify(service).refresh(id);
    }

    @Test
    void reportIsUtf8MarkdownAttachmentAndMissingArchiveIsReadableError() throws Exception {
        FarmTaskService service = mock(FarmTaskService.class);
        FarmTaskController controller = new FarmTaskController(service);
        String id = UUID.randomUUID().toString();
        when(service.report(id)).thenReturn("# 农情管理报告\n\n番茄复查");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.report(id, response);
        assertEquals(200, response.getStatus());
        assertEquals("UTF-8", response.getCharacterEncoding());
        assertTrue(response.getHeader("Content-Disposition").contains(id + ".md"));
        assertTrue(response.getContentAsString().contains("番茄复查"));
        when(service.report(id)).thenThrow(new IOException("档案不存在"));
        MockHttpServletResponse missing = new MockHttpServletResponse();
        controller.report(id, missing);
        assertEquals(404, missing.getStatus());
        assertTrue(missing.getContentAsString().contains("档案不存在"));
    }
}
