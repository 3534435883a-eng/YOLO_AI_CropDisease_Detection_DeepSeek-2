# 农业平台基础框架与工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建好全站桌面视觉主题与工作流导航，重排工作台，并删除数据大屏入口和专属页面。

**Architecture:** 在现有 Vue 3 + SCSS + Element Plus 应用框架上设定纸白、深木棕、陶土红和橄榄绿主题；`workspaceMenu.ts` 负责可见分组，`route.ts` 负责页面注册；工作台继续消费当前 Pinia 与 API 数据。

**Tech Stack:** Vue 3、TypeScript、Vue Router、Pinia、Element Plus、SCSS。

**Spec:** `docs/superpowers/specs/2026-09-29-agri-platform-redesign-design.md`

## Global Constraints

- 不改后端计算逻辑、数据库结构、API 契约、权限或业务语义。
- 页面主要目标视口为 1440×900；常见桌面宽度下侧栏、表格、三维视口和主流程可读、可操作，无横向溢出遮住核心操作。
- `/dataView` 不再出现在菜单中，也不再加载旧数据大屏页面。
- 工作台不含“查看答辩路线”和“开始演示”按钮；创建/控制仿真的操作留在指挥中心。
- 所有样例、规则仿真、视觉候选和真实观测均如实标明，页面不得把虚拟状态描述成真实设备控制或实测结果。

---

### Task 1：应用田间档案主题和桌面框架

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/stores/themeConfig.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/theme/app.scss`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/theme/element.scss`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/layout/component/aside.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/layout/logo/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/layout/navMenu/vertical.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/layout/navBars/index.vue`

- [ ] **Step 1: 定义主题值并接入主题默认项**

  将下列 CSS 自定义属性放进 `app.scss` 的 `:root`，并用这些变量替换主背景和组件中的重复色值：

  ```scss
  :root {
    --agri-paper: #f3eddf;
    --agri-surface: #fcf8ef;
    --agri-wood: #342721;
    --agri-terracotta: #96462f;
    --agri-olive: #718560;
    --agri-ink: #352d27;
    --agri-muted: #76695c;
    --agri-line: #ded2be;
  }
  ```

  在 `themeConfig.ts` 同步默认 `primary`, `topBar`, `topBarColor`, `menuBar`, `menuBarColor`, `menuBarActiveColor` 与纸白/木棕主题；不清除用户浏览器已有设置。布局默认仍是 `defaults`，不删除现有设置面板。

- [ ] **Step 2: 统一 Element Plus 控件**

  在 `element.scss` 用 `--agri-terracotta` 设置主按钮和激活态，用 `--agri-line` 设置表格/输入边框，用 `--agri-surface` 设置弹窗和表格表面；保留原控件的 `type`、disabled、loading 和表单校验状态。选择器、日期控件和弹出菜单也使用同一表面色。

- [ ] **Step 3: 更新侧栏品牌与导航状态**

  在 `aside.vue` 与 `logo/index.vue` 将导航背景设为 `--agri-wood`，Logo 使用“禾序”及“农业智能体”字样；在 `vertical.vue` 设置木棕上的高对比文字、陶土选中标识和清楚的分组间距；在 `navBars/index.vue` 保留面包屑并整理顶部场景上下文。保留 `aside.vue` 已有的小视口抽屉/遮罩行为。

- [ ] **Step 4: 浏览框架状态**

  在浏览器查看 1440×900 与 1024×768 下的侧栏、折叠态、header、菜单 hover、弹窗和表格。确认文字不依赖仅颜色表达的状态且桌面主内容没有横向溢出。

### Task 2：调整导航并移除数据大屏

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/router/workspaceMenu.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/router/route.ts`
- Delete: `YOLO_AI_CropDisease_Detection_Vue/src/views/dataView/index.vue`
- Delete after reference audit: `YOLO_AI_CropDisease_Detection_Vue/public/data/` data-screen-only files.

- [ ] **Step 1: 让工作区菜单遵循业务流程**

  保持 `buildWorkspaceMenu(routes)` 从现有路由表取页面记录，并按以下分组顺序组织：工作台；病害识别（图片/视频/摄像及三类记录）；决策与规划（决策助手、农事规划推演）；温室推演（指挥中心、数字孪生、环境详情、温室档案）；知识与数据（病害库、视觉—知识覆盖）；系统管理（采购、库存、用户、个人）。不复制或重命名业务路由。

- [ ] **Step 2: 移除大屏路由及视图**

  删除 `route.ts` 中 `name: 'dataView'` 的路由记录，从工作区分组移除 `'/dataView'`，删除 `views/dataView/index.vue`。保持所有其他 `path`、`name`、`component` 与 `roles` 不变。

- [ ] **Step 3: 依据引用扫描删除专属资源**

  在 Vue 子项目运行 `rg -n 'dataView|数据大屏|/data/index.html' src --glob '!**/*.map'`。枚举 `public/data/` 中的资源，并在 `src/` 及 `public/data/` 以外的 `public/` 路径搜索资源名；只删除找不到其他引用的专属资源。

- [ ] **Step 4: 手动核对导航与旧地址**

  登录并按新菜单打开六组入口；确认各页面能加载、记录页仍从识别入口到达、两种 AI 通道独立显示。直接访问 `/dataView` 不再显示旧大屏；其他原有业务 URL 继续可用。

### Task 3：重排农业决策工作台

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/homePage/index.vue`

- [ ] **Step 1: 保留数据读取，调整主内容顺序**

  保留 `useAgentRunStore()`、`getAgentVisionEvents()`、`refresh()` 和现有刷新异常处理。页面先呈现“8 号温室 · 番茄”场景与运行/来源状态，再呈现观察→研判→方案→复盘的横向步骤，最后排温室指标、待关注事项和最近识别信号。

- [ ] **Step 2: 保持首页没有演示型 CTA**

  不新增“查看答辩路线”或“开始演示”按钮；保留当前有业务含义的“创建推演/进入推演”“查看三维场景”“查看识别记录”等入口及其路由。页面入口只导航，不直接执行仿真控制。

- [ ] **Step 3: 重做工作台样式和状态文案**

  在本页 `<style scoped>` 中将 `.workbench`, `.run-band`, `.workflow`, `.data-section`, `.metric-grid`, `.alert-list`, `.latest-section` 替换为主题变量、细边框、稳定桌面网格和宽裕标题层级。无活动运行时保留空状态且不渲染模拟指标；服务不可用、加载中、无告警和无识别信号仍分别表达。

- [ ] **Step 4: 手动走查工作台主链路**

  浏览服务可用但无运行、活动运行、服务不可用三种已有状态；点击每个流程步骤核对跳转；检查识别信号进入决策助手的记录链接仍保留，来源标记可读，页面顶部没有两个演示型 CTA。
