# DeepSeek 后端代理接入设计

## 目标

为病害识别建议、智能聊天、温室环境建议三个现有页面接入 DeepSeek。API Key 仅保存在运行机器的环境变量中，绝不进入 Vue 源码、构建产物、日志或 Git 历史。

## 范围与边界

- 包含三个页面到统一 AI 接口的迁移。
- 包含 Spring Boot 调用 DeepSeek Chat Completions API 的配置、校验、超时与安全错误处理。
- 保持现有非流式回复体验，不增加流式传输、对话持久化、用户级额度或自动执行控制设备的能力。

## 架构

Vue 三个页面只向同源代理路径 `POST /api/ai/chat` 发送聊天消息数组。Vite 将该请求代理给 Spring Boot；Spring Boot 的 `DeepSeekController` 校验请求后调用 `DeepSeekService`；服务读取运行环境配置，向 `https://api.deepseek.com/chat/completions` 发起 HTTPS 请求，并将模型正文以项目统一的 `Result` 结构返回。

Spring Boot 使用现有 `spring-boot-starter-web` 提供的 `RestTemplate`，不引入新的第三方依赖或下载。代码分为配置对象、调用服务和 HTTP 控制器，页面不会知道上游 URL、模型名或认证方式。

## 运行配置

以下配置只从环境变量或可被部署环境覆盖的属性读取：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 无 | 必填的 DeepSeek API Key |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 上游 API 地址 |
| `DEEPSEEK_MODEL` | `deepseek-flash` | 发送给 DeepSeek 的模型标识 |

缺少 `DEEPSEEK_API_KEY` 时，AI 接口不请求上游，而是返回明确的中文配置错误。前端不会提供或传递 API Key。模型名只由后端配置控制，客户端请求不能覆盖它。

## 接口约定

`POST /ai/chat` 接收：

```json
{
  "messages": [
    { "role": "system", "content": "可选的场景提示" },
    { "role": "user", "content": "用户或检测数据" }
  ]
}
```

接口仅允许 `system`、`user`、`assistant` 三类角色；最多 30 条消息，每条文本最多 12,000 个字符。服务固定请求非流式回复、最大输出 1,200 tokens，并返回：

```json
{
  "code": "0",
  "msg": "成功",
  "data": { "content": "模型回答", "model": "实际模型名" }
}
```

## 三个页面的行为

- `imgPredict/index.vue` 保留已生成的病害诊断提示词，改为调用 `/api/ai/chat` 并显示 `data.content`。
- `smartChat/index.vue` 继续携带当前对话上下文，改为调用 `/api/ai/chat`；页面内的 `apiKey` 状态删除。
- `detailsEnv/index.vue` 保留温室传感器提示词与按行展示逻辑，改为调用 `/api/ai/chat`；页面内的 `apiKey` 常量删除。

## 失败处理与安全性

后端连接超时为 10 秒、读取超时为 60 秒。缺少 Key、上游认证失败、限流、上游服务错误和网络超时都被映射为可供前端展示的中文信息，不返回认证头、Key 或上游错误正文。日志只记录请求标识、状态与耗时，不记录 API Key 或完整消息内容。

## 验证标准

1. Spring Boot 与 Vue 生产构建均成功完成。
2. 未设置 Key 时，`POST /ai/chat` 返回可理解的配置错误，且服务继续正常运行。
3. 设置有效 Key 后，使用单轮请求验证后端能获得并返回 DeepSeek 回答。
4. 在三个页面分别触发 AI 功能，确认浏览器网络请求只指向本机 `/api/ai/chat`，不包含 `Authorization` 头，也不直接访问 DeepSeek 域名。
