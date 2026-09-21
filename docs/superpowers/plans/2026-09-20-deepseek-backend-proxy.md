# DeepSeek 后端代理接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将三个 Vue 页面迁移到安全的 Spring Boot DeepSeek 代理，确保任何 API Key 都不会出现在浏览器或源码中。

**Architecture:** Spring Boot 新增独立的配置、DTO、服务和控制器。浏览器调用同源的 `/api/ai/chat`，Vite 转发为后端的 `/ai/chat`；服务端从环境变量读取 Key，调用 DeepSeek Chat Completions API，并将成功和可展示的失败信息包装为现有 `Result` 格式。

**Tech Stack:** Java 8、Spring Boot 2.3.7、Spring `RestTemplate`、JUnit 5 / Mockito、Vue 3、TypeScript、Axios、Vite 4。

**Spec:** `docs/superpowers/specs/2026-09-20-deepseek-backend-proxy-design.md`

## Global Constraints

- DeepSeek Key 仅可读取 `DEEPSEEK_API_KEY` 环境变量；不得写入 Java、Vue、`.env`、日志或文档。
- 默认上游为 `https://api.deepseek.com`，默认模型为 `deepseek-flash`；仅可用 `DEEPSEEK_BASE_URL` 和 `DEEPSEEK_MODEL` 覆盖。
- 客户端不能指定上游 URL、模型名或认证头。
- 请求仅支持 `system`、`user`、`assistant` 角色，最多 30 条消息、每条最多 12,000 字符；输出上限为 1,200 tokens，固定 `stream: false`。
- 上游连接超时为 10 秒，读取超时为 60 秒；日志不得记录完整消息、认证头或 Key。
- 运行时下载、Maven 缓存和日志继续放在 `E:\agent 农业\.runtime`。
- 此目录不是 Git 仓库；执行时不创建伪提交，只在可用版本控制仓库中提交变更。

---

## File Structure

| 路径 | 责任 |
| --- | --- |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/config/DeepSeekProperties.java` | 读取并保存可覆盖的 DeepSeek 环境配置。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/config/DeepSeekHttpConfig.java` | 创建带明确超时的专用 `RestTemplate`。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/dto/ai/ChatMessage.java` | 表示一条受验证的聊天消息。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/dto/ai/AiChatRequest.java` | 表示浏览器传入的聊天请求。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/dto/ai/AiChatResponse.java` | 表示返回给浏览器的正文和实际模型名。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/service/DeepSeekService.java` | 验证请求、构造上游请求、映射响应及安全错误。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/controller/DeepSeekController.java` | 暴露 `POST /ai/chat` 并使用项目的 `Result` 包装。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/Ece.java` | 注册 `DeepSeekProperties`。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/resources/application.properties` | 引用环境变量并提供非敏感默认值。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/test/java/com/example/Ece/service/DeepSeekServiceTest.java` | 覆盖请求验证、配置缺失、成功和上游失败映射。 |
| `YOLO_AI_CropDisease_Detection_SpringBoot/src/test/java/com/example/Ece/controller/DeepSeekControllerTest.java` | 覆盖公开 HTTP 合约。 |
| `YOLO_AI_CropDisease_Detection_Vue/src/services/ai.ts` | 统一封装前端到 `/api/ai/chat` 的调用。 |
| `YOLO_AI_CropDisease_Detection_Vue/src/views/imgPredict/index.vue` | 将病害预测建议迁移至前端 AI 服务。 |
| `YOLO_AI_CropDisease_Detection_Vue/src/views/smartChat/index.vue` | 将多轮聊天迁移至前端 AI 服务并移除 Key 状态。 |
| `YOLO_AI_CropDisease_Detection_Vue/src/views/detailsEnv/index.vue` | 将环境建议迁移至前端 AI 服务并移除 Key 常量。 |

### Task 1: 定义配置和 AI 请求/响应模型

