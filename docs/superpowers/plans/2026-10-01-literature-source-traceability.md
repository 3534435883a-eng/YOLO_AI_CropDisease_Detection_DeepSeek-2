# 论文来源追溯 Implementation Plan

**Goal:** 完成已确认文献库设计中的原文来源入口，让六篇已核对论文的人工摘要与本地 PDF 可以直接核对。

**Architecture:** 复用 CitedKnowledgeReader 的精确资源清单读取 localDocument 和人工摘要，向现有来源目录补充字段。PDF 打开仍通过有鉴权的后端，使用已登记的 SHA-256 和字节大小验证原件。无需数据库迁移或重建知识索引。

**Tech Stack:** Java 8 / Spring Boot / Vue 3 / Element Plus。

**Spec:** docs/superpowers/specs/2026-09-29-agri-literature-library-design.md 和 docs/superpowers/specs/2026-09-29-tomato-literature-evidence-design.md。

## Global Constraints

- 用户已授权在当前会话继续实施，沿用现有双标签页面与视觉设计。
- 保留已有未提交修改；不修改默认模拟参数，不猜 M3 实际字段。
- 沿用此前“不增加或运行实现测试”的约束；验证采用生产代码编译、前端构建与静态检查。
- 不声称来源目录是某轮回答的实际引用，不声称人工摘要是全文索引。
- 原文缺失、大小变化或指纹不一致时拒绝通过摘要入口打开，保留文件。

## Task 1: 后端来源与原文关联

Files: agent/rag/CitedKnowledgeReader.java、agent/library/LocalKnowledgeLibraryService.java、agent/controller/KnowledgeLibraryController.java。

- [x] 增加内部接口 `Map<String, Map<String, Object>> readLocalEvidence()`，按清单 sourceCode 返回白名单 localDocument 和 reviewedSummaries。
- [x] 来源 API 增加 localDocument（id、fileName、sizeBytes、sha256、reviewedAt、available），reviewedSummaries（id、topic、fieldType、text）和 summaryCount。available 仅表示当前目录中匹配大小的原件存在，打开时另行校验内容指纹。
- [x] 增加 `resolveVerifiedPdf(String id, String sha256, long sizeBytes)`，在原有目录边界验证后，以流式 SHA-256 校验文件。
- [x] 增加 `GET /ai/knowledge/sources/{sourceCode}/content`，只解析既有清单中的 ID；原件缺失返回 404，指纹或大小不符返回 409，正确原件以 PDF inline 返回。

```java
Optional<Path> pdf = libraryService.resolveVerifiedPdf(documentId, sha256, sizeBytes);
if (!pdf.isPresent()) return ResponseEntity.status(HttpStatus.CONFLICT).build();
```

## Task 2: 双标签内核对摘要与原件

Files: Vue/src/api/knowledge/index.ts、Vue/src/views/referenceLibrary/index.vue。

- [x] 增加带现有鉴权头的 getSourcePdfBlob(sourceCode)。类型增加 localDocument、reviewedSummaries 与 summaryCount。
- [x] 来源表格增加摘要展开区，保留页码、实验条件、适用边界，明确人工摘要不代表本轮实际引用。
- [x] 原文列同时提供已有外部来源和经过指纹核验的本地 PDF 按钮；原件不可用时显示明确状态。
- [x] 两种 PDF 入口共用 blob 查看与释放逻辑，读取失败时关闭空白页并提示核对文件。

```ts
return request.get(`/api/ai/knowledge/sources/${encodeURIComponent(sourceCode)}/content`, {
  responseType: 'blob', headers: { Accept: 'application/pdf' },
});
```

## Task 3: 构建与记录

- [x] 用本地 Maven 离线 compile（maven.test.skip=true）验证生产代码；前端输出至独立目录，保留旧产物。
- [x] git diff --check 检查本轮文件，记录构建结果与运行状态；M3 下载继续独立运行。
- [x] 更新论文实施记录和后续待办，不将未完成原始观测导入、误差计算或校准标为完成。

