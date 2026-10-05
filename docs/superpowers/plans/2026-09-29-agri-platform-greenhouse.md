# 农业平台温室仿真与数字孪生实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 整理温室仿真控制、环境与档案、数字孪生回放，使规则仿真和离线评测在演示中清楚可读。

**Architecture:** 保持 `agentCenter` 现有 API/store 运行链和 `digitalTwin` Three.js 场景/帧读取；只调整工作区布局、控制面板层级、响应式空间和来源说明，不更换计算或三维模型。

**Tech Stack:** Vue 3、TypeScript、Pinia、Element Plus、Three.js、ECharts、SCSS。

**Spec:** `docs/superpowers/specs/2026-09-29-agri-platform-redesign-design.md`

## Global Constraints

- 不改后端计算逻辑、数据库结构、API 契约、权限或业务语义。
- 所有温室规则仿真、离线评测、虚拟设备状态和模型假设均清楚标明，不得呈现为实测或真实设备控制。
- 三维场景是主视觉内容；信息侧栏可收展，控制和指标不得遮挡主要观察区域。
- 运行创建、开始、暂停、单步、回放与重置功能继续可用。

---

### Task 1：重排智能体指挥中心

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/agentCenter/index.vue`

- [ ] **Step 1: 依据运行生命周期安排桌面区块**

  将已有温室/作物场景、仿真配置、运行创建和运行控制作为第一层；将当前步、摘要、告警、设备动作、资源流水作为后续运行证据层。创建运行与执行控制使用明确的主次按钮，状态文字与颜色同时表达。

- [ ] **Step 2: 保留原控制、接口和危险操作确认**

  不改运行状态 store、运行控制 API 或规则字段；开始/暂停/单步/回放仍调用现有处理函数。重置前保留现有确认弹窗，文案说明会清空本次仿真的快照、设备动作和资源流水。

- [ ] **Step 3: 手动检查运行状态**

  查看无活动运行、运行中、暂停、完成、服务不可用五类现有页面状态，操作可用的运行控制并检查告警和资源记录区域可读。

### Task 2：突出数字孪生画面与时间回放

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/index.vue`

- [ ] **Step 1: 让三维画布占据主内容区**

  保持现有 Canvas、相机交互、性能档位和设备渲染；将可收展指标放入侧面 HUD，将时间/播放/视角控件靠边成组，避免控制条盖住作物主要区域。

- [ ] **Step 2: 分开 15 分钟运行和日级离线评测**

  继续使用现有模式切换与数据调用；在模式名称和指标附近标示“保存的规则仿真快照”或“离线评测/示例数据”。服务错误、缺失帧和不完整旧快照沿用明确状态，不用虚构序列补位。

- [ ] **Step 3: 手动查看相机和回放控制**

  在已有场景中操作预设视角、薄膜模式、侧栏展开、设备目录、15 分钟时间轴与日级播放；确认切换页签时 Three.js 画布尺寸正确，控制和设备标记未被遮挡。

### Task 3：统一环境和温室档案

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/detailsEnv/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/infoGreenhouse/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/infoGreenhouse/dialog.vue`

- [ ] **Step 1: 以来源为标题层级的一部分**

  环境值附近持续显示实测、仿真地/模拟位置或不可用状态；温室档案列表和编辑弹窗沿用已建立的暖纸表格、表单、间距和状态标签。不改表单字段和提交 API。

- [ ] **Step 2: 手动检查空/错/成功页面状态**

  查看环境接口成功、接口不可用或字段缺失时的现有分支；查看温室列表、详情及新增/编辑弹窗。确认来源不是只通过颜色表示。

### Task 4：温室子系统整体走查

**Files:**
- Review all changes in `agentCenter`, `digitalTwin`, `detailsEnv`, and `infoGreenhouse`.

- [ ] **Step 1: 检查桌面视口**

  在 1440×900 和 1280×800 下分别打开指挥中心和数字孪生；确认主操作、来源注释、指标面板、时间轴和 Canvas 都可见且无需页面横向滚动。

- [ ] **Step 2: 核查只读与模拟边界**

  从页面文字和控制状态核实没有实物遥测/执行器/现场标定承诺；数据为空或接口失败时页面没有默认填入貌似实测的数值。