**Files:**
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/config/DeepSeekProperties.java`
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/config/DeepSeekHttpConfig.java`
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/dto/ai/ChatMessage.java`
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/dto/ai/AiChatRequest.java`
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/dto/ai/AiChatResponse.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/Ece.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/resources/application.properties`
- Test: `YOLO_AI_CropDisease_Detection_SpringBoot/src/test/java/com/example/Ece/config/DeepSeekPropertiesTest.java`

**Interfaces:**
- Consumes: `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `DEEPSEEK_MODEL` from the process environment.
- Produces: `DeepSeekProperties`, `RestTemplate deepSeekRestTemplate()`, `ChatMessage`, `AiChatRequest`, and `AiChatResponse` for the service task.

- [ ] **Step 1: Write the failing configuration test**

```java
@Test
void defaultsAreSafeWhenNoEnvironmentOverridesExist() {
    DeepSeekProperties properties = new DeepSeekProperties();
    assertEquals("https://api.deepseek.com", properties.getBaseUrl());
    assertEquals("deepseek-flash", properties.getModel());
    assertFalse(properties.hasApiKey());
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `YOLO_AI_CropDisease_Detection_SpringBoot`:

```powershell
$env:JAVA_HOME = 'E:\agent 农业\.runtime\java\temurin8'
$env:MAVEN_USER_HOME = 'E:\agent 农业\.runtime\maven'
& 'E:\agent 农业\.runtime\maven\apache-maven-3.9.16\bin\mvn.cmd' -Dmaven.repo.local='E:\agent 农业\.runtime\maven\repository' -Dtest=DeepSeekPropertiesTest test
```

Expected: compilation failure because `DeepSeekProperties` does not yet exist.

- [ ] **Step 3: Implement the configuration and DTOs**

Create a `@ConfigurationProperties(prefix = "deepseek")` bean with these exact defaults and a blank default API Key:

```java
private String apiKey = "";
private String baseUrl = "https://api.deepseek.com";
private String model = "deepseek-flash";
public boolean hasApiKey() { return apiKey != null && !apiKey.trim().isEmpty(); }
```

Register it with `@EnableConfigurationProperties(DeepSeekProperties.class)` on `Ece`. Add `deepseek.api-key=${DEEPSEEK_API_KEY:}`, `deepseek.base-url=${DEEPSEEK_BASE_URL:https://api.deepseek.com}`, and `deepseek.model=${DEEPSEEK_MODEL:deepseek-flash}` to `application.properties`.

Define DTO fields exactly as follows:

```java
public class ChatMessage { private String role; private String content; }
public class AiChatRequest { private List<ChatMessage> messages; }
public class AiChatResponse {
    private final String content;
    private final String model;
}
```

Provide JavaBean getters/setters for request DTOs and getters plus a constructor for `AiChatResponse`. Configure the dedicated `RestTemplate` with `SimpleClientHttpRequestFactory`, a 10,000 ms connect timeout and a 60,000 ms read timeout.

- [ ] **Step 4: Run the configuration test to verify it passes**

Run the command from Step 2.

Expected: `DeepSeekPropertiesTest` passes and no secret is printed.

- [ ] **Step 5: Check the configuration file for accidental secrets**

```powershell
rg -n -i "sk-[a-z0-9]|deepseek.*api.*key\s*=\s*[^$]" src/main resources
```

Expected: only the environment placeholder appears; no API Key value is present.

### Task 2: 实现上游调用、校验和安全错误映射

**Files:**
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/service/DeepSeekException.java`
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/service/DeepSeekService.java`
- Test: `YOLO_AI_CropDisease_Detection_SpringBoot/src/test/java/com/example/Ece/service/DeepSeekServiceTest.java`

**Interfaces:**
- Consumes: `DeepSeekProperties`, `RestTemplate deepSeekRestTemplate`, `AiChatRequest.messages`.
- Produces: `AiChatResponse chat(List<ChatMessage> messages)` or `DeepSeekException` with a safe error code and Chinese message.

- [ ] **Step 1: Write failing service tests**

```java
@Test
void rejectsMissingApiKeyBeforeCallingNetwork() {
    DeepSeekProperties properties = propertiesWithKey("");
    RestTemplate restTemplate = mock(RestTemplate.class);
    DeepSeekService service = new DeepSeekService(properties, restTemplate);

    DeepSeekException error = assertThrows(DeepSeekException.class,
        () -> service.chat(singleUserMessage("你好")));

    assertEquals("AI_NOT_CONFIGURED", error.getCode());
    verifyNoInteractions(restTemplate);
}

@Test
void mapsSuccessfulUpstreamResponseToContentAndModel() {
    when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Map.class)))
        .thenReturn(new ResponseEntity<>(response("病害防治建议", "deepseek-flash"), HttpStatus.OK));

    AiChatResponse result = serviceWithKey().chat(singleUserMessage("玉米锈病如何处理？"));

    assertEquals("病害防治建议", result.getContent());
    assertEquals("deepseek-flash", result.getModel());
}
```

Add tests for unsupported role, more than 30 messages, content over 12,000 characters, upstream 401, upstream 429 and `ResourceAccessException`. Assert their codes are respectively `AI_INVALID_REQUEST`, `AI_UPSTREAM_AUTH`, `AI_RATE_LIMIT`, and `AI_NETWORK_ERROR`.

- [ ] **Step 2: Run the service test to verify it fails**

```powershell
& 'E:\agent 农业\.runtime\maven\apache-maven-3.9.16\bin\mvn.cmd' -Dmaven.repo.local='E:\agent 农业\.runtime\maven\repository' -Dtest=DeepSeekServiceTest test
```

Expected: compilation failure because `DeepSeekService` and `DeepSeekException` do not yet exist.

- [ ] **Step 3: Implement the minimal safe service**

Implement these public signatures:

```java
public class DeepSeekException extends RuntimeException {
    public DeepSeekException(String code, String message) { ... }
    public String getCode() { ... }
}

public AiChatResponse chat(List<ChatMessage> messages) { ... }
```

Validate the messages before creating `HttpEntity`. Build the exact upstream body:

```java
Map<String, Object> body = new LinkedHashMap<>();
body.put("model", properties.getModel());
body.put("messages", messages);
body.put("stream", false);
body.put("max_tokens", 1200);
```

Set only `Content-Type: application/json` and `Authorization: Bearer <server-side key>` on the upstream request. Call `properties.getBaseUrl() + "/chat/completions"`. Extract `choices[0].message.content` and response `model` defensively; an empty or malformed success body raises `AI_UPSTREAM_ERROR` with `AI 服务返回格式异常，请稍后重试`. Map HTTP 401/403 to `AI_UPSTREAM_AUTH` / `AI 服务认证失败，请检查服务器配置`; 429 to `AI_RATE_LIMIT` / `AI 服务请求过于频繁，请稍后重试`; all other HTTP errors to `AI_UPSTREAM_ERROR` / `AI 服务暂时不可用，请稍后重试`; and network or timeout errors to `AI_NETWORK_ERROR` / `AI 服务连接超时，请稍后重试`. Generate one UUID request ID and measure elapsed milliseconds for every call; log only `requestId`, outcome code and elapsed milliseconds using the application logger. Do not log upstream request bodies, headers, exception response bodies or API Keys.

- [ ] **Step 4: Run the service tests to verify they pass**

Run the command from Step 2.

Expected: all tests pass, and mocked requests contain the configured model plus the server-side authorization header.

- [ ] **Step 5: Run the complete backend test suite**

```powershell
& 'E:\agent 农业\.runtime\maven\apache-maven-3.9.16\bin\mvn.cmd' -Dmaven.repo.local='E:\agent 农业\.runtime\maven\repository' test
```

Expected: existing context test and new service tests pass.

### Task 3: 暴露 HTTP 合约并测试控制器

**Files:**
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/controller/DeepSeekController.java`
- Test: `YOLO_AI_CropDisease_Detection_SpringBoot/src/test/java/com/example/Ece/controller/DeepSeekControllerTest.java`

**Interfaces:**
- Consumes: `POST /ai/chat` JSON body matching `AiChatRequest`.
- Produces: `Result<AiChatResponse>` with success code `"0"`, or `Result.error` using service error codes.

- [ ] **Step 1: Write the failing MVC tests**

```java
@Test
void returnsProjectSuccessEnvelope() throws Exception {
    when(deepSeekService.chat(anyList()))
        .thenReturn(new AiChatResponse("环境湿度应降低", "deepseek-flash"));

    mockMvc.perform(post("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"messages\":[{\"role\":\"user\",\"content\":\"给出温室建议\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.content").value("环境湿度应降低"));
}
```

Add a test that makes the mock service throw `new DeepSeekException("AI_NOT_CONFIGURED", "AI 服务尚未配置")`; assert HTTP 200 with that exact `code` and `msg` and no `data` field containing a Key.

- [ ] **Step 2: Run the controller test to verify it fails**

```powershell
& 'E:\agent 农业\.runtime\maven\apache-maven-3.9.16\bin\mvn.cmd' -Dmaven.repo.local='E:\agent 农业\.runtime\maven\repository' -Dtest=DeepSeekControllerTest test
```

Expected: compilation failure because `DeepSeekController` does not yet exist.

- [ ] **Step 3: Implement the controller**

Create `@RestController` and `@RequestMapping("/ai")` controller with this method:

```java
@PostMapping("/chat")
public Result<?> chat(@RequestBody AiChatRequest request) {
    try {
        return Result.success(deepSeekService.chat(request == null ? null : request.getMessages()));
    } catch (DeepSeekException error) {
        return Result.error(error.getCode(), error.getMessage());
    }
}
```

The service owns all validation so the HTTP contract returns the same safe `Result` shape for malformed JSON content, missing messages, and upstream failures.

- [ ] **Step 4: Run controller and backend tests to verify they pass**

```powershell
& 'E:\agent 农业\.runtime\maven\apache-maven-3.9.16\bin\mvn.cmd' -Dmaven.repo.local='E:\agent 农业\.runtime\maven\repository' test
```

Expected: all unit and MVC tests pass.

- [ ] **Step 5: Verify the Key-free error path against the running backend**

Do not set a Key for this check. Send:

```powershell
$body = @{ messages = @(@{ role = 'user'; content = '测试 AI 配置状态' }) } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:9999/ai/chat' -ContentType 'application/json' -Body $body
```

Expected: a JSON response with `code` equal to `AI_NOT_CONFIGURED`; the response must not include an API Key.

### Task 4: 迁移三个 Vue 页面到同源 AI 服务

**Files:**
- Create: `YOLO_AI_CropDisease_Detection_Vue/src/services/ai.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/imgPredict/index.vue:262-321`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/smartChat/index.vue:76-129`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/detailsEnv/index.vue:70-103`

**Interfaces:**
- Consumes: `POST /api/ai/chat` with `{ messages: Array<{role, content}> }`.
- Produces: `requestAiChat(messages): Promise<{ content: string; model: string }>` for all three pages.

- [ ] **Step 1: Write the shared front-end service**

Create `src/services/ai.ts` with no Key, model or upstream URL:

```ts
import axios from 'axios';

export type AiMessage = { role: 'system' | 'user' | 'assistant'; content: string };
type AiChatPayload = { content: string; model: string };
type Result<T> = { code: string; msg: string; data: T };

export async function requestAiChat(messages: AiMessage[]): Promise<AiChatPayload> {
  const response = await axios.post<Result<AiChatPayload>>('/api/ai/chat', { messages });
  if (response.data.code !== '0') throw new Error(response.data.msg || 'AI 服务暂时不可用');
  return response.data.data;
}
```

- [ ] **Step 2: Migrate the disease prediction page**

Import `requestAiChat`. Preserve the existing generated disease-analysis `prompt`, replace the direct `axios.post('https://api.deepseek.com/...')` block with:

```ts
const result = await requestAiChat([{ role: 'user', content: prompt }]);
state.aiSuggestion = result.content;
```

Remove the local `apiKey` declaration and the direct `Authorization` header. Keep the existing loading flag, success message and user-facing failure message.

- [ ] **Step 3: Migrate chat and environment pages**

In `smartChat/index.vue`, remove `apiKey` from component state, import `requestAiChat`, and replace the direct call with:

```ts
const result = await requestAiChat(this.messages.map(message => ({
  role: message.role,
  content: message.content,
})));
this.messages.push({ role: 'assistant', content: result.content });
```

In `detailsEnv/index.vue`, remove the `apiKey` constant, retain its system and sensor messages, and replace the direct call with:

```ts
const result = await requestAiChat(messages);
suggestions.value = result.content.split('\n');
```

Ensure each page surfaces `error.message` when it is an `Error`, otherwise retains its existing generic message.

- [ ] **Step 4: Verify the source contains no browser-side Key or direct DeepSeek URL**

```powershell
rg -n "api\.deepseek\.com|Authorization.*Bearer|请替换为您的DeepSeekAPI密钥|apiKey" src/views/imgPredict/index.vue src/views/smartChat/index.vue src/views/detailsEnv/index.vue src/services/ai.ts
```

Expected: no matches.

- [ ] **Step 5: Build the Vue application**

```powershell
$node = 'C:\Users\nom\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
& $node 'node_modules\vite\bin\vite.js' build
```

Expected: Vite emits `dist/index.html` and completes successfully. Existing Sass deprecation warnings may be printed but must not be treated as build failure.

### Task 5: 打包、部署与有 Key 的端到端验证

**Files:**
- Modify: deployment outputs only: `YOLO_AI_CropDisease_Detection_SpringBoot/target/Ece-0.0.1-SNAPSHOT.jar` and `YOLO_AI_CropDisease_Detection_Vue/dist/`
- Read: `E:\agent 农业\.runtime\logs\spring-boot.out.log`, `E:\agent 农业\.runtime\logs\vue-preview.out.log`

**Interfaces:**
- Consumes: valid `DEEPSEEK_API_KEY` configured locally by the user; running MySQL, Flask and Vue preview services.
- Produces: locally deployed AI functionality on `http://127.0.0.1:8100/` without exposing the Key.

- [ ] **Step 1: Configure the Key locally without sharing it in chat**

The user enters the real value privately in a local PowerShell window:

```powershell
[Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', 'paste-your-real-key-here', 'User')
```

Open a new PowerShell process before starting Spring Boot so the environment variable is inherited. Do not paste the actual Key into source files, command transcripts, logs, or agent messages.

- [ ] **Step 2: Build the Spring Boot executable**

```powershell
$env:JAVA_HOME = 'E:\agent 农业\.runtime\java\temurin8'
$env:MAVEN_USER_HOME = 'E:\agent 农业\.runtime\maven'
& 'E:\agent 农业\.runtime\maven\apache-maven-3.9.16\bin\mvn.cmd' -Dmaven.repo.local='E:\agent 农业\.runtime\maven\repository' -DskipTests package
```

Expected: `target/Ece-0.0.1-SNAPSHOT.jar` exists and Maven reports `BUILD SUCCESS`.

- [ ] **Step 3: Restart the Spring Boot process and call the proxy directly**

Start the newly built JAR using the existing local deployment procedure, then run:

```powershell
$body = @{ messages = @(@{ role = 'user'; content = '请用一句话说明玉米锈病的常见防治原则。' }) } | ConvertTo-Json -Depth 4
$response = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:9999/ai/chat' -ContentType 'application/json' -Body $body
if ($response.code -ne '0' -or [string]::IsNullOrWhiteSpace($response.data.content)) { throw 'DeepSeek proxy verification failed' }
```

Expected: a successful non-empty model answer. Never print the Key or request headers.

- [ ] **Step 4: Verify the front-end proxy and all three UI entry points**

First verify the proxy route:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8100/api/ai/chat' -ContentType 'application/json' -Body $body
```

Then open `http://127.0.0.1:8100/` and trigger: a completed image prediction's AI suggestion, one message in Smart Chat, and the environment recommendation button. Each must display an answer or an intentionally mapped service error. Browser network requests must target only `/api/ai/chat`; inspect request headers to confirm no browser request contains `Authorization`.

- [ ] **Step 5: Inspect deployment logs for secret exposure**

```powershell
rg -n -i "deepseek.*(authorization|bearer|api.?key)|sk-[a-z0-9]" 'E:\agent 农业\.runtime\logs'
```

Expected: no matches containing a Key, bearer token or authorization header.
