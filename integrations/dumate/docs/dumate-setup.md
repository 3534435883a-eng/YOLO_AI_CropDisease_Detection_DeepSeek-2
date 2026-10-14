# DuMate 配置与验收

状态：本地真实API链路已验证；当前用户账号的Workflow第三方API节点权限尚未确认。以下是配置指南，不是已发布的DuMate作品，也不是官方可导入的Workflow DSL。

## 已完成的直接执行方案

现在`skill/agri-research/`内含可执行Node脚本及报告生成器，可以安装完整ZIP后由DuMate逐步执行，不要求预先注册三个平台工具。脚本固定调用三个本层路径，自动读取本机受控配置，持久化任务UUID和步骤状态，并保存真实响应。

优先API节点的方案仍可选择，但用户提供的比赛介绍并未规定必须采用Workflow/MCP。Skill为鼓励项。原生Skill加本机HTTP脚本是本轮已验收的技术执行方式，安装启用状态、真实DuMate任务运行和分享需要由实机界面确认。

操作见Skill的`references/usage.md`以及`docs/submission/提交清单与使用入口.md`。安装ZIP才能包含脚本；只安装最初的文字SKILL.md不会获得脚本。

## 先确认节点与网络

在比赛专属DuMate账号中查看Workflow是否可增加第三方API/HTTP节点，是否能设置Bearer密钥、JSON体、稳定调用键，以及至少75秒调用超时。若支持，优先按下表封装成Skill。账号权限、节点名称与变量选择器以实机为准；不要因为[DuMate对外OpenAPI](https://cloud.baidu.com/doc/Dumate/s/ymu8cfo0x)存在，就认定这个账号支持HTTP Workflow节点。

配置管理员提供的适配层基础URL及密钥。云端节点不能访问本机127.0.0.1；用受控HTTPS网关只发布9998的三条路径。不要把原9999直接映射公网。保持网关到后端为内网，限制请求体32KiB，代理读取超时高于本层75秒，保留Authorization和Idempotency-Key。共享作品中不显示令牌。

`DUMATE_UPSTREAM`支持部署时指定context path；API节点只指向适配层。没有公网网关时，可以用同机本地HTTP执行工具测试，记录为本地执行，不能声称评审链接已可访问。

## 三节点配置

三个节点均为POST、Content-Type=application/json、Authorization=Bearer（从平台秘密变量绑定）。使用本项目`openapi.json`作为契约；能导入OpenAPI时，先在测试任务中核对导入结果。不能导入时逐项手动设置。

| 节点/Skill能力 | 地址后缀 | JSON体的变量映射 | 结果映射 |
| --- | --- | --- | --- |
| agri.analyze | `/v1/agri/analyze` | text ← 用户原始农业描述，最多6000字符 | analysisId ← `data.analysisId`；草稿与riskClues/source/warnings全部保留 |
| agri.evaluate | `/v1/agri/evaluate` | analysisId ← 上一步；acknowledgeReferenceScenario=true；seed=20250419；days=56 | evaluationId ← `data.evaluationId`；outcomes/units/profile/trace |
| agri.report | `/v1/agri/report` | analysisId ← 分析节点；evaluationId ← 评测节点 | Markdown ← `data.markdown`；humanReview/boundaries/evidence |

节点Idempotency-Key：使用同一任务的持久UUID加`-analyze`、`-evaluate`、`-report`。UUID由任务开始时生成并保存，节点重试必须复用；重新完整实验才生成新UUID。不同节点不能复用同一个键。不要用每次节点执行的当前时间自动改变键。

可在节点JSON编辑器填写以下逻辑模板；其中尖括号表示从平台变量选择器绑定的值，**不是**官方变量插值语法：

```json
{"text":"<用户原始农业描述>"}
```

```json
{"analysisId":"<分析节点data.analysisId>","acknowledgeReferenceScenario":true,"seed":20250419,"days":56}
```

```json
{"analysisId":"<分析节点data.analysisId>","evaluationId":"<评测节点data.evaluationId>"}
```

每个HTTP节点先判断HTTP成功且`ok=true`，才进入下一步。`ok=false`则显示error.code及说明并停止，不由大模型补造结果。连接断开、504、RESULT_INDETERMINATE均不可自动换键重发。429 ADAPTER_BUSY在确认没有上游执行后可同键限次重试，不能无限循环。

## Skill与语言解释

`../skill/agri-research/SKILL.md`是随交付提供的技能。官方[技能管理文档](https://cloud.baidu.com/doc/Dumate/s/5mmydgic3)说明可安装URL、zip/md；API节点方案需要在账号中配置调用能力，原生Skill方案使用自带脚本即可。本轮没有通过界面执行安装。仅安装文字说明不会自动部署API或建立节点。

给结束处的语言解释节点以下规则：

> 你只使用真实工具返回的字段、矩阵和trace解释结果。必须说明评测是固定番茄参考场景，没有注入用户农情。展示P0–P3业务对照与P4–P6离线消融；结合用水、能耗、病害压力、严重度、产量和利润解释权衡，亏损不能说成收益增长。引用analysisId、evaluationId、batchId和seed/days。用户陈述不是传感器观测，环境source必须原样披露；知识状态不是检索证据。M3是历史回放，设备响应是模型仿真，P3不是LLM决策。保留人工复核事项；工具失败时停止，不编造数值或实棚验证。

## 若账号没有所需API节点

先记录账号版本、节点列表和受限原因，再检查该账号是否支持外部MCP连接及其传输方式。若支持，桥接工具必须继续只调用本层三条POST路径，三个工具分别映射agri.analyze/evaluate/report的相同JSON Schema；禁止桥直接注册AgentChat、M3动态操作、文件或设备工具。

工具schema读取`openapi.json`，桥凭证用环境变量/平台secret；stdio或Streamable HTTP必须以账号实际支持为准。未确认传输、安装入口和令牌来源前，本轮不创建未经验证的MCP服务。也不要把一个自定义JSON文件当作官方MCP连接配置。

## 账号内验收与分享

1. 提交[演示任务](demo.md)中的完整用户需求，截取自主规划与实际三工具调用。
2. 确认解析原文/缺项、7策略矩阵、profile边界、trace及人工复核全部可见。
3. 同任务复试节点：调用键稳定，eval批次不变；新任务同seed/days指标相同但batchId不同。
4. 无凭证请求被401拒绝；days=57被400拒绝；文件/用户/设备路径404；不要在作品截图中展示secret。
5. 记录平台账号版本、配置时间、运行结果、真实分享链接与执行录屏。按比赛介绍在DuMate官方作品广场/对话分享处保存链接；不伪造地址。

本地E2E记录不等于上述账号内验收；截至本次交付，平台安装、实际DuMate执行、分享和录屏仍待完成。
