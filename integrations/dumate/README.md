# DuMate 农业研究最小适配层

## 可直接执行的Skill

本模块现在提供完整Skill目录`skill/agri-research/`，包含自包含Node客户端和报告生成器，零npm依赖。ZIP安装可包含全部脚本；没有Workflow节点也能以本机HTTP方式执行。

```powershell
cd 'E:\domate match\integrations\dumate'
./scripts/start-adapter.ps1
node skill/agri-research/scripts/client.mjs check
node skill/agri-research/scripts/client.mjs prepare --text-file '<用户原文UTF-8文件>' --outdir '<新任务目录>' --seed 20250419 --days 56
node skill/agri-research/scripts/client.mjs analyze --outdir '<任务目录>'
node skill/agri-research/scripts/client.mjs evaluate --outdir '<任务目录>'
node skill/agri-research/scripts/client.mjs report --outdir '<任务目录>'
```

客户端自动发现本机项目`.env`，也支持管理员设置`DUMATE_ENV_FILE`和`DUMATE_ADAPTER_URL`；不会打印或打包令牌。任务状态、调用键、原始JSON和HTML/Markdown均保存到指定任务目录。成功步骤重复执行只读本机存档，失败/中断不自动重放。普通用户演示使用逐步命令，技术验收可用`run`。

正式参赛文本及独立验收见[交付入口](docs/submission/提交清单与使用入口.md)。PDF可用`scripts/build-submission.py`基于独立真实响应重建；打包用`scripts/package-release.mjs`，只包括显式Skill文件及Git认可的模块文件，不复制私有状态目录。

仅复用原有农情解析、环境/知识状态和P0–P6仿真接口；原Spring Boot、Vue、知识库与算法均无改动。对外只有三个带Bearer鉴权的POST能力，不提供用户、文件目录或设备管理接口。

| 能力 | 路径 | 输入 | 主要输出 |
| --- | --- | --- | --- |
| `agri.analyze` | `/v1/agri/analyze` | `text`（1–6000字符） | analysisId、字段/原文/缺项/冲突、风险线索、环境source、知识状态、调用摘要 |
| `agri.evaluate` | `/v1/agri/evaluate` | analysisId、acknowledgeReferenceScenario=true；seed默认20250419；days默认56 | evaluationId、batchId、P0–P6十指标、单位、profile、结果摘要 |
| `agri.report` | `/v1/agri/report` | 同一链路的analysisId、evaluationId | 原值矩阵、相对P2差值、各指标并列领先策略、权衡、人工复核、Markdown和证据 |

风险线索是用户描述抽取，需人工复核，不是病害诊断。评测仍是原接口的固定番茄参考场景，不使用本次解析农情作为初始条件。环境状态不覆盖用户陈述。P3不调用LLM；报告采用结果模板，DuMate负责规划调用及基于工具结果的语言解释。

## 运行

需要Node >=22，原Spring Boot已正常运行于9999（可用环境变量指定完整主机及context path）。无npm依赖安装步骤。

```powershell
cd 'E:\domate match\integrations\dumate'
# PowerShell 7；只在本机生成，不向终端打印。
$env:DUMATE_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:DUMATE_UPSTREAM = 'http://127.0.0.1:9999'
npm start
```

测试/Workflow客户端必须使用与服务进程相同的令牌。在同一环境启动第二终端，或把令牌通过本机密钥管理传入；不要每个终端重新生成。也可以复制`.env.example`为`.env`，替换令牌后运行`node --env-file=.env src/server.mjs`。`.env`与`.state/`不入Git。

默认本层监听127.0.0.1:9998，原上游凭证只能由管理员在`DUMATE_UPSTREAM_TOKEN`设置，客户端凭证不会转发。若上游需其他认证方式，应先适配并测试，不临时透传任意Header。

若需要单独启动现有后端，在原Spring Boot目录设置已有的DB、DeepSeek环境变量，执行`mvn -DskipTests package`后：

```powershell
java '-Dfile.encoding=UTF-8' -jar target/Ece-0.0.1-SNAPSHOT.jar '--server.address=127.0.0.1' '--agent.knowledge.bootstrap=false'
```

数据库启动方式沿用原系统；禁用bootstrap避免本次测试重新灌知识。已有知识状态查询仍可读库、加载内存索引。不要启动第二个占用9999的实例。

## 可直接执行的请求链

以下变量的UUID从实际响应获取；JSON不是伪造的成功响应。所有能力均要求`Idempotency-Key`（8–80位字母/数字/下划线/连字符）。

