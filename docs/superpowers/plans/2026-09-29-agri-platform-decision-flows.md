# 农业平台观察与决策流程实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重整识别、知识决策与农事规划页面，让用户能沿观察→研判→方案流程操作并理解数据证据来源。

**Architecture:** 页面继续使用当前 Vue 视图、API、组件与 store；共享识别导航只负责页面跳转和模式状态。知识检索型 `agentChat` 保持其引用证据链，`agentSimulation` 保持独立推演、解析确认和基线对比。

**Tech Stack:** Vue 3、TypeScript、Vue Router、Pinia、Element Plus、SCSS、现有 SSE 与 CSV 上传接口。

**Spec:** `docs/superpowers/specs/2026-09-29-agri-platform-redesign-design.md`

## Global Constraints

- 不改后端计算逻辑、数据库结构、API 契约、权限或业务语义。
- 图片、视频、摄像识别结果只显示为候选观察信号；缺少依据时明确显示暂无可核对条目。
- 决策助手展示知识检索与引用；农事规划推演是单独的模型通道，输入解析必须可核对。
- 现有表单、上传、记录筛选、图表和导出交互继续可用。

---

### Task 1：统一检测与识别记录工作区

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/components/detectionNav/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/imgPredict/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/videoPredict/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/cameraPredict/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/imgRecord/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/videoRecord/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/cameraRecord/index.vue`

- [ ] **Step 1: 统一检测导航视觉**

  保持组件内的 `paths` 映射不变；将标题、图片/视频/摄像切换、检测/历史切换和次要操作排成同一工作区页头。当前模式与当前页面状态必须有文字/控件状态，不只用颜色标识。

- [ ] **Step 2: 整理图片检测工作区**

  保留现有上传与预览、作物/模型参数、开始检测、检测结果、错误和识别证据行为；将输入区与结果区分开，上传前显示空状态，上传后以大图为主显示候选框和结果说明。

- [ ] **Step 3: 整理视频及摄像检测工作区**

  在 `videoPredict/index.vue` 保持视频选择/播放和逐帧结果；在 `cameraPredict/index.vue` 保持摄像授权、预览和开始/停止检测。输入源状态、检测状态和错误文案各自清楚可见，不改变相机授权流程。

- [ ] **Step 4: 统一三类识别记录页**

  图片、视频、摄像记录的搜索/过滤、表格、分页、详情和删除确认采用全站 Element Plus 主题；不删除字段、API 调用或既有角色权限。

- [ ] **Step 5: 手动查看观察工作区**

  分别打开三个检测页与对应记录页，检查输入状态、识别错误、结果/详情和分页；验证 `DetectionNav` 的六个跳转都进入正确 path。

### Task 2：整理知识研判页面

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/agentChat/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/infoDisease/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/infoDisease/detail.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/visionCoverage/index.vue`

- [ ] **Step 1: 强化决策助手的回答与证据层级**

  保留现有会话历史、工具结果和消息交互；每个回答区分结论、建议、引用条目、来源和引用缺口提示。保持知识检索型决策路径，不复用独立农事规划页的解析/导入控件。

- [ ] **Step 2: 保留病害知识与覆盖信息**

  病害列表保持搜索、筛选、分页和详情；详情保留症状、原因、防治建议和图片。视觉—知识覆盖页继续展示可核对覆盖与缺口，说明“暂无对应条目”不是健康结论。

- [ ] **Step 3: 手动核对证据视图**

  打开有引用、有缺口的对话状态、病害详情和视觉覆盖页，确认来源文字、空状态、图片、筛选和分页均可读并可到达。

### Task 3：整理独立农事规划流程

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/agentSimulation/index.vue`

- [ ] **Step 1: 将页面分成输入、核对、推演和结果区**

  保留人工录入、文本解析、CSV 上传及列映射；解析出的农情先显示为可确认草稿，然后使用现有 SSE 推演生成策略、展示基线比较、历史记录和 Markdown 导出。每步按此顺序呈现，当前步骤有可见标题和状态。

- [ ] **Step 2: 显示来源和错误边界**

  明示该页是独立模型推演；上传或解析后的内容是待确认农情，不是实测；保留上传错误、流中断、历史为空和基线不可用的现有错误分支，不替换为默认成功样例。

- [ ] **Step 3: 手动核对农事规划链路**

  使用当前环境可用的文本或 CSV 样例走一遍解析预览与确认，查看策略和基线结果；后端不可用时核对服务错误提示。检查模板下载、历史打开和 Markdown 导出按钮仍可用。
