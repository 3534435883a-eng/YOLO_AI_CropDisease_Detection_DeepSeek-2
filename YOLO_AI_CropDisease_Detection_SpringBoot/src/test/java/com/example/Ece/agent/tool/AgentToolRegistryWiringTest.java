package com.example.Ece.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具接线测试：验证智能体**真的看得见**平台类工具。
 *
 * <p>为什么需要它：工具是靠 Spring 自动发现（{@code @Autowired(required=false) List&lt;AgentTool&gt;}），
 * 而模型是靠 system prompt 里的 {@code registry.catalogJson()} 得知有哪些工具可用
 * （见 {@code AgentOrchestrator.systemPrompt()}）。
 * 单测能证明工具逻辑正确，但证明不了它被注册进目录——漏了注册，
 * 工具就是一段永远不被调用的死代码，而且**编译和测试都不会报错**。</p>
 */
@SpringBootTest(properties = "agent.knowledge.bootstrap=false")
class AgentToolRegistryWiringTest {

    @Autowired
    private AgentToolRegistry registry;

    @Test
    void platformToolsAreDiscoveredAndExposedInTheCatalog() {
        Set<String> names = new HashSet<String>();
        for (AgentTool tool : registry.all()) {
            names.add(tool.name());
        }
        assertTrue(names.contains(KnowledgeSearchTool.NAME), "缺少知识检索工具");
        assertTrue(names.contains(VisionExplainTool.NAME), "缺少视觉映射工具");
        assertTrue(names.contains(GreenhouseStateTool.NAME), "缺少温室状态工具：智能体将看不见棚");
        assertTrue(names.contains(PrescriptionDraftTool.NAME), "缺少处方拟制工具：智能体将给不出方案");
        assertTrue(names.contains(ProductionReportTool.NAME), "缺少生产规划报告工具：智能体将出不了报告");
    }

    @Test
    void everyToolAppearsInThePromptCatalogWithNameAndDescription() {
        String catalog = registry.catalogJson();
        assertNotNull(catalog);
        for (AgentTool tool : registry.all()) {
            assertTrue(catalog.contains("\"" + tool.name() + "\""),
                    "工具目录里没有 " + tool.name() + "，模型不会知道它存在");
            assertTrue(catalog.contains(tool.permission().name()),
                    "工具目录里没有 " + tool.name() + " 的权限标注");
        }
        assertTrue(catalog.contains("\"inputSchema\""), "目录应带参数 schema，否则模型无从构造调用");
    }

    /**
     * 权限模型此前是空头设计：{@code ToolPermission} 声明了 DRAFT / WRITE_REQUIRES_APPROVAL，
     * 但没有任何工具使用它们。本测试锁住"三档里至少两档真的在用"，
     * 并断言**当前没有任何写工具**——写权限一旦被引入，应当是有人明确决策的结果。
     */
    @Test
    void permissionLevelsAreActuallyUsedAndNoWriteToolExistsYet() {
        Set<ToolPermission> used = new HashSet<ToolPermission>();
        for (AgentTool tool : registry.all()) {
            used.add(tool.permission());
        }
        assertTrue(used.contains(ToolPermission.READ_ONLY), "应有只读工具");
        assertTrue(used.contains(ToolPermission.DRAFT), "应有草案级工具（处方拟制、报告生成）");
        assertTrue(!used.contains(ToolPermission.WRITE_REQUIRES_APPROVAL),
                "当前不应存在写工具；引入写权限须是明确决策并配套人工确认流程");
        assertEquals(5, registry.all().size(), "工具数量变化时应显式更新本测试，避免漏注册或误删");
    }
}
