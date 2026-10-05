# M3在线纠偏与风险预警 Implementation Plan

**Goal:** 按时序消费M3，显示状态纠偏、多因素生长参考、环境及持续暴露预警，并同步同一运行的大棚。

**Architecture:** Java保存有游标的独立在线运行；纯标量EKF与风险规则逐半小时推进。Vue复用一个运行组件，页面展示图表、大棚以紧凑面板展示当前状态；API只返回已消费数据。

**Tech Stack:** Java8/Jackson/Spring Boot、Vue3/ECharts、现有Three.js，无新增依赖。

**Spec:** docs/superpowers/specs/2026-10-02-m3-live-assimilation-design.md，用户已认可在线状态纠偏与多因素/预警方向。

## 约束

- 原ZIP、已保存跨年结果、AgentRun与默认规则模型保留。
- 每个游标按真实半小时推进；批量推进保留全部株高更新和预警事件；不使用未来株高。
- 在线预测误差以新观测到达之前的预测计算；更新后误差单列。
- 土壤VWC与LAI/LDW口径未核实，保留原值及来源，不自动同化或伪称水分校准。
- RUE生长结果明确为参考；果实饱满度、风速、叶果3D效果不视为实测。
- 不加/运行测试、不提交；生产构建使用-Dmaven.test.skip=true，业务回放属于用户授权功能。

## Task 1：状态内核与环境预警

- [x] 新建OnlineInternodeFilter：initialThermalAge初始化，预测a+=GDD，P按dt增加，dh/da解析求导；按EKF创新、Joseph方差更新，保存前后值与权重。
- [x] 新建M3LiveRiskEngine：逐步累计高温/低温/高湿/VPD暴露；每条预警保存rule、level、数值、阈值、持续分钟、建议、数据来源。
- [x] 为派生VPD/露点、估计输入、未标定指标提供来源说明；不做发病概率。

## Task 2：顺序数据运行API

- [x] 新建M3LiveService与M3LiveController：start、step、get、delete。expectedCursor保护重复推进，1/12/48合法步数、最多8运行、30分钟TTL。
- [x] 初始化仅使用首日株高；冻结历史节间参数。每株保留固定参数基线与在线状态，按M3时序更新，保存已到达测量与来源。
- [x] 使用已有advanceCalibrated推进光温CO₂/假设水分的RUE参考；返回未同化LAI/LDW及单位说明。
- [x] 输出新增帧、事件与累计逐株prior/post/open-loop误差；GET只返回过去帧，不输出未来观测。

## Task 3：页面与大棚

- [x] 新建m3/live.ts接口与M3LiveWorkbench.vue：开始/暂停/单步/速度/年份，逐步绘制固定参数与在线纠偏两线、原测散点。
- [x] 展示最近纠偏、预警列表、24h暴露、生长参考、原始叶参考及数据质量；导出当前已消费结果。
- [x] 模型校准页以在线工作区为主，历史跨年结果可折叠查看。
- [x] 大棚以同一runId打开紧凑工作区，模型株高/参考LAI驱动场景；离开暂停播放，交接不重建运行。

## Task 4：构建与交付

- [x] Java/Vue生产构建，跳过测试。
- [x] 实际业务顺序回放2025记录前后误差、更新数量、预警事件；不根据结果回调超参数。
- [x] 更新本地服务并查看页面及大棚，保存截图、边界说明与跟踪记录。

## 实施结果

2025实际顺序回放2640个半小时时段、42次逐株更新。固定参数RMSE22.77cm；在线使用过去观测后的下一次预测RMSE10.42cm；当次更新后拟合偏差RMSE3.57cm单列。LAI/干重、土壤胁迫、果实和风尚未完成观测校准。结果与截图见docs/research/2026-10-02-m3-online-growth-risk-results.md。
