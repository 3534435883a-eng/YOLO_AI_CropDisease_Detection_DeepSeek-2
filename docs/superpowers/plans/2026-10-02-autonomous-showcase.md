# 一键自动接管与校准展示 Implementation Plan

> 本会话实施。用户明确授权功能和答辩展示；不另询问批准，不运行自动测试，不提交Git。

**Goal:** 将现有M3/AI流程变为一键启动、可暂停、易讲解的大棚展示。

**Architecture:** M3LiveWorkbench提供`startAutomatic():Promise<void>`与播放状态事件，复用M3场景API；大棚切换精简展示HUD，后台工作台持续接收状态。独立CalibrationStory读取已保存历史结果和当前在线快照，分开呈现参数校准与状态纠偏。

**Tech Stack:** Vue3 / TypeScript / ECharts / Three.js；现有Spring Boot M3接口。

**Spec:** ../specs/2026-10-02-autonomous-showcase-design.md。

- [x] 修改M3LiveWorkbench：一键接管复用运行，固定单步、启用自动事件与AI；发布播放状态；持续读取快照，避免隐藏控制台后失去AI反馈。
- [x] 创建AutonomousShowcase.vue：环境、事件、AI设备、反馈、阶段进度、暂停恢复/退出/关联问答入口；加载、失败和未解决均准确显示。
- [x] 创建CalibrationStory.vue：历史三年结果及原模型/参数校准/观测曲线；当前在线预测与最近实测更新分开表示；当前无观测不伪造更新。
- [x] 修改digitalTwin/index.vue：一键入口、组件编排、校准按钮、旧完整面板保留；退出暂停但不删运行。
- [x] 完成两类问答隔离、前后端生产构建、实际界面检查及截图；编写项目答辩叙事与完成记录。

完成记录：../../research/2026-10-02-autonomous-showcase-results.md。
