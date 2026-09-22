# 农业专属智能体（RAG + 知识图谱 + LLM 编排）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有单轮 LLM 问答升级为面向农业场景的专属智能体：检索增强 + 知识图谱 + LLM 编排 + 安全守门，输出带出处、可执行、可审计的农事方案与处方。

**Architecture:** 三阶段递进。阶段 A 建"知识层"（切块 → BM25 → 向量 → RRF 融合，带降级），单独交付即可支持"有出处的检索问答"；阶段 B 加"编排层"（LLM 选工具、多步循环、SSE 可观测）与"守门层"；阶段 C 补评测取证、报告导出、知识图谱与前端面板。所有新增数据落 `agent_` 前缀的 create-only 表，不改动遗留表与遗留接口。

**Tech Stack:** Java 8、Spring Boot 2.3.7、MyBatis-Plus、JdbcTemplate、fastjson 1.2.75、JUnit 5（Jupiter）、MySQL 8.4、Flask + sentence-transformers（`bge-small-zh-v1.5`）、Vue 3 + Element Plus（SSE）。

**Spec:** `docs/superpowers/specs/2026-09-22-agri-rag-agent-design.md`

## Global Constraints

- 语言与版本：Java **1.8**（不得使用 `var`、`List.of`、`Map.of`、文本块）；Spring Boot **2.3.7.RELEASE**；测试用 **JUnit 5**（`org.junit.jupiter.api.Test`，`junit-vintage-engine` 已排除，**不要写 JUnit 4 测试**）。
- 迁移纪律：新表一律 `CREATE TABLE IF NOT EXISTS`、`agent_` 前缀、InnoDB、utf8mb4；**禁止** `DROP TABLE`、**禁止** `ALTER` 遗留表（`disease`/`greenhouse`/`storage`/`purchase`/`user`/`imgrecords`/`videorecords`/`camerarecords`）、**禁止**重新导入 `cropdisease.sql`。
- 遗留接口不变：`/ai/chat`、`/agent/**`、`/files/**`、`/imgRecords` 等既有契约与既有 18 项测试必须保持通过。
- 数据来源标记必须贯穿输出：`REAL` / `LEGACY_HISTORY` / `SIMULATED` / `VISION_SIGNAL` / `MANUAL`；`SIMULATED` 与 `VISION_SIGNAL` 不得表述为实测。
- 安全边界：不自动施药、不自动改变设备状态；涉药输出必须带引用并标注"需人工确认"；无引用支撑的专业结论改为拒答。
- 提交纪律：每个 Task 末尾提交一次，提交信息用 `type: 描述`（`feat`/`test`/`docs`/`fix`/`chore`）。

### 固定命令（每个 Task 的验证步骤都用这套）

```powershell
$env:JAVA_HOME = 'C:\Users\nom\AppData\Local\TomatoGreenhouseRuntime\java\temurin8'
$MVN  = 'C:\Users\nom\AppData\Local\TomatoGreenhouseRuntime\maven\apache-maven-3.9.16\bin\mvn.cmd'
$REPO = 'C:\Users\nom\AppData\Local\TomatoGreenhouseRuntime\maven\repository'
$SB   = 'E:\agent 农业\YOLO_AI_CropDisease_Detection_DeepSeek-2\YOLO_AI_CropDisease_Detection_DeepSeek-2\YOLO_AI_CropDisease_Detection_SpringBoot'
$ROOT = 'E:\agent 农业\YOLO_AI_CropDisease_Detection_DeepSeek-2\YOLO_AI_CropDisease_Detection_DeepSeek-2'
```

- 单测：`& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=<类名> test`
- 全测：`& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" test`
- Java 包根：`$SB\src\main\java\com\example\Ece\`；测试根：`$SB\src\test\java\com\example\Ece\`
- 首次构建会下载依赖（Maven Central 已验证可达，约 194 个 jar 已在本地仓库）

---

## 阶段 A：知识层（交付即可支持"有出处的检索问答"）

### Task 1: 知识块切分与知识表迁移

**Files:**
- Create: `database/migrations/V20260922_01__agent_knowledge_rag.sql`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\KnowledgeChunk.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\KnowledgeChunker.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\rag\KnowledgeChunkerTest.java`

**Interfaces:**
- Produces:
  - `enum KnowledgeChunk.FieldType { SYMPTOM, CAUSE, CONTROL, OTHER }`
  - `class KnowledgeChunk`：字段 `String sourceTable; long sourceId; String cropType; String diseaseName; FieldType fieldType; int chunkNo; String content; String contentHash;`，含全参构造与 getter
  - `class KnowledgeChunker`：常量 `CHUNK_SIZE = 500`、`OVERLAP = 80`、`STEP = CHUNK_SIZE - OVERLAP`；方法 `List<KnowledgeChunk> chunk(long sourceId, String cropType, String diseaseName, Map<FieldType, String> fields)`、`static String sha256(String value)`

- [ ] **Step 1: 写失败测试**

```java
package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeChunkerTest {
    private final KnowledgeChunker chunker = new KnowledgeChunker();

    private Map<KnowledgeChunk.FieldType, String> fields(String symptom, String control) {
        Map<KnowledgeChunk.FieldType, String> map = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
        if (symptom != null) map.put(KnowledgeChunk.FieldType.SYMPTOM, symptom);
        if (control != null) map.put(KnowledgeChunk.FieldType.CONTROL, control);
        return map;
    }

    @Test
    void shortTextBecomesSingleChunk() {
        List<KnowledgeChunk> chunks = chunker.chunk(1L, "番茄", "早疫病", fields("叶片出现褐色轮纹斑。", null));
        assertEquals(1, chunks.size());
        assertEquals("叶片出现褐色轮纹斑。", chunks.get(0).getContent());
        assertEquals(0, chunks.get(0).getChunkNo());
        assertEquals(KnowledgeChunk.FieldType.SYMPTOM, chunks.get(0).getFieldType());
    }

    @Test
    void longTextSplitsWithOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1200; i++) sb.append('病');
        List<KnowledgeChunk> chunks = chunker.chunk(2L, "番茄", "晚疫病", fields(sb.toString(), null));
        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).getContent().length());
        assertEquals(500, chunks.get(1).getContent().length());
        assertEquals(360, chunks.get(2).getContent().length());
        assertEquals(420, chunks.get(1).getStartOffset());
        assertEquals(840, chunks.get(2).getStartOffset());
    }

    @Test
    void blankFieldsAreSkipped() {
        List<KnowledgeChunk> chunks = chunker.chunk(3L, "番茄", "灰霉病", fields("   ", ""));
        assertTrue(chunks.isEmpty());
    }

    @Test
    void hashIsStableAndDistinctPerChunk() {
        String text = "同一段文本";
        List<KnowledgeChunk> a = chunker.chunk(4L, "番茄", "叶霉病", fields(text, null));
        List<KnowledgeChunk> b = chunker.chunk(4L, "番茄", "叶霉病", fields(text, null));
        assertEquals(a.get(0).getContentHash(), b.get(0).getContentHash());
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 600; i++) longText.append('x');
        List<KnowledgeChunk> c = chunker.chunk(4L, "番茄", "叶霉病", fields(longText.toString(), null));
        assertNotEquals(c.get(0).getContentHash(), c.get(1).getContentHash());
    }

    @Test
    void metadataIsCarriedThrough() {
        List<KnowledgeChunk> chunks = chunker.chunk(77L, "番茄", "早疫病", fields("症状文本", "防治文本"));
        assertEquals(2, chunks.size());
        assertEquals(77L, chunks.get(0).getSourceId());
        assertEquals("番茄", chunks.get(0).getCropType());
        assertEquals("早疫病", chunks.get(0).getDiseaseName());
        assertEquals("disease", chunks.get(0).getSourceTable());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=KnowledgeChunkerTest test
```
Expected: 编译失败 `cannot find symbol: class KnowledgeChunker`

- [ ] **Step 3: 实现模型与切块器**

`KnowledgeChunk.java`：

```java
package com.example.Ece.agent.rag;

public class KnowledgeChunk {
    public enum FieldType { SYMPTOM, CAUSE, CONTROL, OTHER }

    private final String sourceTable;
    private final long sourceId;
    private final String cropType;
    private final String diseaseName;
    private final FieldType fieldType;
    private final int chunkNo;
    private final int startOffset;
    private final String content;
    private final String contentHash;

    public KnowledgeChunk(String sourceTable, long sourceId, String cropType, String diseaseName,
                          FieldType fieldType, int chunkNo, int startOffset, String content, String contentHash) {
        this.sourceTable = sourceTable; this.sourceId = sourceId; this.cropType = cropType;
        this.diseaseName = diseaseName; this.fieldType = fieldType; this.chunkNo = chunkNo;
        this.startOffset = startOffset; this.content = content; this.contentHash = contentHash;
    }

    public String getSourceTable() { return sourceTable; }
    public long getSourceId() { return sourceId; }
    public String getCropType() { return cropType; }
    public String getDiseaseName() { return diseaseName; }
    public FieldType getFieldType() { return fieldType; }
    public int getChunkNo() { return chunkNo; }
    public int getStartOffset() { return startOffset; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
}
```

`KnowledgeChunker.java`：

```java
package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeChunker {
    public static final int CHUNK_SIZE = 500;
    public static final int OVERLAP = 80;
    public static final int STEP = CHUNK_SIZE - OVERLAP;
    private static final String SOURCE_TABLE = "disease";

    public List<KnowledgeChunk> chunk(long sourceId, String cropType, String diseaseName,
                                      Map<KnowledgeChunk.FieldType, String> fields) {
        List<KnowledgeChunk> result = new ArrayList<KnowledgeChunk>();
        if (fields == null) return result;
        for (Map.Entry<KnowledgeChunk.FieldType, String> entry : fields.entrySet()) {
            String text = entry.getValue() == null ? "" : entry.getValue().trim();
            if (text.isEmpty()) continue;
            int chunkNo = 0;
            for (int start = 0; start < text.length(); start += STEP) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                String content = text.substring(start, end);
                String hash = sha256(SOURCE_TABLE + "|" + sourceId + "|" + entry.getKey() + "|" + chunkNo + "|" + content);
                result.add(new KnowledgeChunk(SOURCE_TABLE, sourceId, cropType, diseaseName,
                        entry.getKey(), chunkNo, start, content, hash));
                chunkNo++;
                if (end == text.length()) break;
            }
        }
        return result;
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=KnowledgeChunkerTest test
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 写迁移脚本**

`database/migrations/V20260922_01__agent_knowledge_rag.sql`（严格按 spec §6 四张表；`agent_knowledge_chunk.embedding` 用 `VARBINARY(4096)`；唯一键与索引照抄 spec）。

- [ ] **Step 6: 静态检查迁移脚本**

```powershell
Select-String -Path "$ROOT\database\migrations\V20260922_01__agent_knowledge_rag.sql" -Pattern 'DROP TABLE|ALTER TABLE|`disease`|`greenhouse`' | ForEach-Object { $_.Line }
```
Expected: 无输出（不得出现任何遗留表名与非 create 语句）

- [ ] **Step 7: 提交**

```powershell
git -C $ROOT add database/migrations/V20260922_01__agent_knowledge_rag.sql "$SB\src\main\java\com\example\Ece\agent\rag" "$SB\src\test\java\com\example\Ece\agent\rag"
git -C $ROOT commit -m "feat: add knowledge chunking and agent knowledge tables"
```

---

### Task 2: 中文 bigram 分词与 BM25 索引

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\ChineseBigramTokenizer.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\Bm25Index.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\ScoredChunk.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\rag\ChineseBigramTokenizerTest.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\rag\Bm25IndexTest.java`

