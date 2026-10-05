# M3 模型校准 Implementation Plan

> 后续用户批准将方案扩展为2023/2024校准、2025冻结参数的年份保留评价。当前执行与已完成清单见2026-10-02-m3-multiyear-calibration.md；本文件保留为早期单年设计记录，其日期划分不再是当前执行划分。

**Goal:** 接入2025 CK/广辉201原始观测，保留旧作物模型，新增积温株高版本及三轨展示。

**Architecture:** Python导入器把只读原文件转换为外部JSON观测目录；Java读取并使用项目作物模型运行、校准及保存结果；Vue显示结果并向数字孪生传递有来源的株高回放。

**Tech Stack:** Python/openpyxl（导入）；Java8/Spring Boot/Jackson（模型）；Vue3/TypeScript/ECharts/Three.js（展示）。不新增运行时包。

**Spec:** `docs/superpowers/specs/2026-10-02-m3-model-calibration-design.md`，用户2026-10-02已批准。

## Global Constraints

- 原始ZIP与原始表格只读；标准化JSON和运行记录保存在E盘外部目录。
- 不覆盖旧运行，不改旧默认模型，不将公共数据写入AgentRun。
- 初态04-19；校准04-26/05-10/05-17；保留评估05-28/05-31/06-07/06-13。
- 无未来状态重置；插值不计独立测量；缺测与转换假设明确展示。
- 不新增或运行自动测试；生产构建跳过测试。实际校准和误差计算属于已批准功能。
- 保留项目已有未提交修改；不自动提交仓库。

## Task 1: 观测导入

Files: `scripts/import_horti_m3.py`；外部`normalized/observations.json`、`quality-report.json`、`manifest.json`。

Interface: JSON含`schemaVersion`、`cohort`、`growth`、`environment`、`sources`、`quality`、`assumptions`。

- [ ] 对434份资料逐一SHA256并记录原ZIP校验及资料类型。
- [ ] 读取2025八份GB18030原始表型，保留每条原始字段与行号；只选择CK11–CK16用于首版计算，其他植株仍可查询。
- [ ] 读取sensor1的“对照2后”列，作为区域代表环境；sensor2对照2和sensor3处理1后单独保留为补值参考。原始目录不混合测点，计算时依优先级补缺并标记估计。保留原列名、时间与重复/异常报告。
- [ ] 以原始日期/时间排序，计算30分钟覆盖率，记录长缺测；原始缺失保持null，不默默补全。
- [ ] 用捆绑Python运行导入器；输出记录数、有效候选组与质量状态，不运行测试。

## Task 2: 版本化模型与校准

Files: `agent/m3/M3ObservationService.java`、`M3CalibrationService.java`、`M3Controller.java`；`crop/TomatoCropGrowthModel.java`。

Interfaces: GET `/m3/observations`、GET `/m3/result`、POST `/m3/calibrate`。

- [ ] 原advance方法仍走旧版本；新增`advanceCalibrated(current,environment,minutes,heightCmPerGdd)`，太阳光近似能量`PPFD/4.57` W/m²、株高增量`heightCmPerGdd*GDD增量`，两项与旧路径分离。
- [ ] 保存当前作物模型与参数源码SHA256、初态参数及数据SHA256。初态株高取每株04-19观测；其他作物初态采用显式假设，不将冠层仪器输出自动当作地面积LAI。
- [ ] 原始/修正版本共用lux×0.0185估计PPFD；标注太阳光近似、光谱未知。首版水分胁迫使用固定中性假设，不将未知水分刻度直接映射。
- [ ] 时间精度未知的表型对齐12:00；模型分段积分到该时间。用户已要求一种流程补值：主测点缺失先参考CK测点，再参考其他处理测点，仍缺时用校准期同一时段均值；全部保留来源与估计标记，不使用保留期长势补值或拟合。
- [ ] 只对三次校准测量按无截距最小二乘求伸长系数：`sum(GDD*(H-H0))/sum(GDD^2)`，约束[0,1] cm/GDD，报告边界；保留段不参与拟合。
- [ ] 计算相同有效配对点的MAE、RMSE、Bias，按日期/植株保存残差；初态不计误差。输出是否改善、适用范围及非独立季节说明。
- [ ] 结果原子保存到外部`runs/<version>.json`，最新指针单独更新；GET不重新拟合，不自动发布为默认参数。

## Task 3: 三轨页面与大棚回放

Files: `src/api/m3/index.ts`、`src/views/modelCalibration/index.vue`、`src/router/route.ts`、`src/router/workspaceMenu.ts`、`src/views/digitalTwin/index.vue`。

- [ ] 添加模型校准入口，显示真实数据状态、原始测量散点与两条预测线，区分校准/保留区间。
- [ ] 页面展示误差表、参数版本、来源文件、单位假设、质量报告及样本数。接口失败显示空/错误状态，不生成模拟M3观测。
- [ ] 可按植株或群体均值查看；展示日级环境输入和来源，导出保存结果JSON。
- [ ] 数字孪生通过独立M3回放面板选择原始/修正轨迹和日期，使用结果中的株高；隐藏不适用的原策略指标，叶/果外观标记为示意，设备不映射为M3实测动作。
- [ ] 页面取消挂载时销毁图表、观察器与回放循环。

## Task 4: 构建与收尾

- [ ] Java8 Maven以`-Dmaven.test.skip=true package`构建；Vue输出到独立目录，保留旧产物。
- [ ] 启动更新服务，执行一次真实业务校准；记录实际结果、排除项和来源，不以构建成功代替精度结果。
- [ ] 在浏览器打开对照页及M3大棚回放，检查布局与数据显示；不跑自动测试或性能基准。
- [ ] 更新task_plan/findings/progress和用户说明；报告已完成范围与必要限制。

## 用户追加的算法调研

2026-10-02用户要求检索论文与已有项目，当前优先完成调研。结果见`docs/research/2026-10-02-tomato-simulation-calibration-algorithms.md`。Reduced TOMGRO + UKF/EnKF和vtc节间模型为后续候选；不将它们描述为当前已实现，不自动将候选扩展视为原线性基线计划已完成。
