# 独立问答保留 Implementation Plan

> 本会话逐项实施。按用户最新澄清执行，不再请求设计确认；不新增/运行自动测试，不提交Git。

**Goal:** 在现有界面保留普通独立问答，同时沿用关联大棚能力。

**Architecture:** Vue在两个会话状态间切换，使用独立sessionId；普通请求不传simulationRunId。Java按实际关联过滤工具目录并阻止无关联模拟调用，通用规划提示按用户作物回答。

**Tech Stack:** Vue3 / TypeScript / Element Plus；既有Spring Boot / DeepSeek。

**Spec:** ../specs/2026-10-02-independent-and-greenhouse-chat-design.md。

## Task 1: 入口及会话状态

Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/agentChat/index.vue`。

- [x] 加入两个入口；`general`和`greenhouse`状态分别包含`sessionId,crop,draft,attachVision,turns`。
- [x] 直接入口默认general，liveRun入口默认greenhouse；普通与关联使用对应示例和侧栏，不改正文样式。
- [x] `send`仅greenhouse传运行ID；重试恢复提问作物；切换不清空消息/草稿；具名agentChat匹配keep-alive。

## Task 2: 服务端范围

Modify: `SpringBoot/src/main/java/com/example/Ece/agent/tool/AgentToolRegistry.java`, `agent/orchestrator/AgentOrchestrator.java`（SpringBoot为项目后端目录）。

- [x] `catalogJson(boolean simulationAvailable, boolean greenhouseStateAvailable)`过滤当前不可用工具，保留旧无参接口。
- [x] `systemPrompt`使用实际关联标志，泛农业规划；执行循环再次拦截无关联模拟/温室调用。

## Task 3: 交付

- [x] Maven跳过测试生产打包、Vite生产构建；不运行自动测试。
- [x] 查看两个入口和消息/草稿保留，在浏览器保存截图；记录构建结果、后端重载导致的内存运行过期边界。

完成记录：../../research/2026-10-02-autonomous-showcase-results.md。