**Interfaces:**
- Consumes: `KnowledgeChunk`（Task 1）
- Produces:
  - `class ScoredChunk`：`KnowledgeChunk chunk; double score; int rank;`（rank 从 1 起）+ getter
  - `class ChineseBigramTokenizer`：`List<String> tokenize(String text)`
  - `class Bm25Index`：`void rebuild(List<KnowledgeChunk> chunks)`、`List<ScoredChunk> search(String query, int topK)`；常量 `K1 = 1.2`、`B = 0.75`

- [ ] **Step 1: 写失败测试**

```java
package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChineseBigramTokenizerTest {
    private final ChineseBigramTokenizer tokenizer = new ChineseBigramTokenizer();

    @Test
    void splitsChineseIntoBigrams() {
        assertEquals(Arrays.asList("番茄", "茄早", "早疫", "疫病"), tokenizer.tokenize("番茄早疫病"));
    }

    @Test
    void keepsAsciiWordsLowercasedAndDigits() {
        List<String> tokens = tokenizer.tokenize("Tomato 早疫病 2025");
        assertTrue(tokens.contains("tomato"));
        assertTrue(tokens.contains("2025"));
        assertTrue(tokens.contains("早疫"));
        assertFalse(tokens.contains("Tomato"));
    }

    @Test
    void blankInputYieldsNothing() {
        assertTrue(tokenizer.tokenize("   ").isEmpty());
        assertTrue(tokenizer.tokenize(null).isEmpty());
    }
}
```

```java
package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Bm25IndexTest {
    private KnowledgeChunk chunk(long id, String content) {
        return new KnowledgeChunk("disease", id, "番茄", "病" + id,
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, content, "h" + id);
    }

    private Bm25Index indexOf(KnowledgeChunk... chunks) {
        Bm25Index index = new Bm25Index();
        index.rebuild(Arrays.asList(chunks));
        return index;
    }

    @Test
    void ranksChunkContainingRareTermFirst() {
        Bm25Index index = indexOf(
                chunk(1L, "番茄叶片出现褐色轮纹斑，湿度大时病斑扩展迅速"),
                chunk(2L, "番茄果实表面出现白色霉层"),
                chunk(3L, "番茄茎部腐烂并伴有异味"));
        List<ScoredChunk> hits = index.search("褐色轮纹斑", 3);
        assertFalse(hits.isEmpty());
        assertEquals(1L, hits.get(0).getChunk().getSourceId());
        assertEquals(1, hits.get(0).getRank());
        assertTrue(hits.get(0).getScore() > 0.0);
    }

    @Test
    void rareTermOutweighsCommonTerm() {
        Bm25Index index = indexOf(
                chunk(1L, "番茄 番茄 番茄 番茄"),
                chunk(2L, "番茄 晚疫病"));
        List<ScoredChunk> hits = index.search("番茄 晚疫病", 2);
        assertEquals(2L, hits.get(0).getChunk().getSourceId());
    }

    @Test
    void respectsTopKAndEmptyQuery() {
        Bm25Index index = indexOf(chunk(1L, "番茄早疫病"), chunk(2L, "番茄晚疫病"));
        assertEquals(1, index.search("番茄", 1).size());
        assertTrue(index.search("", 5).isEmpty());
        assertTrue(index.search(null, 5).isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest='ChineseBigramTokenizerTest,Bm25IndexTest' test
```
Expected: 编译失败 `cannot find symbol: class ChineseBigramTokenizer`

- [ ] **Step 3: 实现分词器、倒排索引与打分**

`ChineseBigramTokenizer`：遍历字符，`Character.isLetterOrDigit` 为词内字符，否则作分隔；对连续中文串（`isIdeographic`）做 2-gram（长度 1 时输出该字）；ASCII 串整体小写入词。

`ScoredChunk`：三字段 + getter + `setRank(int)`（BM25 与向量检索各自填 rank）。

`Bm25Index`：

```java
public void rebuild(List<KnowledgeChunk> chunks) {
    documents.clear(); inverted.clear(); docLengths.clear(); totalLength = 0;
    if (chunks == null || chunks.isEmpty()) { averageLength = 0.0; return; }
    for (KnowledgeChunk chunk : chunks) {
        List<String> tokens = tokenizer.tokenize(chunk.getContent());
        documents.add(chunk);
        docLengths.put(chunk.getContentHash(), tokens.size());
        totalLength += tokens.size();
        for (String token : tokens) {
            inverted.computeIfAbsent(token, key -> new ArrayList<Integer>()).add(documents.size() - 1);
        }
    }
    averageLength = (double) totalLength / documents.size();
}

public List<ScoredChunk> search(String query, int topK) {
    List<ScoredChunk> results = new ArrayList<ScoredChunk>();
    List<String> queryTokens = tokenizer.tokenize(query);
    if (queryTokens.isEmpty() || documents.isEmpty() || topK <= 0) return results;
    Map<Integer, Double> scores = new HashMap<Integer, Double>();
    for (String token : queryTokens) {
        List<Integer> postings = inverted.get(token);
        if (postings == null || postings.isEmpty()) continue;
        double idf = Math.log(1.0 + (documents.size() - postings.size() + 0.5) / (postings.size() + 0.5));
        for (Integer docIndex : postings) {
            int length = docLengths.get(documents.get(docIndex).getContentHash());
            double tf = 0.0;
            for (String t : tokenizer.tokenize(documents.get(docIndex).getContent())) if (t.equals(token)) tf += 1.0;
            double denominator = tf + K1 * (1.0 - B + B * length / Math.max(averageLength, 1.0));
            double contribution = denominator == 0.0 ? 0.0 : idf * (tf * (K1 + 1.0)) / denominator;
            Double current = scores.get(docIndex);
            scores.put(docIndex, (current == null ? 0.0 : current) + contribution);
        }
    }
    for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
        results.add(new ScoredChunk(documents.get(entry.getKey()), entry.getValue(), 0));
    }
    Collections.sort(results, new Comparator<ScoredChunk>() {
        public int compare(ScoredChunk left, ScoredChunk right) {
            int byScore = Double.compare(right.getScore(), left.getScore());
            return byScore != 0 ? byScore : left.getChunk().getContentHash().compareTo(right.getChunk().getContentHash());
        }
    });
    if (results.size() > topK) results = new ArrayList<ScoredChunk>(results.subList(0, topK));
    for (int i = 0; i < results.size(); i++) results.get(i).setRank(i + 1);
    return results;
}
```

> 注：`docLengths` 用 `contentHash` 作键以避免重复 chunk 冲突；实现时可改为在 `rebuild` 时缓存每篇的 `Map<String,Integer> termFrequency` 以省去重复分词（同一行为不变，测试仍须通过）。

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest='ChineseBigramTokenizerTest,Bm25IndexTest' test
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```powershell
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent\rag" "$SB\src\test\java\com\example\Ece\agent\rag"
git -C $ROOT commit -m "feat: add chinese bigram tokenizer and in-memory bm25 index"
```

---

### Task 3: 向量检索、RRF 融合与降级

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\EmbeddingClient.java`（接口）
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\HttpEmbeddingClient.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\EmbeddingUnavailableException.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\VectorIndex.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\RrfFusion.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\KnowledgeRetriever.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\RetrievalResult.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\rag\RrfFusionTest.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\rag\KnowledgeRetrieverTest.java`

**Interfaces:**
- Consumes: `KnowledgeChunk`、`ScoredChunk`、`Bm25Index`（Task 1–2）
- Produces:
  - `interface EmbeddingClient { double[] embed(String text) throws EmbeddingUnavailableException; }`
  - `class RrfFusion`：`List<ScoredChunk> fuse(List<List<ScoredChunk>> rankedLists, int k, int topN)`
  - `class RetrievalResult`：`List<ScoredChunk> getItems(); boolean isDegraded(); String getDegradedReason(); double getTopScore();`
  - `class KnowledgeRetriever`：`RetrievalResult retrieve(String query, String cropType, int topN)`、`void rebuild(List<KnowledgeChunk> chunks)`；常量 `TOP_K_EACH = 20`、`RRF_K = 60`、`MIN_SCORE = 0.016`

- [ ] **Step 1: 写失败测试**

```java
package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RrfFusionTest {
    private final RrfFusion fusion = new RrfFusion();

    private ScoredChunk hit(long id, double score, int rank) {
        ScoredChunk sc = new ScoredChunk(new KnowledgeChunk("disease", id, "番茄", "d" + id,
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "c" + id, "h" + id), score, rank);
        return sc;
    }

    @Test
    void fusesTwoRankedListsByReciprocalRank() {
        List<ScoredChunk> bm25 = Arrays.asList(hit(1L, 9.0, 1), hit(2L, 8.0, 2), hit(3L, 7.0, 3));
        List<ScoredChunk> vector = Arrays.asList(hit(3L, 0.9, 1), hit(1L, 0.8, 2), hit(4L, 0.7, 3));
        List<ScoredChunk> fused = fusion.fuse(Arrays.asList(bm25, vector), 60, 4);
        assertEquals(4, fused.size());
        assertEquals(1L, fused.get(0).getChunk().getSourceId());
        assertEquals(3L, fused.get(1).getChunk().getSourceId());
        assertEquals(2L, fused.get(2).getChunk().getSourceId());
        assertEquals(4L, fused.get(3).getChunk().getSourceId());
        assertEquals(1, fused.get(0).getRank());
    }

    @Test
    void truncatesToTopNAndSurvivesEmptyList() {
        List<ScoredChunk> only = Arrays.asList(hit(1L, 1.0, 1), hit(2L, 0.5, 2));
        assertEquals(1, fusion.fuse(Arrays.asList(only, new ArrayList<ScoredChunk>()), 60, 1).size());
        assertTrue(fusion.fuse(new ArrayList<List<ScoredChunk>>(), 60, 5).isEmpty());
    }
}
```

```java
package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeRetrieverTest {
    private KnowledgeChunk chunk(long id, String content) {
        return new KnowledgeChunk("disease", id, "番茄", "病" + id,
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, content, "h" + id);
    }

    private List<KnowledgeChunk> corpus() {
        return Arrays.asList(
                chunk(1L, "番茄叶片出现褐色轮纹斑，湿度大时扩展迅速，属早疫病典型症状"),
                chunk(2L, "番茄果实表面出现白色霉层，属灰霉病典型症状"),
                chunk(3L, "番茄茎部腐烂有异味，属细菌性软腐"));
    }

    @Test
    void degradesToBm25WhenEmbeddingUnavailable() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) throws EmbeddingUnavailableException {
                throw new EmbeddingUnavailableException("service down");
            }
        });
        retriever.rebuild(corpus());
        RetrievalResult result = retriever.retrieve("褐色轮纹斑", "番茄", 3);
        assertTrue(result.isDegraded());
        assertEquals("EMBEDDING_UNAVAILABLE", result.getDegradedReason());
        assertFalse(result.getItems().isEmpty());
        assertEquals(1L, result.getItems().get(0).getChunk().getSourceId());
    }

    @Test
    void usesVectorWhenAvailableAndMarksNotDegraded() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) { return new double[]{1.0, 0.0}; }
        });
        retriever.rebuild(corpus());
        RetrievalResult result = retriever.retrieve("白霉", "番茄", 3);
        assertFalse(result.isDegraded());
        assertTrue(result.getTopScore() > 0.0);
    }

    @Test
    void flagsLowScoreResults() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) { return new double[]{1.0, 0.0}; }
        });
        retriever.rebuild(corpus());
        assertTrue(retriever.isLowScore(retriever.retrieve("量子计算机", "番茄", 3)));
        assertFalse(retriever.isLowScore(retriever.retrieve("褐色轮纹斑", "番茄", 3)));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest='RrfFusionTest,KnowledgeRetrieverTest' test
```
Expected: 编译失败 `cannot find symbol: class RrfFusion`

