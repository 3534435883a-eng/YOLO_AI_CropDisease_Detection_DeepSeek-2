# 农业平台管理页与整体验收实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一采购、库存、用户和个人设置页面的视觉，并完成桌面改版的构建、来源边界与导航验收。

**Architecture:** 管理页继续使用既有 CRUD、Element Plus 表格和弹窗，通过已建立的全站 SCSS/Element Plus 主题统一密度与色彩；最终检查跨模块导航、桌面布局和模拟来源说明。

**Tech Stack:** Vue 3、TypeScript、Element Plus、SCSS、Vite。

**Spec:** `docs/superpowers/specs/2026-09-29-agri-platform-redesign-design.md`

## Global Constraints

- 不改后端计算逻辑、数据库结构、API 契约、权限或业务语义。
- 表单字段、筛选条件、表格字段、分页与弹窗操作继续可用。
- 主要验收视口为 1440×900 和 1280×800；常见桌面宽度下不能横向溢出遮挡核心内容。
- 前端 package 没有配置自动化 test 脚本，不新建测试文件；使用生产构建和浏览器人工验收。

---

### Task 1：统一管理页密度和表单

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/purchaseManage/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/purchaseManage/dialog.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/storageManage/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/storageManage/dialog.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/userManage/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/userManage/dialog.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/personal/index.vue`

- [ ] **Step 1: 统一列表页的标题与工具栏**

  在采购、库存和用户列表里对齐页标题、搜索/过滤工具栏、主操作和表格的间距；用全站纸白表面、主题边框、深木色标题和陶土色主按钮。保留列、筛选项、操作列、分页、导出和角色权限。

- [ ] **Step 2: 统一弹窗和个人设置样式**

  在三类管理弹窗和 `personal/index.vue` 使用全站输入框、标签、帮助说明、错误态与按钮主题。保留字段顺序、必填校验、上传、请求方法和成功/失败提示逻辑。

- [ ] **Step 3: 浏览管理操作流程**

  手动打开采购新增/编辑、库存搜索/分页、用户新增/编辑、个人设置；确认空表格、加载态、校验提示和二次确认仍可触达，内容在 1280×800 不被裁掉。

### Task 2：整个平台集成与交付

**Files:**
- Review all changed Vue/SCSS/router files from the four redesign work streams.
- No new automated test files.

- [ ] **Step 1: 运行前端生产构建**

  在 `YOLO_AI_CropDisease_Detection_Vue/` 执行 `npm run build`；只处理本次 UI 改版引入的 Vue、TypeScript、SCSS、模板或路由错误。

- [ ] **Step 2: 检查完整主流程和两种 AI 通道**

  在浏览器从病害识别进入决策助手核对引用；从农事规划输入/上传到核对、推演和基线；从指挥中心进入数字孪生回放。确认流程入口对得上页面标题，两个 AI 通道一直分开。

- [ ] **Step 3: 检查桌面导航、空状态和数据来源**

  在 1440×900 与 1280×800 打开工作台、识别、决策、规划、指挥中心、数字孪生、知识库和一个管理页。确认主内容无横向溢出、操作未遮挡、没有“查看答辩路线”与“开始演示”首页按钮，规则仿真/离线样例/视觉信号/实测来源文案均可辨认。

- [ ] **Step 4: 检查删除边界并打开预览**

  在 Vue 源码中搜索 `/dataView`、`数据大屏`、`/data/index.html`，确认旧数据大屏无菜单入口、路由和悬空导入；在变更差异中确认没有后端、API 或数据结构调整。用 Vue 开发服务打开工作台并保留浏览器预览，汇报构建结果和实际可走查的流程。
