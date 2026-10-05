# 文献与来源资料库实施计划

> **For agentic workers:** Implement this plan task-by-task in the current session. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在“知识与数据”下新增“文献与来源”页面，分类查找并打开仓库本地 PDF，同时浏览知识库已登记来源。

**Architecture:** Spring Boot 提供只读本地 PDF 目录/内容接口和知识来源目录接口。Vue 新页面通过现有带令牌的 Axios 客户端加载两类数据，并通过 Blob URL 在新标签页显示用户选择的 PDF；现有决策助手逐轮引用保持原样。

**Tech Stack:** Java Spring Boot、Java NIO 文件 API、Vue 3、TypeScript、Element Plus、现有 Axios 请求封装、Vue Router。

**Spec:** `docs/superpowers/specs/2026-09-29-agri-literature-library-design.md`

## Global Constraints

- PDF 不复制到前端构建包，不上传文件，也不自动将全文导入 RAG。
- 页面需说明：来源已登记不代表某次回答实际引用了它；某篇本地 PDF 也不因此自动成为可检索知识。
- 决策助手每轮实际采用的证据继续以该轮对话中的引用为准。
- 接口不向前端暴露操作系统绝对路径。
- 文档 ID 必须映射到目录内的已发现文件；解析后的真实路径必须仍处在配置根目录中；只允许 PDF 扩展名，拒绝目录穿越和任意文件读取。
- 本地文档根目录由 Spring Boot 配置项指定；本地默认配置指向仓库内的 `农业论文` 目录。
- 不新增数据库表，不增加引用历史写入。
- 本地文献接口和知识来源接口分别加载，某一类服务不可用时另一标签仍可使用。
- 本请求未要求增加或运行测试；实施过程中不新增或运行测试。

---

## File map

- `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/library/LocalKnowledgeLibraryService.java` — 扫描配置根目录、生成稳定文档 ID、分类 PDF、解析 ID 并校验真实路径。
- `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/controller/KnowledgeLibraryController.java` — 暴露只读目录、PDF 内容、知识来源三个接口。
- `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/resources/application.properties` — 配置本地文献根目录与环境变量覆盖。
- `YOLO_AI_CropDisease_Detection_Vue/src/api/knowledge/index.ts` — 增加文献目录、来源类型与 PDF Blob API。
- `YOLO_AI_CropDisease_Detection_Vue/src/router/route.ts` — 注册 `/referenceLibrary` 页面路由。
- `YOLO_AI_CropDisease_Detection_Vue/src/router/workspaceMenu.ts` — 将入口加到“知识与数据”分组。
- `YOLO_AI_CropDisease_Detection_Vue/src/views/referenceLibrary/index.vue` — 双标签文献与来源页面、筛选、搜索、加载状态和 PDF 打开动作。

## Task 1: 提供只读知识资料接口

**Files:**

- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/library/LocalKnowledgeLibraryService.java`
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/controller/KnowledgeLibraryController.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/resources/application.properties`

**Interfaces:**

- `GET /api/ai/knowledge/library` 返回 `{ available, count, documents }`。每个 document 有 `id`, `fileName`, `category`, `folder`, `sizeBytes`。
- `GET /api/ai/knowledge/library/{id}/content` 返回单份 PDF 的 inline `application/pdf` 流。
- `GET /api/ai/knowledge/sources` 返回现有来源仓库中的 `sourceCode`, `sourceName`, `sourceType`, `authorityLevel`, `version`, `url`, `licenseNote`。
- 文档类别固定为 `研究论文`、`设施番茄标准`、`其他作物标准`，依据现有目录和已知文件名分类；不能猜测不存在的书目字段。

- [x] 在 `application.properties` 添加 `agent.knowledge.library-root=${AGRI_KNOWLEDGE_LIBRARY_ROOT:../\u519C\u4E1A\u8BBA\u6587}`，用 Unicode 转义避免 Spring `.properties` 中的中文乱码，并允许本地运行者通过环境变量改目录。
- [x] 新建 `LocalKnowledgeLibraryService`，构造器接收根目录配置；使用 `Files.walk` 找到扩展名不区分大小写的 `.pdf`，排序后输出元数据。
- [x] 对每份文件的根目录相对路径计算 SHA-256 文档 ID；不在 DTO 或响应中输出绝对路径。
- [x] 按路径标签分类：`标准_其他作物` 为“其他作物标准”、`标准` 为“设施番茄标准”；仓库根目录中的 DB/NY/T 标准文件归为“设施番茄标准”，其余根目录 PDF 归为“研究论文”。
- [x] 为 PDF 打开动作实现 `resolvePdf(id)`：重新查目录生成当前允许的 ID 集合，拒绝未知 ID；`toRealPath()` 后确认目标仍位于根目录下且为普通 PDF 文件。
- [x] 若配置根目录不存在或不可读，目录接口返回 `available=false`, `count=0`, `documents=[]`；不可通过请求参数指定任意目录或文件。
- [x] 新建 `KnowledgeLibraryController`，在 `GET /ai/knowledge/library` 返回目录 DTO，在 `GET /ai/knowledge/library/{id}/content` 用 `ResponseEntity<Resource>` 设置 `Content-Type: application/pdf` 和 inline 文件名，在 `GET /ai/knowledge/sources` 仅读取 `KnowledgeSourceRepository.findAll()` 并返回必要来源字段。
- [x] 对不存在或非法的文档 ID 返回 404；读取失败时返回通用错误消息，不把实际服务器路径写入响应。

