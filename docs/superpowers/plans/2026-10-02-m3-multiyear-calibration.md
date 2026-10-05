# M3 三年校准与大棚对照 Implementation Plan

**Goal:** 导入三年原始表格，以2023/2024可比CK广辉201批次校准自主株高机制，将2025作为冻结参数的年份保留评价，提供真实对照页面和三维回放。

**Architecture:** 独立Python导入器生成schema2三年观测文件；Java服务做共同环境驱动、双参数节间机制拟合、线性积温基线比较与结果版本化；Vue查询结果并回放同一结果至已有Three.js大棚。仍为单一用户工作流程。

**Tech Stack:** Python/openpyxl、Java8/Jackson/Spring Boot、Vue3/ECharts/Three.js，复用现有依赖。

**Spec:** 用户2026-10-02认可跨年校准计划；依据docs/research/2026-10-02-tomato-simulation-calibration-algorithms.md中的覆盖核对与候选路线。

## Global Constraints

- 保留现有未提交改动、原ZIP、原表格及旧AgentRun。
- 所有年份原株高导入；2023重复原文件只计一次；插值数据不算实测。
- 首次校准CK广辉201：2023选择714/715/716（README明确processCK），2024/2025选择CK11–CK16；2023传感器对应须用原表环境均值核对，记录依据。
- 2023/2024除首日外用于拟合；2025仅首日初始化，后续所有株高保留评价，参数搜索/模型选择/均值补值不读取2025长势。
- 缺测按同CK测点、其他区域参考测点、训练年份同时间均值顺序补齐；标记估计。主环境驱动统一，跨年不拼成连续植株。
- PPFD光照换算与基温仍是假设。观测环境作为外部驱动，不能宣称设备控制与果实等输出已校准。
- 不增加或运行测试；生产构建跳过测试。业务导入、拟合、保留误差计算属于用户授权功能。
- 主执行内联，不为执行选项再次请求许可；不自动提交。

## Task 1: 三年原始数据导入

**Files:** scripts/import_horti_m3_multiyear.py；外部normalized/multiyear-observations.json及multiyear-manifest.json。

**Interfaces:** schemaVersion=2；seasons=[{year,role,startDate,endDate,cohort,growth,environment,quality}]，quality保存完整株高覆盖；cohort记录选择与mappingEvidence。

- [x] 解析grow_index与growth_index、2023表中日期及2024/25文件日期，保留原列名/行/编码/来源SHA256。
- [x] 按日期/植株/指标去重，非数值和越界保留质量标记；按年份计量实际覆盖。
- [x] 解析CSV和所有原始XLSX测点列，按测点/时间去重，记录冲突；初始/最终窗口外不参与运行。
- [x] 生成统一半小时驱动；最近过去读数使用ceil半小时，训练期日内均值只来自2023/24，补值保留来源。
- [x] 运行导入并保留可检查质量报告。

## Task 2: 机理与跨年校准

**Files:** agent/m3/ThermalInternodeHeightModel.java、M3MultiYearCalibrationService.java、M3ObservationService.java、M3Controller.java。

**Interfaces:** GET /m3/observations；POST /m3/calibrate；GET /m3/result；结果schemaVersion=2，seasons包含series/driver/scores，parameters冻结。

- [x] 实现FSP启发的简化节间模型：积温触发节间出生；节间年龄驱动Logistic伸长；总长度求株高。固定k=0.02、midpoint=150°Cd为明确形态假设，拟合phyllochron与最大节间长，边界25–50°Cd与2–20cm。
- [x] 初态热年龄通过反求观测H0确定，不根据后续2025株高重置；记录缺少逐节初态的局限。
- [x] 网格搜索＋缩小邻域的有界最小二乘，仅使用2023/24按年份平衡的CK株高增量，保存边界与有限扰动敏感性。
- [x] 同样训练数据拟合线性GDD伸长基线，记录原机制/线性/节间模型误差，不依据2025误差选择模型。
- [x] 所有年份运行原项目模型与节间候选，共用同一环境驱动；2025输出MAE/RMSE/Bias与逐株残差，结果允许未改善。
- [x] 保存输入哈希、算法源码哈希、来源、假设与许可引用；输出年份保留评估，不自动发布默认参数。

## Task 3: 结果页面与三维回放

**Files:** src/api/m3/index.ts、src/views/modelCalibration/index.vue、src/router/route.ts、src/router/workspaceMenu.ts、src/views/digitalTwin/M3ReplayPanel.vue、src/views/digitalTwin/index.vue。

**Interfaces:** 结果页按年份/植株查看同一冻结参数结果；大棚通过mode=m3载入最新结果。

- [x] 创建桌面三年对照页：原模拟线、修正模拟线、M3原测散点；训练/保留标签、算法参数、基线比较、质量和来源。
- [x] 请求失败显示空状态，不填造M3观测；导出版本JSON；入口可打开大棚回放。
- [x] 在大棚加入独立结果回放面板，传递株高/对应环境，叶和果保持示意，设备状态不冒充M3实测。
- [x] 管理图表销毁和播放计时器生命周期。

## Task 4: 构建与交付

- [x] Java生产构建显式跳过测试，Vue输出独立目录；保留旧产物。
- [x] 执行一次业务校准，记录实得参数与保留误差。
- [x] 启动平台并打开真实对照/回放页，查看实际数据显示；不运行自动测试。
- [x] 更新规划记录并报告结果及适用范围。

