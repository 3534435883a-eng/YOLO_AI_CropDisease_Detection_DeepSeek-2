# DuMate 农业研究集成实施清单

**Goal:** 在新分支新增独立适配服务，复用现有农业解析和P0–P6仿真，交付可审核PR。

**Architecture:** 独立Node HTTP服务通过固定路径访问原Spring Boot。文件证据仅保存在适配层私有状态目录；报告按存档结果生成，不接收调用方指标。原业务源码、配置、知识库及UI全部保留。

**Tech Stack:** Node >=22，内置HTTP、fetch、crypto、fs、node:test；零第三方依赖。

**Spec:** [接口审计及设计](findings.md)。用户已授权先建分支、列清单后直接实施、测试、提交PR；不另行设置设计审批关卡。

## 全局约束

- 分支 `feat/dumate-ican-minimal`，基线 `57b6d21de9cb0933a6cd68df88c356f378fa2ff2`，不提交或合并main。
- 仅新增 `integrations/dumate/`；不得重写仿真算法、知识库或UI。
- HTTP对外仅3条POST路径，不设通用代理、文件读取、用户管理或设备控制路由。
- 6000字符/32KiB请求、1–56天、非负32位seed、总调用预算75秒、并发2。
- 证据保存24小时、最多200条；失败与PENDING阻止自动重放不确定POST。
- M3历史回放、设备仿真、P3非LLM、无实棚验证必须明确展示。

## 修改文件清单

| 新文件 | 职责 |
| --- | --- |
| `package.json` / `.gitignore` / `.env.example` | 无依赖运行、私有状态隔离、环境配置 |
| `src/config.mjs` | 启动配置与统一错误 |
| `src/upstream.mjs` | 固定接口、超时、响应上限、业务错误映射 |
| `src/store.mjs` | 原子证据存储、摘要、重复调用与保留期 |
| `src/capabilities.mjs` | 参数契约、结果裁剪、结构化报告 |
| `src/server.mjs` | Bearer鉴权、严格路由与有界并发 |
| `test/adapter.test.mjs` | HTTP契约、安全与故障测试 |
| `scripts/smoke.mjs` | 真实解析、重复评测、报告验证 |
| `scripts/restore-m3-reference.mjs` / `scripts/generate-openapi.mjs` | 参考副本字节校验恢复、从实现Schema生成契约 |
| `openapi.json` / `README.md` | 对外契约、运行与真实请求示例 |
| `docs/dumate-setup.md` / `docs/demo.md` | 条件化平台配置、演示及参赛材料 |
| `skill/agri-research/SKILL.md` | 农业研究能力的可复用DuMate技能说明 |
| `task_plan.md` / `findings.md` / `progress.md` | 范围、审计与验证证据 |
| `docs/evidence/` | 无凭证的真实E2E结果及报告 |

恢复脚本是在真实M3测试发现Windows换行导致摘要不一致后加入的必要运行辅助；不改变原始观测或清单。

## 执行步骤

- [x] 拉取GitHub当前仓库并建立分支：`git switch -c feat/dumate-ican-minimal`。
- [x] 逐项阅读四个Controller及相关Service，确认请求、权限、数据与超时；见findings。
- [x] 对比独立HTTP、后端模块、MCP三种接入方案，选独立HTTP；先列文件清单。
- [x] 实现三个能力及固定白名单，增加可信证据关联和风险边界。
- [x] 编写契约/安全/故障测试：`cd integrations/dumate; npm test`。
- [x] 验证原后端：在Spring Boot目录执行 `mvn test` 和 `mvn -DskipTests package`。
- [x] 启动真实后端，关闭知识库bootstrap，使用现有数据库只读状态与真实DeepSeek凭证；不输出凭证。
- [x] 真实烟测：`node scripts/smoke.mjs .state/real-e2e-20261014`；检验相同键缓存、不同键重算及原值一致。
- [x] 完成API契约、配置指南、Skill与录屏脚本。
- [x] 最终代码审查、定向修复并重跑受影响测试。
- [x] 提交功能分支并创建[草稿PR #1](https://github.com/3534435883a-eng/YOLO_AI_CropDisease_Detection_DeepSeek-2/pull/1)，已附到当前聊天；不合并。

## 平台侧待办

当前账号节点能力尚未确认，也未获得可操作的DuMate管理会话。API/Skill材料可先审核；平台安装、从DuMate真实执行、官方分享链接和录屏需要在该账号中完成。不得把本地E2E替代为平台成功证明。

## 独立完善交付（2026-10-14）

用户授权由Codex独立完善项目与参赛材料，并明确不读取或复用DuMate生成的部分。后续以仓库和本适配层为基线，在同一功能分支实施；不操作`.dumate/`和DuMate制作的交付目录。

**设计：** 用自包含Node客户端将三个能力封装为可安装Skill；从本机受控配置读取凭证，任务UUID和每步调用状态持久化，逐步调用并保存原始响应，自动生成带比较范围的报告。增加独立启动/检查入口和按显式文件清单打包脚本。正式方案PDF、简介、讲稿、录屏操作表、答辩问题和审核清单都由Codex生成。平台分享与真实DuMate界面录屏按实际情况记录，不用脚本烟测替代。

新增/修改文件：`skill/agri-research/SKILL.md`、`skill/agri-research/scripts/client.mjs`、`skill/agri-research/scripts/report.mjs`、`skill/agri-research/references/usage.md`、`test/skill-client.test.mjs`、`scripts/start-adapter.ps1`、`scripts/package-release.mjs`、`docs/submission/`、运行与状态文档。原Spring Boot、仿真算法、知识库、UI和现有API保持原样。

- [x] 编写有固定三路径、总超时、输入校验、任务锁和稳定调用键的客户端及可复用Skill。
- [x] 完成客户端故障测试，运行原适配层测试，并通过新客户端做独立真实链路验收。
- [x] 制作正式PDF及其他参赛文本，渲染逐页检查。
- [x] 按清单生成不含凭证的Skill ZIP及完整交付包，检查可移植执行。
- [x] 更新现有草稿PR和用户交付说明，列明平台上仍需用户完成的实际动作。

## 情境驱动作品定位与详细方案（2026-10-15）

用户要求先仔细思考定位并制作详细方案，本轮不实施新业务功能。大棚模型沿用本项目已有模型；不再调查平台模型优先级。

- [x] 审计YOLO、知识检索、类别映射、农业规划、农情任务档案及现有适配层。
- [x] 制作以同一农情任务为核心的动态对话、工具边界和分阶段实施方案。
- [x] 核对方案中已实现/可复用/拟新增的区别，交付独立文档。

保留既有功能分支和PR；仅新增设计文档，原服务与实现不改动。