**Deliverable review:** 逐段阅读 Controller 与 Service，确认所有可访问文件都来自配置根目录内目录扫描出的 `.pdf`；确认列表响应没有绝对路径，也没有任何写入/索引操作。

## Task 2: 接入前端 API 与导航

**Files:**

- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/api/knowledge/index.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/router/route.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/router/workspaceMenu.ts`

**Interfaces:**

- `LocalKnowledgeDocument`: `id: string`, `fileName: string`, `category: string`, `folder: string`, `sizeBytes: number`。
- `LocalKnowledgeLibrary`: `available: boolean`, `count: number`, `documents: LocalKnowledgeDocument[]`。
- `KnowledgeSourceItem`: `sourceCode`, `sourceName`, `sourceType`, `authorityLevel`, `version`, `url`, `licenseNote` 为可选字段。
- `getLocalKnowledgeLibrary(): Promise<LocalKnowledgeLibrary>`。
- `getKnowledgeSources(): Promise<KnowledgeSourceItem[]>`。
- `getLocalPdfBlob(id: string): Promise<Blob>` 通过现有 Axios request 拦截器传递登录令牌。

- [x] 在现有 knowledge API 文件里定义三个类型和三个函数；PDF 请求设置 `responseType: 'blob'`，使用 `/api/ai/knowledge/library/{encodeURIComponent(id)}/content`。
- [x] 用现有 API 信封 `unwrap<T>` 解包两个 JSON 接口的 `data` 字段；Blob 不走 JSON 信封解包。
- [x] 在 `route.ts` 注册 `/referenceLibrary`，页面组件动态导入 `/@/views/referenceLibrary/index.vue`，路由标题为“文献与来源”。
- [x] 在 `workspaceMenu.ts` 的 `/knowledgeWorkspace` 路由组末尾加入 `/referenceLibrary`，不更换既有菜单顺序或路径。

**Deliverable review:** 阅读新增 API 类型与路由表，确认 PDF 经 Axios 请求而非浏览器裸 URL 加载，以保留现有 Authorization 请求头。

## Task 3: 实现双标签资料页面

**Files:**

- Create: `YOLO_AI_CropDisease_Detection_Vue/src/views/referenceLibrary/index.vue`
- Consumes: Task 2 的三个 API 方法与数据类型。

- [x] 创建页面标题区“文献与来源”，副标题清楚说明“本地文献”表示仓库存档，“知识库来源”表示已登记来源，不等于逐轮引用记录。
- [x] 用两个 `el-tab-pane` 分别呈现“本地文献”和“知识库来源”；每个标签有自己的 loading、error、empty 状态，独立调用与重试。
- [x] 本地文献标签展示文档总数、类别筛选和标题/文件名搜索；分类值严格采用 API 返回的三个固定类别；用 `el-table` 展示文件名、分类、目录标签、文件大小。
- [x] “打开 PDF”点击处理器必须先在同步用户手势中打开空白标签页，再调用 `getLocalPdfBlob(id)`；成功后将 Blob URL 指派到标签页，读取失败则关闭空标签并提示；为每个查看标签安装自清理轮询，在标签关闭时清除轮询并回收 Blob URL，不能因 Vue 组件销毁而过早撤销仍在使用的 PDF URL。
- [x] 来源标签调用 `getKnowledgeSources()`，按名称、类型、版本搜索；用易读文本和标签显示权威级别，字段缺失时显示短横线，不臆造 URL、版本或许可说明。
- [x] 仅当来源 `url` 是 `http:` 或 `https:` 时显示“原文”链接，使用 `target="_blank"` 和 `rel="noopener noreferrer"`。
- [x] 来源表格上方固定显示：“这是知识库登记来源目录，不代表它已被某次回答引用。决策助手每轮引用见对话中的本轮证据。”
- [x] 沿用 `infoDisease`、`visionCoverage` 的暖纸白、深木棕、低饱和绿色与细分割线；桌面宽度保持表格可读，中窄窗口让筛选栏换行并允许表格内部滚动。

**Deliverable review:** 对照设计说明逐项阅读两个标签的数据来源和提示文案，确认本地文件不被写成已入 RAG，登记来源不被写成历史引用。

## Task 4: 汇总变更与交付

**Files:** Task 1–3 文件。

- [x] 查看工作区差异，确认本功能只新增文献库代码、路由入口、文献 API 和配置，没有改动助手引用逻辑、知识索引或数据库。
- [x] 按本请求的验证范围约束，不新增或运行自动化测试/构建；最终明确向用户报告未运行的验证项。
- [x] 汇报页面位置、目录和来源数据边界、PDF 打开方式、实现文件与未验证项。