```powershell
$api = 'http://127.0.0.1:9998'
$run = [guid]::NewGuid().ToString()
$headers = @{ Authorization = "Bearer $env:DUMATE_TOKEN"; 'Idempotency-Key' = "$run-analyze" }
$inputText = '番茄温室面积600平方米，坐果期，温度30℃，空气湿度88%，下部叶片有少量黄斑，尚未确诊。请比较节水、病害风险和收益的权衡，仅做研究与虚拟仿真。'
$a = Invoke-RestMethod "$api/v1/agri/analyze" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{text=$inputText} | ConvertTo-Json)
$headers['Idempotency-Key'] = "$run-evaluate"
$evaluationBody = @{analysisId=$a.data.analysisId; acknowledgeReferenceScenario=$true; seed=20250419; days=56} | ConvertTo-Json
$e = Invoke-RestMethod "$api/v1/agri/evaluate" -Method Post -Headers $headers -ContentType 'application/json' -Body $evaluationBody
$headers['Idempotency-Key'] = "$run-report"
$reportBody = @{analysisId=$a.data.analysisId; evaluationId=$e.data.evaluationId} | ConvertTo-Json
$r = Invoke-RestMethod "$api/v1/agri/report" -Method Post -Headers $headers -ContentType 'application/json' -Body $reportBody
$r.data.markdown
```

原真实上游请求为`POST /ai/agri/plan/situation/parse {text}` → `GET /ai/agri/environment/current` → `GET /ai/knowledge/status` → `POST /eval/runs {batchId:<本层生成UUID>,seed:20250419,days:56}` → `GET /eval/<该UUID>/matrix`。本层不接受任意上游URL、batchId、策略代码或动态工具名。

## 超时、错误与存档

- 默认每次能力调用总预算75s，可设置100–120000ms；包括可选状态调用。解析失败不填默认农情；知识/环境单项失败保留显式warning，整体超时则失败。
- seed 0–2147483647、days 1–56、请求体32KiB、单上游响应8MiB、活动请求2。超出参数400；未授权401；不支持路由404；过大413；非JSON415；忙429。
- 上游认证/HTTP/业务/连接错误分别映射502/503；过期matrix410；解析无原文依据422；总超时504；键参数冲突/在途/中断不确定409。失败返回`ok=false,error.code,message,retryable`，不透出原错误正文或目录。
- 成功返回`ok=true,requestId,data`。同一键与完全相同的JSON参数返回同一存档；字段顺序也属于该请求表示，不会隐式归一化。换参数要换键。
- 上游POST无自动重试。超时或进程中断可能仍在执行，失败/在途键不能当作重算指令。人工核查后才可使用新键；不自动循环重试。
- 本层保存24小时、最多200条记录，满容量503；不自动逐出尚未过期证据。PENDING不会自动过期，管理员停服务、核查并归档后清理。不要多个实例共用`.state`。
- 本地原响应、请求/响应SHA256、时间、参数摘要保存在`.state/<UUID>.json`。通过UUID关联，HTTP不提供读文件接口。管理员需限制目录ACL；保留期清理在启动或新请求时执行，离线停机不会定时删除。
- 本层没有取消同步上游仿真的能力，也没有改动原后端权限。只允许TLS网关对外开放本层三个路径，9999和私有证据目录继续限内网。

## 测试与平台交付

```powershell
npm test
# 使用真实上游，约一次DeepSeek解析+两次已有仿真；不伪造上游成功。
node scripts/smoke.mjs .state/my-e2e
```

烟测检查完整链路、同键复用、独立重算指标逐值一致和证据摘要。失败退出1，保留实际失败信息。契约测试中的fixtures仅用于故障/安全测试，不能当比赛实验结果。

真实测试记录见[进度](progress.md)及[实验报告](docs/evidence/report.md)。平台配置见[DuMate指南](docs/dumate-setup.md)，比赛任务与录屏脚本见[演示素材](docs/demo.md)，契约见[OpenAPI](openapi.json)。账户Workflow/API节点尚未确认，尚未完成DuMate账号安装或发布。

## M3参考数据的隔离验证

最小外部API不暴露M3场景操作。原接口可在管理员本机验证；不要让外部调用者操作任意M3运行。

Windows检出JSON时若CRLF导致摘要不符，运行`node scripts/restore-m3-reference.mjs`从当前Git HEAD读取原始字节，恢复到`.state/m3`并校验现有manifest，不重写摘要。使用`--agent.m3.data-root`指向该副本启动原后端；不要直接指向只读assets后让日志落入已跟踪目录。

M3为历史观测回放；温室设备响应为模型仿真；P3前瞻策略不等于LLM决策；本次未进行实棚验证。