- [ ] **Step 3: 实现向量检索、融合与降级**

- `RrfFusion.fuse`：按 `contentHash` 归并；得分 `Σ 1/(k + rank)`；排序（分数降序、`contentHash` 升序兜底）；截断 `topN`；重排 `rank`。
- `VectorIndex`：内存持有 `Map<String, double[]>`；`List<ScoredChunk> search(double[] query, int topK)` 用余弦相似度（向量已在 Flask 侧 L2 归一化，仍做防御性归一化）；零向量返回空。
- `HttpEmbeddingClient`：`RestTemplate`（复用 `DeepSeekHttpConfig` 的连接超时风格，读取超时 **3000ms**）POST `{EMBEDDING_BASE_URL}/embed`，body `{"texts":[text]}`，解析 `vectors[0]`；HTTP 非 200、超时、解析失败统一抛 `EmbeddingUnavailableException`。
- `KnowledgeRetriever.retrieve`：BM25 `search(query, TOP_K_EACH)`；再 `try { vectorIndex.search(embeddingClient.embed(query), TOP_K_EACH) } catch (EmbeddingUnavailableException e) { degraded = true; reason = "EMBEDDING_UNAVAILABLE" }`；`RrfFusion.fuse(lists, RRF_K, topN)`；`topScore = items.isEmpty() ? 0.0 : items.get(0).getScore()`；`cropType` 非空时先按作物过滤候选（`diseaseName` 含作物名或 `cropType` 相等）。
- `isLowScore`：`result.getItems().isEmpty() || result.getTopScore() < MIN_SCORE`。

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest='RrfFusionTest,KnowledgeRetrieverTest' test
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```powershell
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent\rag" "$SB\src\test\java\com\example\Ece\agent\rag"
git -C $ROOT commit -m "feat: add vector retrieval with rrf fusion and bm25 fallback"
```

---

### Task 4: Flask `/embed` 端点与视觉链路缺陷修复

**Files:**
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Flask\predict\embedding.py`
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Flask\requirements.txt`
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Flask\scripts\check_embed.py`
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Flask\scripts\check_confidence.py`
- Modify: `$ROOT\YOLO_AI_CropDisease_Detection_Flask\main.py`（新增 `/embed` 路由）
- Modify: `$ROOT\YOLO_AI_CropDisease_Detection_Flask\predict\predictImg.py`（`conf` 覆盖与置信度放大两处缺陷）

**Interfaces:**
- Produces: `POST /embed` ← `{"texts":["..."]}` → `{"vectors":[[...]],"model":"BAAI/bge-small-zh-v1.5","dim":512}`，失败返回 503 `{"error":"EMBEDDING_UNAVAILABLE"}`
- Produces: `ImagePredictor.conf` 尊重入参；`predict()` 返回**原始置信度**（百分比字符串），不再做 90%–99.99% 放大

- [ ] **Step 1: 安装依赖并写自检脚本**

```powershell
$FLASK = "$ROOT\YOLO_AI_CropDisease_Detection_Flask"
py -3 -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple sentence-transformers
```

`scripts/check_embed.py`：导入 `predict.embedding.embed`，断言 `len(vectors)==2`、`len(vectors[0])==512`、自身余弦相似度 > 0.999、`"番茄早疫病"` 与 `"番茄晚疫病"` 的余弦相似度**低于** `"番茄早疫病"` 与 `"番茄叶片褐色轮斑"` 的相似度（语义单调性），全部通过则打印 `EMBEDDING CHECK OK`。

- [ ] **Step 2: 运行自检确认失败**

```powershell
Set-Location $FLASK; py -3 scripts\check_embed.py
```
Expected: `ModuleNotFoundError: No module named 'predict.embedding'`

- [ ] **Step 3: 实现 embedding 服务与路由**

`predict/embedding.py`：模块级懒加载 + 线程锁（首次请求加载模型并缓存），`embed(texts)` 用 `SentenceTransformer.encode(texts, normalize_embeddings=True, convert_to_numpy=True).tolist()`；模型名取 `EMBEDDING_MODEL` 环境变量，默认 `BAAI/bge-small-zh-v1.5`。

`main.py` 在 `setup_routes` 内加 `self.app.add_url_rule('/embed', 'embed', self.embed, methods=['POST'])`，实现 `def embed(self)`：空入参返回 400 `EMPTY_INPUT`；异常返回 503 `EMBEDDING_UNAVAILABLE`（错误信息截断 200 字符）；成功返回上述 JSON。

`requirements.txt`：`flask`、`flask-socketio`、`ultralytics`、`opencv-python`、`requests`、`sentence-transformers`（版本按当前环境 `py -3 -m pip freeze` 结果回填）。

- [ ] **Step 4: 运行自检确认通过**

```powershell
Set-Location $FLASK; py -3 scripts\check_embed.py
```
Expected: `EMBEDDING CHECK OK`（首次运行需下载模型，约 95MB）

- [ ] **Step 5: 修复 predictImg 两处缺陷**

- 构造函数：`self.conf = float(conf)`（原为硬编码 `0.1`，导致 `main.py` 与前端传入阈值失效）。
- 删除 `map_confidence` 的 90%–99.99% 放大，直接输出原始置信度百分比；方法保留但改为 `return original_conf` 并在 docstring 注明"保留原始置信度，不做展示性放大"。

`scripts/check_confidence.py`：不加载权重，直接断言 `ImagePredictor.__init__` 后 `predictor.conf == 0.42`（传 `conf=0.42`），并对假 `results` 断言 `map_confidence(0.31) == 0.31`；通过则打印 `CONFIDENCE CHECK OK`。

- [ ] **Step 6: 运行自检确认通过**

```powershell
Set-Location $FLASK; py -3 scripts\check_confidence.py
```
Expected: `CONFIDENCE CHECK OK`

- [ ] **Step 7: 端到端冒烟（需 MySQL 未启动也可，仅 Flask）**

```powershell
Set-Location $FLASK; Start-Process -FilePath py -ArgumentList '-3','main.py' -WindowStyle Hidden; Start-Sleep 8
Invoke-RestMethod -Uri 'http://127.0.0.1:5000/embed' -Method Post -ContentType 'application/json' -Body '{"texts":["番茄早疫病症状"]}' | ConvertTo-Json -Depth 3 -Compress | ForEach-Object { $_.Substring(0, 160) }
```
Expected: 含 `"dim":512` 的 JSON

- [ ] **Step 8: 提交**

```powershell
git -C $ROOT add YOLO_AI_CropDisease_Detection_Flask
git -C $ROOT commit -m "feat: add embedding endpoint and fix predictor confidence handling"
```

---

## 阶段 B：编排与守门

### Task 5: 引用格式化、检索工具与步骤审计

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\CitationFormatter.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\tool\AgentTool.java`、`ToolPermission.java`、`ToolException.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\tool\KnowledgeSearchTool.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\repository\AgentStepTraceRepository.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\rag\CitationFormatterTest.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\tool\KnowledgeSearchToolTest.java`

**Interfaces:**
- Produces:
  - `class CitationFormatter`：`List<Map<String, Object>> toCitations(List<ScoredChunk> items)`（每项 `index`/`diseaseName`/`fieldType`/`chunkNo`/`score`/`label`）、`String toPromptBlock(List<ScoredChunk> items)`（拼成 `[n] 病名·字段·片段k：正文`）
  - `enum ToolPermission { READ_ONLY, DRAFT, WRITE_REQUIRES_APPROVAL }`
  - `interface AgentTool { String name(); String description(); ToolPermission permission(); String inputSchemaJson(); Map<String,Object> execute(Map<String,Object> input) throws ToolException; }`
  - `class KnowledgeSearchTool implements AgentTool`：`name() = "knowledge.search"`，入参 `query`(必填)、`crop`(选填)、`topN`(默认 5)
  - `class AgentStepTraceRepository`：`void record(String sessionId, Long runId, int stepNo, String toolName, String inputDigest, String outputDigest, long durationMs, boolean degraded, String status)`；用 `JdbcTemplate`

- [ ] **Step 1: 写失败测试**

```java
package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CitationFormatterTest {
    private final CitationFormatter formatter = new CitationFormatter();

    private ScoredChunk hit(long id, String name, KnowledgeChunk.FieldType type, int chunkNo, double score) {
        return new ScoredChunk(new KnowledgeChunk("disease", id, "番茄", name, type, chunkNo, 0,
                "叶片出现褐色轮纹斑", "h" + id), score, 1);
    }

    @Test
    void buildsNumberedCitations() {
        List<Map<String, Object>> citations = formatter.toCitations(Arrays.asList(
                hit(1L, "早疫病", KnowledgeChunk.FieldType.SYMPTOM, 0, 0.0325),
                hit(2L, "晚疫病", KnowledgeChunk.FieldType.CONTROL, 1, 0.0161)));
        assertEquals(2, citations.size());
        assertEquals(1, citations.get(0).get("index"));
        assertEquals("早疫病", citations.get(0).get("diseaseName"));
        assertEquals("SYMPTOM", citations.get(0).get("fieldType"));
        assertEquals(0, citations.get(0).get("chunkNo"));
        assertTrue(String.valueOf(citations.get(0).get("label")).contains("早疫病"));
    }

    @Test
    void promptBlockCarriesIndexAndSource() {
        String block = formatter.toPromptBlock(Arrays.asList(hit(1L, "早疫病", KnowledgeChunk.FieldType.SYMPTOM, 0, 0.03)));
        assertTrue(block.contains("[1]"));
        assertTrue(block.contains("早疫病"));
        assertTrue(block.contains("叶片出现褐色轮纹斑"));
    }
}
```

```java
package com.example.Ece.agent.tool;

import com.example.Ece.agent.rag.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSearchToolTest {
    private KnowledgeSearchTool toolWith(final boolean degraded) {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) throws EmbeddingUnavailableException {
                if (degraded) throw new EmbeddingUnavailableException("down");
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(Arrays.asList(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1")));
        return new KnowledgeSearchTool(retriever, new CitationFormatter());
    }

    @Test
    void exposesMetadata() {
        KnowledgeSearchTool tool = toolWith(false);
        assertEquals("knowledge.search", tool.name());
        assertEquals(ToolPermission.READ_ONLY, tool.permission());
        assertTrue(tool.inputSchemaJson().contains("query"));
    }

    @Test
    void returnsCitationsAndDegradedFlag() throws ToolException {
        Map<String, Object> output = toolWith(true).execute(new HashMap<String, Object>(
                Collections.singletonMap("query", "褐色轮纹斑")));
        assertEquals(Boolean.TRUE, output.get("degraded"));
        assertEquals("EMBEDDING_UNAVAILABLE", output.get("degradedReason"));
        assertFalse(((List<?>) output.get("citations")).isEmpty());
    }

    @Test
    void rejectsMissingQuery() {
        assertThrows(ToolException.class, () -> toolWith(false).execute(new HashMap<String, Object>()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest='CitationFormatterTest,KnowledgeSearchToolTest' test
```
Expected: 编译失败 `cannot find symbol: class CitationFormatter`

- [ ] **Step 3: 实现**

按上面 Interfaces 实现；`KnowledgeSearchTool.execute` 返回 `{citations, promptBlock, degraded, degradedReason, topScore}`；`inputDigest` 用 `KnowledgeChunker.sha256(query + "|" + crop + "|" + topN)`。

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest='CitationFormatterTest,KnowledgeSearchToolTest' test
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```powershell
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent" "$SB\src\test\java\com\example\Ece\agent"
git -C $ROOT commit -m "feat: add citation formatting, knowledge tool and step audit"
```

---

### Task 6: 编排循环、工具注册表与 SSE 接口

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\orchestrator\AgentOrchestrator.java`、`AgentSession.java`、`AgentStepEvent.java`、`AgentResult.java`、`LlmClient.java`、`DeepSeekLlmClient.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\tool\AgentToolRegistry.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\controller\AgentChatController.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\dto\AgentChatRequest.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\orchestrator\AgentOrchestratorTest.java`

**Interfaces:**
- Consumes: `AgentTool`、`AgentToolRegistry`、`KnowledgeSearchTool`（Task 5）
- Produces:
  - `interface LlmClient { String plan(List<Map<String,Object>> history) ; String compose(List<Map<String,Object>> history); }`
  - `class AgentToolRegistry`：`void register(AgentTool tool)`、`AgentTool find(String name)`、`List<AgentTool> all()`、`String catalogJson()`
  - `class AgentStepEvent`：`String type; int stepNo; String toolName; String message; Map<String,Object> payload;`
- `class AgentResult`：字段 `String answer; List<Map<String,Object>> citations; List<AgentStepEvent> events; int steps; Status status;`，枚举 `Status { DONE, REFUSED, ERROR }`，含 getter
  - `class AgentOrchestrator`：`AgentResult run(String sessionId, String question, String crop, Consumer<AgentStepEvent> sink)`；常量 `MAX_STEPS = 6`、`MAX_TOOL_REPEAT = 2`、`STEP_TIMEOUT_MS = 20000`、`TOTAL_TIMEOUT_MS = 90000`
  - `POST /api/ai/agent/chat`（SSE，事件名 `step`/`citation`/`delta`/`final`/`error`）

- [ ] **Step 1: 写失败测试（用脚本化假 LLM）**

```java
package com.example.Ece.agent.orchestrator;

import com.example.Ece.agent.rag.*;
import com.example.Ece.agent.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {
    private AgentToolRegistry registry() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) { return new double[]{1.0, 0.0}; }
        });
        retriever.rebuild(Arrays.asList(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1")));
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new KnowledgeSearchTool(retriever, new CitationFormatter()));
        return registry;
    }

    @Test
    void runsToolThenFinalizes() {
        final int[] planCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                if (planCalls[0] == 1) return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}";
                return "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }
            public String compose(List<Map<String, Object>> history) { return "疑似早疫病，请结合田间情况复核。[1]"; }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        List<AgentStepEvent> events = new ArrayList<AgentStepEvent>();
        AgentResult result = orchestrator.run("s1", "叶子有褐色轮纹斑", "番茄", new java.util.function.Consumer<AgentStepEvent>() {
            public void accept(AgentStepEvent event) { events.add(event); }
        });
        assertEquals("疑似早疫病，请结合田间情况复核。[1]", result.getAnswer());
        assertTrue(events.stream().anyMatch(e -> "knowledge.search".equals(e.getToolName())));
        assertTrue(events.stream().anyMatch(e -> "final".equals(e.getType())));
        assertFalse(result.getCitations().isEmpty());
    }

    @Test
    void stopsAtMaxStepsAndRefusesWhenNoEvidence() {
        LlmClient looping = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"完全不相关的问题\"}}";
            }
            public String compose(List<Map<String, Object>> history) { return "无依据"; }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), looping);
        AgentResult result = orchestrator.run("s2", "量子计算机", "番茄", new java.util.function.Consumer<AgentStepEvent>() {
            public void accept(AgentStepEvent event) { }
        });
        assertTrue(result.getSteps() <= AgentOrchestrator.MAX_STEPS);
        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=AgentOrchestratorTest test
```
Expected: 编译失败 `cannot find symbol: class AgentOrchestrator`

- [ ] **Step 3: 实现编排层**

- 循环：`plan` → 解析 `{"tool","input"}`（fastjson；解析失败视为 `FINALIZE`）→ 若 `FINALIZE` 则 `compose` 输出；否则查注册表、校验权限（`WRITE_REQUIRES_APPROVAL` 仅允许 `report.export` 且需请求方确认）、执行、发 `step` 事件、把结果摘要追加进 history。
- 上限：`stepNo > MAX_STEPS`、同工具 > `MAX_TOOL_REPEAT`、`stepNo|tool|inputDigest` 重复 → 直接 `FINALIZE` 或 `REFUSED`；整体耗时 > `TOTAL_TIMEOUT_MS` → 强制用已有证据 `compose`。
- 拒答条件：全程无任何引用且问题含专业诉求 → `Status.REFUSED`，回答固定文案"没有找到可靠依据，建议联系当地农技人员"。
- `DeepSeekLlmClient`：包一层现有 `DeepSeekService.chat`，`plan` 用系统提示（工具目录 + 严格 JSON 输出），`compose` 用系统提示（只能依据给定引用作答、必须标注 `[n]`、涉药加"需人工确认"）。
- `AgentChatController`：`@PostMapping(value = "/ai/agent/chat", produces = "text/event-stream")` 返回 `SseEmitter`（超时 `TOTAL_TIMEOUT_MS + 5000`），在独立线程里跑 `orchestrator.run`，逐事件 `emitter.send(SseEmitter.event().name(event.getType()).data(json))`。

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=AgentOrchestratorTest test
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量回归**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" test
```
Expected: 既有 18 项 + 新增全部通过，`Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```powershell
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent" "$SB\src\test\java\com\example\Ece\agent"
git -C $ROOT commit -m "feat: add agent orchestration loop with sse chat endpoint"
```

---

### Task 7: 安全守门与处方服务

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\guard\GuardrailService.java`、`GuardrailViolation.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\service\PrescriptionService.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\guard\GuardrailServiceTest.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\service\PrescriptionServiceTest.java`

**Interfaces:**
- Consumes: `ScoredChunk`、`SimulationState`、`DecisionPlan`（既有 `TomatoDecisionPolicy`）
- Produces:
  - `class GuardrailService`：`GuardrailCheck check(String answer, List<ScoredChunk> citations, boolean degraded)`；`GuardrailCheck` 含 `boolean allowed; String reason; String rewrittenAnswer;`
  - `class PrescriptionService`：`Prescription draft(SimulationState state, DecisionPlan plan, List<ScoredChunk> citations)`；`Prescription` 含 `String conclusion; List<String> actions; List<String> cautions; boolean requiresManualConfirm;`

- [ ] **Step 1: 写失败测试**

```java
package com.example.Ece.agent.guard;

import com.example.Ece.agent.rag.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuardrailServiceTest {
    private final GuardrailService guard = new GuardrailService();

    private ScoredChunk citation() {
        return new ScoredChunk(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.CONTROL, 0, 0, "可选用代森锰锌", "h1"), 0.03, 1);
    }

    @Test
    void allowsAnswerWithCitations() {
        GuardrailCheck check = guard.check("建议通风降湿。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed());
    }

    @Test
    void rejectsProfessionalClaimWithoutCitation() {
        GuardrailCheck check = guard.check("喷施戊唑醇 3000 倍液即可。", new ArrayList<ScoredChunk>(), false);
        assertFalse(check.isAllowed());
        assertEquals("NO_EVIDENCE", check.getReason());
    }

    @Test
    void forcesManualConfirmForPesticideMentions() {
        GuardrailCheck check = guard.check("可选用代森锰锌防治。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed());
        assertTrue(check.getRewrittenAnswer().contains("需人工确认"));
    }

    @Test
    void disclosesDegradedRetrieval() {
        GuardrailCheck check = guard.check("建议通风。[1]", Arrays.asList(citation()), true);
        assertTrue(check.getRewrittenAnswer().contains("降级"));
    }

    @Test
    void forbidsDeviceExecutionClaims() {
        GuardrailCheck check = guard.check("已自动开启通风设备。[1]", Arrays.asList(citation()), false);
        assertFalse(check.isAllowed());
        assertEquals("AUTO_EXECUTION_CLAIM", check.getReason());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=GuardrailServiceTest test
```
Expected: 编译失败 `cannot find symbol: class GuardrailService`

- [ ] **Step 3: 实现守门 6 条与处方结构**

守门规则按 spec §11：① 涉药必须带引用且补"需人工确认/遵循当地用药规范"；② 出现"已自动/已执行/已开启设备"等表述 → 拒绝（`AUTO_EXECUTION_CLAIM`）；③ 无引用却含专业结论（药剂名、浓度、倍液、用量）→ 拒答（`NO_EVIDENCE`）；④ `degraded` → 追加"（本次为降级检索：仅关键词匹配）"；⑤ 出现把仿真说成实测的措辞（"实测""现场检测到"）→ 重写为"推演显示"；⑥ 回答超长（> 4000 字）→ 截断并提示。

`PrescriptionService`：把 `DecisionPlan` 的动作与资源量转成"做什么/何时/用量"，把风险等级与阻断原因转成 `cautions`；涉药或改动设备 → `requiresManualConfirm = true`。

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest='GuardrailServiceTest,PrescriptionServiceTest' test
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```powershell
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent" "$SB\src\test\java\com\example\Ece\agent"
git -C $ROOT commit -m "feat: add guardrail rules and prescription drafting"
```

---

## 阶段 C：评测、交付物与知识图谱

### Task 8: 检索评测集（E-A）与分析质量 A/B（E-B）

**Files:**
- Create: `$ROOT\docs\eval\retrieval-questions.md`（40 问 + 期望出处）
- Create: `$ROOT\docs\eval\ab-comparison-template.md`（四档对照表模板与操作步骤）
- Create: `$SB\src\test\java\com\example\Ece\agent\rag\RetrievalEvalTest.java`

**Interfaces:**
- Consumes: `KnowledgeRetriever`、`KnowledgeChunker`
- Produces: 控制台指标报告 `Top1 命中率 / Top3 命中率 / 引用正确率 / 降级次数`

- [ ] **Step 1: 写评测测试**

`RetrievalEvalTest`：用 `@Test void reportRetrievalMetrics()` 读 `docs/eval/retrieval-questions.md`（格式 `问题 | 期望病名`），语料用固定的 12 条内置知识块（避免依赖 MySQL），断言 `Top3 命中率 >= 0.8`，并 `System.out.printf` 输出完整指标表。

- [ ] **Step 2: 运行并记录基线**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=RetrievalEvalTest test
```
Expected: 打印指标表，`Top3 命中率` ≥ 0.8；把该输出**原样粘贴到 `docs/eval/retrieval-questions.md` 末尾的"基线记录"小节**

- [ ] **Step 3: 写 A/B 模板（E-B）**

`ab-comparison-template.md`：四档（裸 LLM / +RAG / +RAG+KG / +agent）× 指标（术语命中率、引用正确率、可执行项数、评审分 1–5）；给出每档的执行步骤（用 `POST /api/ai/agent/chat` 与 `POST /api/ai/chat` 各跑同一批 20 问）与填表说明。

- [ ] **Step 4: 提交**

```powershell
git -C $ROOT add docs/eval "$SB\src\test\java\com\example\Ece\agent\rag\RetrievalEvalTest.java"
git -C $ROOT commit -m "test: add retrieval evaluation set and ab comparison template"
```

---

### Task 9: 报告导出（自包含 HTML + 打印为 PDF）

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\service\ReportService.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\controller\ReportController.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\service\ReportServiceTest.java`

**Interfaces:**
- Consumes: `AgentRunSummaryResponse`、`AgentRunComparisonResponse`（既有 `AgentRunService`）
- Produces: `String ReportService.renderHtml(Long runId)`；`GET /api/agent/runs/{runId}/report.html`（`Content-Type: text/html;charset=UTF-8`）

- [ ] **Step 1: 写失败测试**

`ReportServiceTest`：注入 stub 的 `AgentRunService`（返回固定摘要与对比），断言渲染结果含 ① 数据来源标记 `SIMULATED`、② 生成的日期、③ `@media print` 样式块、④ 所有环境数值段落都带"（推演值）"后缀。

- [ ] **Step 2: 运行确认失败 → 实现 → 运行确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=ReportServiceTest test
```
Expected: 先失败，实现后 `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 3: 提交**

```powershell
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent"
git -C $ROOT commit -m "feat: add printable html report export"
```

---

### Task 10: 知识图谱抽取、子图接口与可视化数据

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\kg\KnowledgeGraphService.java`、`GraphNode.java`、`GraphEdge.java`、`GraphSubgraph.java`、`GraphRepository.java`、`InMemoryGraphRepository.java`、`JdbcGraphRepository.java`
- Create: `$ROOT\database\seeds\V20260922_02__agent_kg_seed.sql`（从 `disease` 表抽取 + 权威数据源补全的节点/边种子）
- Create: `$SB\src\main\java\com\example\Ece\agent\controller\KnowledgeGraphController.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\kg\KnowledgeGraphServiceTest.java`

**Interfaces:**
- Produces: `class GraphSubgraph { List<GraphNode> nodes; List<GraphEdge> edges; }`（含 getter）；`GraphSubgraph KnowledgeGraphService.subgraph(String name, int depth)`；`GET /api/knowledge/graph?name=&depth=2` → `{nodes:[{id,name,type}], edges:[{source,target,relation}]}`（ECharts graph 可直接消费）
- 每个节点/边必须带 `sourceName` 与 `version`；缺失出处的数据**不得入库**

- [ ] **Step 1: 写失败测试**（用内存 stub 仓储构造 2 跳图，断言节点去重、深度限制、缺失出处时抛 `IllegalArgumentException`）
- [ ] **Step 2: 运行确认失败 → 实现 → 运行确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=KnowledgeGraphServiceTest test
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 3: 提交**

```powershell
git -C $ROOT add database/seeds "$SB\src\main\java\com\example\Ece\agent" "$SB\src\test\java\com\example\Ece\agent"
git -C $ROOT commit -m "feat: add knowledge graph service, seed data and subgraph api"
```

---

### Task 11: 前端智能体对话面板

**Files:**
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Vue\src\api\agent\chat.ts`
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Vue\src\views\agentCenter\components\AgentChat.vue`
- Modify: `$ROOT\YOLO_AI_CropDisease_Detection_Vue\src\views\agentCenter\index.vue`（挂载面板）

**Interfaces:**
- Consumes: `POST /api/ai/agent/chat`（SSE）、引用结构（Task 5）
- Produces: 对话面板（流式正文 + 步骤时间线 + 引用列表 + 降级提示 + 拒答态）

- [ ] **Step 1: 实现 SSE 客户端**

`chat.ts`：用 `fetch` + `ReadableStream` 解析 `event:`/`data:`（不用 `EventSource`，因为要 POST）；导出 `streamAgentChat(payload, handlers)`，handlers 含 `onStep/onCitation/onDelta/onFinal/onError`。

- [ ] **Step 2: 实现面板组件**

`AgentChat.vue`：输入框（含"上传影像并提问"入口）、消息列表、`el-timeline` 展示步骤（工具名 + 状态 + 耗时 + 降级标签）、引用以可点击标签渲染（点击滚动到对应引用块）、`SIMULATED`/`VISION_SIGNAL` 徽标、拒答与降级提示条。

- [ ] **Step 3: 生产构建校验**

```powershell
Set-Location "$ROOT\YOLO_AI_CropDisease_Detection_Vue"; npm run build
```
Expected: `built in ...`，无新增 error（既有 Sass/字体告警可忽略）

- [ ] **Step 4: 提交**

```powershell
git -C $ROOT add YOLO_AI_CropDisease_Detection_Vue/src
git -C $ROOT commit -m "feat: add streaming agent chat panel to command center"
```

---

## 阶段 D：作物生长模型与 AI 性能评测平台（2026-09-22 追加）

> 定位：模型不是展示动画，而是**AI 性能的验证平台**。做法是同一初始条件下跑四档对照，用多目标矩阵量化"带 AI 的决策优于不带 AI"。
> 设计依据：spec §20、§21。

### Task 12: 作物生长模型（半机理）

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\crop\CropStage.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\crop\TomatoGrowthParameters.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\crop\TomatoCropState.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\crop\TomatoCropGrowthModel.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\crop\TomatoCropGrowthModelTest.java`

**Interfaces:**
- Consumes: `SimulationState`（既有，含 `getTemperatureC/getAirHumidityPct/getSoilMoisturePct/getCo2Ppm/getLightPpfd/getVpd/getSimulatedAt`）
- Produces:
  - `enum CropStage { SEEDLING, FLOWERING, FRUIT_SET, FRUIT_GROWTH, MATURITY }`
  - `class TomatoGrowthParameters`：常量与 `sourceNote` 字符串（`BASE_TEMPERATURE_C=10.0`、`OPTIMAL_LOW_C=18.0`、`OPTIMAL_HIGH_C=28.0`、`MAX_TEMPERATURE_C=35.0`、`EXTINCTION_K=0.65`、`RUE_G_PER_MJ=3.0`、`SLA_M2_PER_G=0.02`、`SENESCENCE_PER_DAY=0.02`、`FRUIT_ALLOCATION_RATIO=0.55`、`GDD_FLOWERING=600`、`GDD_FRUIT_SET=800`、`GDD_FRUIT_GROWTH=1200`、`GDD_MATURITY=1500`、`MAX_LAI=6.0`、`VPD_FRUIT_SET_LIMIT_KPA=2.0`）
  - `class TomatoCropState`：不可变，含 `gdd/stage/lai/plantHeightCm/wLeaf/wStem/wRoot/wFruit/wTotal/fruitSetRate/fruitCount/singleFruitWeightG/temperatureFactor/co2Factor/waterFactor`，提供 `with...` 风格构造或全参构造 + getter
  - `class TomatoCropGrowthModel`：`TomatoCropState initial()`、`TomatoCropState advance(TomatoCropState current, SimulationState environment, int minutes)`

- [ ] **Step 1: 写失败测试**

```java
package com.example.Ece.agent.crop;

import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class TomatoCropGrowthModelTest {
    private final TomatoCropGrowthModel model = new TomatoCropGrowthModel();
    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();

    private SimulationState environment(double temperature, double humidity, double soil, double co2, double light) {
        return engine.evaluate(LocalDateTime.of(2026, 9, 21, 12, 0), temperature, humidity, soil, co2, light, 6.2);
    }

    private TomatoCropState simulate(SimulationState environment, int steps) {
        TomatoCropState state = model.initial();
        for (int i = 0; i < steps; i++) state = model.advance(state, environment, 15);
        return state;
    }

    @Test
    void isDeterministicForSameInputs() {
        SimulationState env = environment(24.0, 70.0, 60.0, 800.0, 500.0);
        TomatoCropState first = simulate(env, 96);
        TomatoCropState second = simulate(env, 96);
        assertEquals(first.getGdd(), second.getGdd(), 1e-9);
        assertEquals(first.getLai(), second.getLai(), 1e-9);
        assertEquals(first.getWFruit(), second.getWFruit(), 1e-9);
        assertEquals(first.getStage(), second.getStage());
    }

    @Test
    void growingDegreeDaysIncreaseWithTemperature() {
        TomatoCropState cool = simulate(environment(12.0, 70.0, 60.0, 800.0, 400.0), 96);
        TomatoCropState warm = simulate(environment(26.0, 70.0, 60.0, 800.0, 400.0), 96);
        assertTrue(warm.getGdd() > cool.getGdd());
        assertTrue(cool.getGdd() >= 0.0);
    }

    @Test
    void leafAreaRisesThenPlateausWithoutFalling() {
        TomatoCropState early = simulate(environment(24.0, 70.0, 60.0, 800.0, 600.0), 96);
        TomatoCropState later = simulate(environment(24.0, 70.0, 60.0, 800.0, 600.0), 480);
        assertTrue(early.getLai() > 0.0);
        assertTrue(later.getLai() >= early.getLai() * 0.5);
        assertTrue(later.getLai() <= TomatoGrowthParameters.MAX_LAI);
    }

    @Test
    void highTemperatureReducesFruitSet() {
        TomatoCropState optimal = simulate(environment(24.0, 70.0, 60.0, 800.0, 600.0), 480);
        TomatoCropState hot = simulate(environment(34.0, 55.0, 60.0, 800.0, 600.0), 480);
        assertTrue(hot.getFruitSetRate() < optimal.getFruitSetRate());
    }

    @Test
    void waterStressReducesDryMatterAndFruitNeverExceedsTotal() {
        TomatoCropState wet = simulate(environment(24.0, 70.0, 70.0, 800.0, 600.0), 480);
        TomatoCropState dry = simulate(environment(24.0, 45.0, 25.0, 800.0, 600.0), 480);
        assertTrue(dry.getWaterFactor() < 1.0);
        assertTrue(dry.getWTotal() < wet.getWTotal());
        assertTrue(dry.getWFruit() <= dry.getWTotal() + 1e-9);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=TomatoCropGrowthModelTest test
```
Expected: 编译失败 `cannot find symbol: class TomatoCropGrowthModel`

- [ ] **Step 3: 实现模型**

实现要点（严格按 spec §20 公式与钳制规则）：
- `advance` 内先算日聚合：以 15 分钟步长累计当日均温与日辐射，跨日时更新 `gdd`、`lai` 衰老、`stage` 迁移；`gdd` 用 `max(0, T_avg - BASE_TEMPERATURE_C)`。
- `temperatureFactor` 用最优区间 `[18, 28]`，超出后线性衰减到 `MAX_TEMPERATURE_C` 归零；`co2Factor` 用 `1.0 + min(0.35, max(0.0, (co2 - 400) / 400 * 0.35))`；`waterFactor` 用土壤水分的分段线性（`< 45%` 线性衰减到 0.3，`> 78%` 触发湿害系数 0.9）。
- `dW = RUE × I_abs × f_temp × f_co2 × f_water`，其中 `I_abs = 0.5 × light × (1 - exp(-K × lai))`，光照按 15 分钟折算为 MJ·m⁻²（`light × minutes × 60 / 1e6`）。
- 分配：`SEEDLING/FLOWERING` 按叶 0.5 / 茎 0.3 / 根 0.2；`FRUIT_SET` 起果实占比 `FRUIT_ALLOCATION_RATIO`，其余按叶 0.3 / 茎 0.25 / 根 0.45 归一化。
- 坐果率：`f_temp(15–30℃ 区间最优，>32℃ 线性降到 0.2) × f_vpd(VPD>2.0 kPa 线性衰减) × min(1.0, wTotal / 阈值)`，仅在 `FRUIT_SET` 之后生效；单果重按坐果后积温增长。
- 钳制：`lai ∈ [0, MAX_LAI]`、所有干重 `≥ 0`、`wFruit ≤ wTotal`、`stage` 单调不回退。

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=TomatoCropGrowthModelTest test
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```powershell
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent\crop" "$SB\src\test\java\com\example\Ece\agent\crop"
git -C $ROOT commit -m "feat: add semi-mechanistic tomato crop growth model"
```

---

### Task 13: AI 性能评测平台（四档对照 + 指标矩阵）

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\eval\EvaluationStrategy.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\eval\EvaluationBatch.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\eval\PerformanceEvaluationService.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\eval\EvaluationController.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\eval\PerformanceEvaluationServiceTest.java`

**Interfaces:**
- Consumes: `TomatoCropGrowthModel`、`TomatoSimulationEngine`、`TomatoDecisionPolicy`（Task 12 与既有）
- Produces:
  - `enum EvaluationStrategy { P0_NONE, P1_FIXED_MANUAL, P2_RULE_ENGINE, P3_AGENT }`
  - `class EvaluationBatch`：`batchId`、`seed`、`days`、`Map<EvaluationStrategy, EvaluationOutcome> outcomes`
  - `class EvaluationOutcome`：`wFruit`、`singleFruitWeightG`、`fruitSetRate`、`waterUsed`、`energyUsed`、`co2Used`、`highTemperatureMinutes`、`highHumidityMinutes`、`highVpdMinutes`、`diseasePressureIntegral`、`constraintViolations`、`List<Map<String,Object>> series`
  - `class PerformanceEvaluationService`：`EvaluationBatch runBatch(String batchId, long seed, int days)`（**默认 `days = 120`**；短地平线评测必须显式传入并以 `TomatoCropState` 的预置成株初值起跑）
  - HTTP：`POST /api/eval/runs`、`GET /api/eval/{batchId}/matrix`、`GET /api/eval/{batchId}/series`

- [ ] **Step 1: 写失败测试**

```java
package com.example.Ece.agent.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceEvaluationServiceTest {
    private final PerformanceEvaluationService service = new PerformanceEvaluationService();

    @Test
    void producesAllFourStrategies() {
        EvaluationBatch batch = service.runBatch("batch-a", 20260921L, 3);
        assertEquals(4, batch.getOutcomes().size());
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            assertTrue(batch.getOutcomes().containsKey(strategy), "missing " + strategy);
        }
    }

    @Test
    void isReproducibleForSameSeed() {
        EvaluationBatch first = service.runBatch("batch-b", 7L, 2);
        EvaluationBatch second = service.runBatch("batch-b", 7L, 2);
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            assertEquals(first.getOutcomes().get(strategy).getWFruit(),
                    second.getOutcomes().get(strategy).getWFruit(), 1e-9);
            assertEquals(first.getOutcomes().get(strategy).getHighTemperatureMinutes(),
                    second.getOutcomes().get(strategy).getHighTemperatureMinutes());
        }
    }

    @Test
    void regulatedStrategiesOutperformNoControlOnFruitOrRisk() {
        EvaluationBatch batch = service.runBatch("batch-c", 20260921L, 5);
        EvaluationOutcome none = batch.getOutcomes().get(EvaluationStrategy.P0_NONE);
        EvaluationOutcome rule = batch.getOutcomes().get(EvaluationStrategy.P2_RULE_ENGINE);
        assertTrue(rule.getHighTemperatureMinutes() <= none.getHighTemperatureMinutes());
        assertTrue(rule.getWFruit() >= none.getWFruit() * 0.95);
    }

    @Test
    void seriesCarryDailyProgression() {
        EvaluationBatch batch = service.runBatch("batch-d", 1L, 3);
        List<Map<String, Object>> series = batch.getOutcomes().get(EvaluationStrategy.P2_RULE_ENGINE).getSeries();
        assertFalse(series.isEmpty());
        assertTrue(series.get(0).containsKey("lai"));
        assertTrue(series.get(0).containsKey("wFruit"));
        assertTrue(series.get(0).containsKey("day"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=PerformanceEvaluationServiceTest test
```
Expected: 编译失败 `cannot find symbol: class PerformanceEvaluationService`

- [ ] **Step 3: 实现四档策略与指标聚合**

> **地平线纪律**：番茄 24℃ 下仅 14 GDD/天，`GDD_FLOWERING=600` 约需 43 天。因此产量类指标必须在 **≥120 天**地平线上比较；测试用 3–5 天只为快速验证结构与可复现性，不得据此宣称产量差异。需要短地平线演示产量时，从预置成株初值（30 天龄、`lai ≈ 2.0`）起跑并在界面标注来源。

- `P0_NONE`：设备全关，环境自然演进，作物照常推进。
- `P1_FIXED_MANUAL`：每 6 小时固定开灌溉 1 步、每日 12:00 开通风 2 步（模拟经验农户），不读环境。
- `P2_RULE_ENGINE`：每步调用 `TomatoDecisionPolicy.decide(state)`，把 `targetOn` 映射为设备状态。
- `P3_AGENT`：`P2` 的动作集作为候选，额外用作物模型向前推演 N 步（N=8）比较 `wFruit` 与风险积分，取更优候选；无更优则沿用 `P2`。
- 指标聚合：每步累加高温/高湿/高 VPD 分钟数与病害环境压力；资源按 `DeviceCommand` 的资源码与量累加；`constraintViolations` 统计通风与 CO₂ 同开等冲突次数（应为 0）；`series` 每日一条（day、gdd、lai、wTotal、wFruit、stage、环境风险）。
- 复现：全程无随机，`seed` 只影响天气相位的初始相位；同 seed 必须逐值一致。

- [ ] **Step 4: 运行测试确认通过**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" -Dtest=PerformanceEvaluationServiceTest test
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量回归并提交**

```powershell
& $MVN -Dmaven.repo.local=$REPO -f "$SB\pom.xml" test
git -C $ROOT add "$SB\src\main\java\com\example\Ece\agent\eval" "$SB\src\test\java\com\example\Ece\agent\eval"
git -C $ROOT commit -m "feat: add four-strategy performance evaluation platform"
```

---

### Task 14: 前端性能对比展示

**Files:**
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Vue\src\api\eval\index.ts`
- Create: `$ROOT\YOLO_AI_CropDisease_Detection_Vue\src\views\agentCenter\components\PerformanceMatrix.vue`
- Modify: `$ROOT\YOLO_AI_CropDisease_Detection_Vue\src\views\agentCenter\index.vue`（挂载）

**Interfaces:**
- Consumes: `POST /api/eval/runs`、`GET /api/eval/{batchId}/matrix`、`GET /api/eval/{batchId}/series`
- Produces: 四档对比矩阵表（含最优档高亮）、四类归一化雷达图、生长曲线（多档叠加）、器官分配堆叠图

- [ ] **Step 1: 实现 API 封装**

`src/api/eval/index.ts`：导出 `runEvaluation(payload)`、`getEvaluationMatrix(batchId)`、`getEvaluationSeries(batchId)`，沿用 `/@/utils/request` 实例并复用 `api/agent` 的 `unwrap` 信封解析方式。

- [ ] **Step 2: 实现展示组件**

`PerformanceMatrix.vue`：`el-table` 渲染指标矩阵（行=指标，列=四档，最优值加粗高亮）；`echarts` 雷达图（四类指标归一化到 0–100）；`echarts` 折线图（多档 LAI/wFruit 曲线叠加）；`echarts` 堆叠柱图（叶/茎/根/果干重分配）。所有图表在 `onUnmounted` 释放实例。

- [ ] **Step 3: 生产构建校验**

```powershell
Set-Location "$ROOT\YOLO_AI_CropDisease_Detection_Vue"; npm run build
```
Expected: 构建成功，无新增 error

- [ ] **Step 4: 提交**

```powershell
git -C $ROOT add YOLO_AI_CropDisease_Detection_Vue/src
git -C $ROOT commit -m "feat: add ai performance comparison views"
```

---
## 阶段 E：生态子系统与知识库 ingest（2026-09-22 追加）

> 依据：spec §22、§23。目标是把"环境 + 作物"扩为五子系统生态，并把语料从 100 条扩到千级且条条可溯源。
> 全部为纯 Java 半机理模型：确定性、可单测、参数逐条标 `sourceNote`；**不得编造文献页码**，拿不准就写"典型文献区间，待核对出处"。

### Task 15: 水肥土壤模型

**Files:**
- Create: `$SB\src\main\java\com\example\Ece\agent\eco\SoilParameters.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\eco\SoilState.java`
- Create: `$SB\src\main\java\com\example\Ece\agent\eco\SoilWaterNutrientModel.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\eco\SoilWaterNutrientModelTest.java`

**Interfaces:**
- Consumes: `SimulationState`、`TomatoCropState`（Task 12）
- Produces:
  - `class SoilState`（不可变 + getter）：`soilMoisturePct`、`ecDsPerM`、`soilPh`、`nitrogenKgPerHa`、`phosphorusKgPerHa`、`potassiumKgPerHa`、`leachedNitrogenKgPerHa`、`irrigationMmTotal`、`nutrientFactor`
  - `class SoilParameters`：`KC_INITIAL=0.6`、`KC_MID=1.15`、`KC_LATE=0.8`、`N_PER_KG_DM=0.035`、`P_PER_KG_DM=0.008`、`K_PER_KG_DM=0.045`、`EC_PER_KG_FERT=0.02`、`EC_LEACH_RATE=0.05`、`PH_MIN=4.0`、`PH_MAX=8.5`、`NUTRIENT_LOW=20.0`、`NUTRIENT_HIGH=120.0`（均含 `sourceNote`）
  - `class SoilWaterNutrientModel`：`SoilState initial()`、`SoilState advance(SoilState current, SimulationState environment, TomatoCropState crop, int minutes, boolean irrigationOn, double fertilizerKgPerHa)`

- [ ] **Step 1: 写失败测试**（`SoilWaterNutrientModelTest`，5 项）

```java
@Test void soilMoistureDropsWithoutIrrigation() {
    SoilState state = model.initial();
    for (int i = 0; i < 96; i++) state = model.advance(state, env(26.0, 60.0), crop(), 15, false, 0.0);
    assertTrue(state.getSoilMoisturePct() < model.initial().getSoilMoisturePct());
}
@Test void irrigationRaisesSoilMoisture() {
    SoilState dry = run(96, false, 0.0);
    SoilState wet = run(96, true, 0.0);
    assertTrue(wet.getSoilMoisturePct() > dry.getSoilMoisturePct());
}
@Test void fertilizerRaisesNitrogenAndEc() {
    SoilState base = run(96, false, 0.0);
    SoilState fed = run(96, false, 40.0);
    assertTrue(fed.getNitrogenKgPerHa() > base.getNitrogenKgPerHa());
    assertTrue(fed.getEcDsPerM() > base.getEcDsPerM());
}
@Test void nutrientFactorStaysInRangeAndFallsWhenDepleted() {
    SoilState depleted = run(480, false, 0.0);
    assertTrue(depleted.getNutrientFactor() >= 0.3 && depleted.getNutrientFactor() <= 1.0);
    assertTrue(depleted.getNutrientFactor() <= model.initial().getNutrientFactor());
}
@Test void isDeterministic() { assertEquals(run(96, true, 20.0).getSoilMoisturePct(), run(96, true, 20.0).getSoilMoisturePct(), 1e-9); }
```

- [ ] **Step 2: 运行确认失败** → `& $MVN "-Dmaven.repo.local=$REPO" -f "$SB\pom.xml" "-Dtest=SoilWaterNutrientModelTest" test`
- [ ] **Step 3: 实现**（按 spec §22.2 公式：Hargreaves ET0、Kc 查表、水分平衡、养分吸收与 EC/pH 缓冲；全部钳制非负，`soilPh ∈ [PH_MIN, PH_MAX]`，`nutrientFactor ∈ [0.3, 1.0]`）
- [ ] **Step 4: 运行确认通过**（`Tests run: 5, Failures: 0, Errors: 0`）
- [ ] **Step 5: 提交** `git commit -m "feat: add soil water and nutrient balance model"`

### Task 16: 病虫害流行模型

**Files:** Create `agent\eco\DiseaseKind.java`、`EpidemicParameters.java`、`DiseaseState.java`、`PestDiseaseEpidemicModel.java`；Test `PestDiseaseEpidemicModelTest.java`

**Interfaces:**
- `enum DiseaseKind { BOTRYTIS, LATE_BLIGHT, POWDERY_MILDEW, LEAF_MOLD }`
- `class DiseaseState`（不可变 + getter）：`Map<DiseaseKind, Double> severityPct`、`Map<DiseaseKind, Double> inoculumLevel`、`Map<DiseaseKind, Double> latentProgress`、`int infectionEvents`、`double diseaseDamageFactor`、`double pestPopulation`
- `class EpidemicParameters`：各病害适宜温度区间、湿度阈值、叶湿时长阈值、潜育期分钟数、严重度增益；虫害 `rMax=0.08/天`、`K=500`（含 `sourceNote`）
- `class PestDiseaseEpidemicModel`：`DiseaseState initial()`、`DiseaseState advance(DiseaseState current, SimulationState environment, TomatoCropState crop, int minutes)`

- [ ] **Step 1: 写失败测试**（4 项，全部为**分辨力**断言，避免"单一趋势"假通过）

```java
@Test void humidCoolFavoursBotrytisOverDry() {
    assertTrue(run(18.0, 95.0, 480).severity(DiseaseKind.BOTRYTIS) > run(18.0, 45.0, 480).severity(DiseaseKind.BOTRYTIS));
}
@Test void powderyMildewPrefersModerateHumidityUnlikeBotrytis() {
    double moderate = run(22.0, 65.0, 480).severity(DiseaseKind.POWDERY_MILDEW);
    double saturated = run(22.0, 95.0, 480).severity(DiseaseKind.POWDERY_MILDEW);
    assertTrue(moderate >= saturated, "powdery mildew must NOT peak at saturation");
}
@Test void severityAndDamageStayInRange() {
    DiseaseState state = run(20.0, 95.0, 1440);
    for (DiseaseKind kind : DiseaseKind.values()) { assertTrue(state.severity(kind) >= 0.0 && state.severity(kind) <= 100.0); }
    assertTrue(state.getDiseaseDamageFactor() >= 0.0 && state.getDiseaseDamageFactor() <= 1.0);
}
@Test void isDeterministic() { assertEquals(run(20.0, 90.0, 96).getInfectionEvents(), run(20.0, 90.0, 96).getInfectionEvents()); }
```

- [ ] **Step 2–4: 失败 → 实现（spec §22.3 公式）→ 通过**
- [ ] **Step 5: 提交** `feat: add simplified pest and disease epidemic model`

### Task 17: 管理经济模型

**Files:** Create `agent\eco\EconomicsParameters.java`、`EconomicsState.java`、`ManagementEconomicsModel.java`；Test `ManagementEconomicsModelTest.java`

**Interfaces:**
- `class EconomicsParameters`：可配置单价（水/电/CO₂/肥/药/人工、一等品与二等品收购价、二等品折价系数），**每个字段含 `sourceNote`，无来源时默认值标注为"示例参数"**
- `class EconomicsState`：`waterUsedM3`、`energyKWh`、`co2UsedKg`、`fertilizerUsedKg`、`pesticideUsedKg`、`laborHours`、`yieldKg`、`marketableYieldKg`、`costYuan`、`revenueYuan`、`profitYuan`、`waterPerYield`、`energyPerYield`
- `class ManagementEconomicsModel`：`EconomicsState initial()`、`EconomicsState advance(EconomicsState current, ResourceUsage usage, TomatoCropState crop, DiseaseState disease, int minutes)`

- [ ] **Step 1: 写失败测试**（4 项）

```java
@Test void costEqualsSumOfUsageTimesUnitPrice() { /* 断言 costYuan 与手工计算值一致（1e-6） */ }
@Test void revenueSplitsFirstAndSecondGrade() { /* marketableYield < yield 时 revenue 按折价计算 */ }
@Test void profitIsRevenueMinusCost() { assertEquals(state.getRevenueYuan() - state.getCostYuan(), state.getProfitYuan(), 1e-6); }
@Test void perYieldMetricsAreSafeWhenYieldIsZero() { /* 未坐果时 waterPerYield/energyPerYield 返回 0.0，不得出现 NaN/Infinity */ }
```

- [ ] **Step 2–4: 失败 → 实现 → 通过**
- [ ] **Step 5: 提交** `feat: add management economics model with configurable prices`

### Task 18: 权威知识库 ingest 与来源登记

**Files:**
- Create: `database/migrations/V20260923_01__agent_knowledge_source.sql`
- Create: `$SB\src\main\java\com\example\Ece\agent\rag\KnowledgeSource.java`、`KnowledgeSourceRepository.java`、`KnowledgeIngestService.java`、`IngestReport.java`
- Test: `$SB\src\test\java\com\example\Ece\agent\rag\KnowledgeIngestServiceTest.java`

**Interfaces:**
- `class KnowledgeSource`：`sourceCode`、`sourceName`、`sourceType`(A–E)、`authorityLevel`(1–5)、`url`、`licenseNote`、`version`；构造时校验 `sourceName`/`version` 非空，否则抛 `IllegalArgumentException`
- `interface KnowledgeSourceRepository { void save(KnowledgeSource source); KnowledgeSource findByCode(String sourceCode, String version); }`（运行期 `JdbcKnowledgeSourceRepository`，测试用内存实现）
- `class KnowledgeIngestService`：`IngestReport ingest(KnowledgeSource source, List<IngestRecord> records)`；`IngestRecord` 含 `sourceId`、`cropType`、`diseaseName`、`Map<KnowledgeChunk.FieldType,String> fields`
- `class IngestReport`：`int accepted`、`int rejected`、`int chunksWritten`、`boolean embeddingDegraded`、`List<String> rejectedReasons`

- [ ] **Step 1: 写失败测试**（4 项）

```java
@Test void rejectsRecordsWithoutProvenance() {
    assertThrows(IllegalArgumentException.class, () -> new KnowledgeSource("X", "", "B", 3, "http://x", "CC-BY", "v1"));
}
@Test void ingestIsIdempotentByContentHash() {
    IngestReport first = service.ingest(source, records());
    IngestReport second = service.ingest(source, records());
    assertEquals(first.getChunksWritten(), second.getChunksWritten());
    assertEquals(0, second.getRejected());          // 重复块按 content_hash 判重后跳过，不产生新块
}
@Test void reportsDegradedWhenEmbeddingUnavailable() { /* 注入抛异常的 EmbeddingClient → report.isEmbeddingDegraded() 为 true，且块仍写入（BM25-only） */ }
@Test void countsRejectedRecordsWithReasons() { /* 空文本条目计入 rejected 且 reasons 非空 */ }
```

- [ ] **Step 2: 运行确认失败** → `"-Dtest=KnowledgeIngestServiceTest"`
- [ ] **Step 3: 实现**（spec §23.3 六步管线：读取→规范化→切块→出处登记→向量化→幂等写入；无出处直接拒绝）
- [ ] **Step 4: 运行确认通过** + 迁移静态检查（`Select-String -Pattern 'DROP TABLE|ALTER TABLE'` 应为空）
- [ ] **Step 5: 产出语料报告** `docs/eval/corpus-report.md`（来源分布、块数、授权说明）并提交
- [ ] **Step 6: 提交** `feat: add authoritative corpus ingest with provenance registry`

---
## 覆盖核对（spec → task）

| Spec 章节 | 覆盖任务 |
|---|---|
| §4 架构、§5 组件 | T1–T7、T9–T11 |
| §6 存储 schema | T1（4 张表一次建齐） |
| §7 检索规格 | T2（BM25）、T3（向量+RRF+降级）、T8（阈值标定） |
| §8 知识图谱 | T10 |
| §9 编排协议与工具契约 | T5（工具契约与审计）、T6（循环与 SSE） |
| §10 引用与答案格式 | T5（格式化）、T7（守门重写）、T11（前端渲染） |
| §11 守门 6 条 | T7 |
| §12 降级矩阵 | T3（embed 降级）、T6（步数/超时）、T7（披露） |
| §13 测试与评测 | 各 Task 单测 + T8（E-A/E-B）、Flask 自检（T4） |
| §14 接口契约 | T6（chat）、T9（report.html）、T10（graph）；`/api/knowledge/search` 与 `reindex` 在 T5/T3 的实现中以 `KnowledgeSearchTool` 与重建入口暴露，如时间允许再加两个只读 HTTP 包装 |
| §15 Flask 改动 | T4 |
| §16 部署与迁移 | T1（迁移）、T4（依赖）、Global Constraints（命令） |
| §17 里程碑 | T1–T4 = W1-D1~D2；T5–T7 = W1-D3~D4；T8–T11 = W2 |
| §20 作物生长模型 | T12（模型与单测）；T13（作为 P3 档推演内核） |
| §21 性能评测平台 | T13（四档对照与指标矩阵）、T14（前端矩阵/雷达/曲线/堆叠图） |
| §22 番茄生态子系统 | T15（水肥土壤）、T16（病虫害流行）、T17（管理经济）；与 T12 作物、既有微气候共同构成五子系统 |
| §23 权威知识库 ingest | T18（来源登记 + 六步 ingest 管线 + 语料报告） |

## 附录：Task 9–11 的完整测试与关键实现代码

> Task 9–11 的任务段为压缩表述；执行时以本附录代码为准（与任务段的文件名、接口名完全一致）。

### 附 A（Task 9）：报告渲染——把依赖反转成 DTO，测试才可纯函数化

`ReportService` **不依赖** `AgentRunService`，改为接收 `ReportInput`；控制器负责组装。这样单测无需 Spring 上下文。

`ReportInput.java` 字段（含 getter/setter）：`String runCode; String status; String simulatedAt; String strategySummary; List<Map<String,Object>> metrics; List<Map<String,Object>> comparison; List<Map<String,Object>> alerts;`

`ReportServiceTest.java`：

```java
package com.example.Ece.agent.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {
    private final ReportService service = new ReportService();

    private Map<String, Object> metric(String code, String label, Object value, String unit) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("code", code); map.put("label", label); map.put("value", value); map.put("unit", unit);
        return map;
    }

    private ReportInput input() {
        ReportInput input = new ReportInput();
        input.setRunCode("SIM-20260921-001");
        input.setStatus("RUNNING");
        input.setSimulatedAt("2026-09-21 06:00");
        input.setStrategySummary("温度偏高，优先通风降温");
        input.setMetrics(Arrays.asList(metric("temperature", "温度", 31.2, "℃")));
        input.setComparison(Arrays.asList(metric("environmentRisk", "环境风险", 42.0, "%")));
        input.setAlerts(Arrays.asList(metric("HIGH_HUMIDITY", "空气湿度偏高", 0, "")));
        return input;
    }

    @Test
    void rendersPrintableHtmlWithProvenance() {
        String html = service.renderHtml(input());
        assertTrue(html.contains("SIM-20260921-001"));
        assertTrue(html.contains("SIMULATED"));
        assertTrue(html.contains("@media print"));
        assertTrue(html.contains("（推演值）"));
        assertTrue(html.contains("温度偏高，优先通风降温"));
        assertTrue(html.contains("空气湿度偏高"));
    }
}
```

`renderHtml` 实现要点（无新依赖，字符串拼接即可）：
- 头部 `<meta charset="utf-8">`，标题含 `runCode` 与 `simulatedAt`；
- 固定一行红字声明：`数据来源：SIMULATED（场景推演，非实测数据）`；
- 每个数值单元格渲染为 `值 + 单位 + （推演值）`；
- 样式块含 `@media print { .no-print { display:none } body { font-size:12pt } }`；
- 页脚写生成时间与版本号。

### 附 B（Task 10）：图仓储抽象与测试

新增 `GraphRepository` 接口（`List<GraphNode> nodes(); List<GraphEdge> edges();`）与两个实现：`JdbcGraphRepository`（查 `agent_knowledge_node` / `agent_knowledge_edge`，运行期用）、`InMemoryGraphRepository`（测试用）。

`GraphNode` 构造器：`GraphNode(String id, String name, String type, String sourceName, String version)`，当 `sourceName` 为空或 `version` 为空时抛 `IllegalArgumentException`（对应"无出处不得入库"）。
`GraphEdge` 构造器：`GraphEdge(String headId, String tailId, String relation, String sourceName, String version)`，同样的出处校验。
`KnowledgeGraphService.subgraph(String name, int depth)`：BFS 展开 `depth` 跳，节点按 `id` 去重，边两端都必须在结果集中。

`KnowledgeGraphServiceTest.java`：

```java
package com.example.Ece.agent.kg;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeGraphServiceTest {
    private KnowledgeGraphService service() {
        GraphNode crop = new GraphNode("CROP:番茄", "番茄", "CROP", "disease表", "v1");
        GraphNode disease = new GraphNode("DISEASE:早疫病", "早疫病", "DISEASE", "disease表", "v1");
        GraphNode symptom = new GraphNode("SYMPTOM:褐色轮纹斑", "褐色轮纹斑", "SYMPTOM", "设施番茄KG数据集", "v1");
        GraphEdge affects = new GraphEdge(crop.getId(), disease.getId(), "AFFECTS", "disease表", "v1");
        GraphEdge hasSymptom = new GraphEdge(disease.getId(), symptom.getId(), "HAS_SYMPTOM", "设施番茄KG数据集", "v1");
        return new KnowledgeGraphService(new InMemoryGraphRepository(
                Arrays.asList(crop, disease, symptom), Arrays.asList(affects, hasSymptom)));
    }

    @Test
    void returnsTwoHopSubgraph() {
        GraphSubgraph subgraph = service().subgraph("番茄", 2);
        assertEquals(3, subgraph.getNodes().size());
        assertEquals(2, subgraph.getEdges().size());
    }

    @Test
    void limitsDepthToOneHop() {
        assertEquals(2, service().subgraph("番茄", 1).getNodes().size());
    }

    @Test
    void rejectsDataWithoutProvenance() {
        assertThrows(IllegalArgumentException.class,
                () -> new GraphNode("X:y", "y", "DISEASE", "", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new GraphEdge("a", "b", "AFFECTS", "disease表", ""));
    }
}
```

### 附 C（Task 11）：SSE 客户端完整实现

`src/api/agent/chat.ts`（用 `fetch` 而非 `EventSource`，因为需要 POST）：

```ts
export type AgentChatPayload = { question: string; crop?: string; sessionId: string };

export type AgentChatHandlers = {
	onStep?: (event: Record<string, unknown>) => void;
	onCitation?: (event: Record<string, unknown>) => void;
	onDelta?: (text: string) => void;
	onFinal?: (event: Record<string, unknown>) => void;
	onError?: (message: string) => void;
};

export async function streamAgentChat(payload: AgentChatPayload, handlers: AgentChatHandlers): Promise<void> {
	const response = await fetch('/api/ai/agent/chat', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(payload),
	});
	if (!response.ok || !response.body) {
		handlers.onError?.(`请求失败：${response.status}`);
		return;
	}
	const reader = response.body.getReader();
	const decoder = new TextDecoder('utf-8');
	let buffer = '';
	for (;;) {
		const { value, done } = await reader.read();
		if (done) break;
		buffer += decoder.decode(value, { stream: true });
		const frames = buffer.split('\n\n');
		buffer = frames.pop() ?? '';
		for (const frame of frames) {
			const lines = frame.split('\n');
			const nameLine = lines.find((line) => line.startsWith('event:'));
			const dataLine = lines.find((line) => line.startsWith('data:'));
			if (!dataLine) continue;
			const name = nameLine ? nameLine.slice(6).trim() : 'message';
			let data: Record<string, unknown>;
			try {
				data = JSON.parse(dataLine.slice(5).trim());
			} catch {
				continue;
			}
			if (name === 'step') handlers.onStep?.(data);
			else if (name === 'citation') handlers.onCitation?.(data);
			else if (name === 'delta') handlers.onDelta?.(String(data.text ?? ''));
			else if (name === 'final') handlers.onFinal?.(data);
			else if (name === 'error') handlers.onError?.(String(data.message ?? '未知错误'));
		}
	}
}
```

`AgentChat.vue` 结构（Element Plus）：输入区（`el-input` + 作物选择 + 发送按钮）→ 步骤时间线（`el-timeline`，每项显示工具名、状态、耗时、`degraded` 标签）→ 消息区（正文用 `v-html` 渲染带 `[n]` 的文本，`[n]` 转为可点击 span）→ 引用列表（`el-card` 逐条显示病名/字段/片段号/分数/来源标记）→ 顶部提示条（`degraded` 时显示"本次为降级检索：仅关键词匹配"；拒答时显示建议人工咨询）。

## 已知偏离与理由

- **报告导出改为自包含 HTML + 浏览器打印**（spec §5/§14 已同步修订）：OpenPDF 渲染中文需额外字体包，属高风险的隐性工作；HTML 方案零新依赖、中文零配置,演示时"打印/另存为 PDF"同样产出 PDF 文件。
- **`/api/knowledge/search` 与 `/api/knowledge/reindex` 以工具与内部入口形式提供**（未单独暴露 HTTP）：评测（T8）在 JUnit 内直接调用检索器，避免为调试接口增加攻击面；若评委/演示需要，再补两个只读包装。
- **生态五子系统与权威 ingest 追加后，总工作量约 18 人日**（原 13.5 + 新增 4.5），超出 17 人日产能约 1 人日：以"知识图谱可视化降级为 2D 关系图 + 前端两处面板保持最小可用"抵扣；若仍紧张，优先保 T18（语料与出处）与 T13（评测），T10 可再降级为纯接口。
- **取消 3D 场景与生长动画、取消 bge-reranker 重排、知识图谱三维可视化降级为 2D 关系图**（2026-09-22 决策）：需求澄清为"模型用于展示 AI 性能"而非视觉动画，故把 3–4 人日转投生长模型与评测平台；同时为材料生产预留 3.5 人日。

## 进展回填（2026-09-23）

| Task | 状态 | 提交 | 实测/说明 |
|---|---|---|---|
| T1–T7 知识层与编排 | ✅ | `5446d67` 等 | 单测覆盖；**但生产装配缺失，见下** |
| T8 检索评测 | ✅ | `4d6c1c0` | 真实语料 100 条 / 338 块：前缀 Top-1 97.5%、Top-3 100%、中段 Top-3 100%、负样本拒答 100%、口语题未误拒 93.8% |
| T11 对话面板 | ✅ | `58a4c9e` | 见下方偏离说明 |
| T12 / T13 / T18 | ✅ | `bf8d917` / `e667387` / `323162a` | 生长模型、评测矩阵、语料出处 |
| T9 报告导出 / T10 知识图谱 / T14 性能页 | ⬜ 未做 | — | 优先级低于"把已有能力真正接到运行时" |
| **生产装配（新增，计划外）** | ✅ | `58a4c9e` | `KnowledgeIndexService` / `KnowledgeBootstrap` / `KnowledgeController`；此前 ingest 与索引重建只有测试调用，运行时永远拒答 |
| **真实模型联调（新增，计划外）** | ✅ | `58a4c9e` | 见 spec §24：4 个集成问题 + 端到端 DONE 5/5 |

**T11 实现偏离（四处，均已落地）**：

1. **文件与路由**：落在 `views/agentChat/index.vue` + 路由 `/agentChat`（原计划名为 `AgentChat.vue`），并与既有 `agentCenter` 互相加了入口链接；未改动已提交且可用的指挥中心页面结构。
2. **不用 `v-html` 渲染正文**：原计划用 `v-html` 把 `[n]` 转成 span。实际改为把答案切成"文本/引用"两类片段后用 `v-for` 渲染，**彻底消除注入面**，同时保留 `[n]` 可点击联动证据卡。
3. **SSE 用 `fetch` + `ReadableStream` 而非 `EventSource`**：`/ai/agent/chat` 是 **POST**，浏览器原生 `EventSource` 只支持 GET；客户端必须跨 chunk 缓冲并按空行切帧（`\n\n` 与 `\r\n\r\n` 都兼容）。
4. **不把 RRF 融合分展示为"相关度"**：该量按构造只保留排名、丢弃分数量级（见 spec §21.4 与 §3.8），界面只展示可核对的来源（来源表 / ID / 字段 / 片段号）。
