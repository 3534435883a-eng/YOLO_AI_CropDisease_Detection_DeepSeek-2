# 番茄模拟、参数校准与观测纠偏算法调研

> 实施状态更新：用户认可跨年计划后，已完成简化热时间节间机制、双参数校准与2025年份保留评价，结果见[三年校准结果](2026-10-02-m3-multiyear-results.md)。本文“尚未实现”的表述属于调研阶段；UKF/EnKF、完整生理模型及产量校准目前仍未实现。

检索日期：2026-10-02。项目场景：公开 Horti-M3 数据驱动的番茄温室模拟，未来将观测入口替换为现场采集。

## 三年株高覆盖核对（用户指出后补充）

此前“8个日期”仅指首版选定的2025批次，不能据此描述完整M3的规模。现已只读核对本地三年raw_data目录：2023/2024的目录名为grow_index，2025为growth_index，当前导入器只处理2025。

| 年份 | 有株高数值的日期数 | 植株编号数 | 株高数值记录数 | 日期范围 |
|---|---:|---:|---:|---|
| 2023 | 9 | 78 | 702 | 05-07至07-01 |
| 2024 | 9 | 102 | 918 | 09-21至11-13 |
| 2025 | 8 | 108 | 864 | 04-19至06-13 |

合计26个日期、2484个唯一年份/日期/植株数值记录；不等于2484次独立环境试验，尚未全量审核测量口径和异常。2023五月和六月的“2023夏季西红柿.csv”文件SHA256相同，统计只计一次；2024每表108行，但只有102个株高数值，缺失未算测量。原始目录的日期按表中日期或文件日期获取，未将processed_data每日插值算入。

首版CK11–CK16只是2025中的6株，对应48个株高记录。完整三年可以用于更广泛的机制研究及跨年评估，需先对齐品种、处理、环境对应、单位和生育起点；不能把不同批次直接拼成一株连续三年。候选扩展为2023/2024可比批次建立和校准机制，2025作独立年份评价。该跨年扩展尚未导入或实施，原2025首版划分仍是当前代码状态。

## 结论

最有用的参考是两个相互补充的项目：

1. **mnqoliveira/data-assimilation-tomato-models**：可审查的 Reduced TOMGRO / Vanthoor 模型、参数校准、UKF / EnKF / PF 及观测函数代码。适合研究生理模拟与观测纠偏。
2. **ksmolen2/vtc**：瓦赫宁根团队公开的动态三维番茄功能结构模型。对应论文明确用观测株高调整节间出现和伸长参数，最贴近本项目的株高与三维展示目标。

推荐先完成已批准的积温株高校准基线；下一步候选为“简化机理模型 + 独立形态子模型 + 少量参数校准 + 观测状态更新”。本文件提出的是研究后的候选扩展，不代表已经实现，也不改写已批准功能的完成状态。

## 检索范围与核对方法

使用 Exa Search，按生长机理、参数优化、数据同化、中国温室形态模型、作者代码、三维数字孪生等 13 个检索角度，请求共 76 条候选结果，包含 66 个不同链接。候选结果包含重复、无关以及二次转载；数量不代表逐篇读完，也不代表穷尽全部平台。

重点复核了论文出版方、WUR 原始论文、作者 GitHub 仓库及实际模型/滤波/校准源码。Exa 聚合条目只作为发现线索。未运行外部代码、未安装外部依赖、未将作者实验的精度视作本项目精度。

## 一、最匹配的论文和已有项目

| 来源 | 算法与输出 | 与 M3 的关系 | 本次判断 |
|---|---|---|---|
| [Oliveira 作者项目](https://github.com/mnqoliveira/data-assimilation-tomato-models) | Reduced TOMGRO、Vanthoor；校准、UKF、EnKF、PF；LAI、干物质与果实状态 | 环境驱动有温度、光和 CO₂；M3 的 LAI/LDW 定义尚需确认 | 首选生理计算与纠偏参考 |
| [Oliveira 等，2025](https://doi.org/10.1016/j.inpa.2025.02.003) | 用植株质量与图像观测更新 Reduced TOMGRO；比较 UKF、EnKF | 方法可借鉴；原文测量和观测函数不能直接套用 M3 | 首选真实观测同化论文 |
| [Smoleňová 等，2025](https://doi.org/10.1093/insilicoplants/diaf022)；[作者 vtc 项目](https://github.com/ksmolen2/vtc) | GroIMP 动态三维番茄；积温驱动节间出现，Logistic 节间伸长；贝叶斯优化校准株高 | M3 有株高与环境，缺少该研究完整器官参数及逐株节间初态 | 首选形态与三维生长参考 |
| [Vazquez-Cruz 等，2014](https://doi.org/10.1016/j.compag.2013.10.006) | eFAST / Sobol 敏感性筛选后，遗传算法校准 Reduced TOMGRO | 可借鉴“先筛参数再拟合”；不照搬 8 参数配置 | 参数筛选依据 |
| [Gong 等，2021](https://doi.org/10.1016/j.atech.2021.100011) | 比较 GA、PSO、DE 校准成熟果实干物质，拟合 14 个参数 | 论文使用 3 组年度环境与产量数据；本项目首期株高数据不足以照搬 | 多参数优化的对照依据 |
| [设施番茄外观形态及物质累积分配模型构建与验证，2022](https://doi.org/10.11975/j.issn.1002-6819.2022.21.022) | 辐热积驱动形态、光合呼吸、干鲜物质与器官分配；跨茬验证 | 中国塑料/日光温室更贴近场景；株高、光温数据可参考 | 国内形态模型依据 |
| [基于不同驱动因子的番茄生长模型比较，2024](https://doi.org/10.11898/1001-7313.20240610) | Logistic；比较积温、辐热积、适宜度对花数/坐果/果径的作用 | 说明不同器官需要不同驱动；不是株高机制的直接验证 | 环境驱动选择依据 |
| [GreenLight](https://github.com/davkat1/GreenLight) | 动态温室气候与作物，能量、CO₂、作物耦合 | 适合设备控制与温室环境；不能直接解决缺少器官形态观测的问题 | 后续环境机制参考 |

### 1. Oliveira：模拟与纠偏都有实际代码

2025 论文：*Leveraging data from plant monitoring into crop models*，Information Processing in Agriculture 12(3):408–429。作者为 Monique Pires Gravina de Oliveira、Thais Queiroz Zorzeto-Cesar、Romis Ribeiro de Faissol Attux、Luiz Henrique Antunes Rodrigues，机构主要为巴西 UNICAMP。

已查看：
- [Reduced TOMGRO](https://github.com/mnqoliveira/data-assimilation-tomato-models/blob/main/models/model_ReducedTomgro.py)：小时光、温度、CO₂参与光合与呼吸；状态包括节数 n、LAI、总干重 w、果实干重 wf、成熟果实干重 wm。没有直接的株高状态。
- [filters.py](https://github.com/mnqoliveira/data-assimilation-tomato-models/blob/main/assimilation/filters.py)：基于 FilterPy 扩展 UKF、EnKF，并有 PF。
- [measurementFunctions.py](https://github.com/mnqoliveira/data-assimilation-tomato-models/blob/main/assimilation/measurementFunctions.py)：明确区分观测与模型状态；部分映射系数按作者实验设定，不能沿用到 M3。
- [run_Calibs.py](https://github.com/mnqoliveira/data-assimilation-tomato-models/blob/main/simulations/run_Calibs.py)：当前文件调用 scipy.optimize.dual_annealing；不能将这份实现描述成 PSO 或最小二乘。文件存在与当前模块名称不一致的导入，尚未运行，不能宣称开箱即用。
- [MIT LICENSE](https://github.com/mnqoliveira/data-assimilation-tomato-models/blob/main/LICENSE)已核对，版权 Monique Oliveira，2022。今后若复用代码，保留原许可与署名。

重要发现：2025 论文用真实植株监测，说明滤波可能改善也可能降低预测表现，取决于观测质量和观测函数。不能认为修正株高就自动修正产量。2023 会议中的部分同化试验使用人工观测，应与 2025 实测研究分别标注。

### 2. vtc：有可解释的株高机制与三维植物

2025 论文：*Development of a tomato functional–structural plant model for digital twin applications*，in silico Plants 7(2), diaf022，Smoleňová 等，瓦赫宁根大学与研究中心团队。原文 [WUR PDF](https://edepot.wur.nl/706749)。

- 动态模型：积温驱动新节间出现；叶与节间生长分别计算；器官需求影响同化物分配。
- 株高子模型：节间长度相加；节间伸长率来自 Logistic 的导数。
- 4 个株高参数：phyllochron（节间出现的积温间隔）、maxlen（最大节间长）、klen（伸长曲线斜率）、tmlen（拐点积温）。
- 原文用带上下界的贝叶斯优化、MAE 目标以及 Sobol 敏感性分析；分时段用截至当时已取得的观测更新参数。
- 论文重点是幼株/营养生长期；M3 的后续结果期不能直接按该范围宣称模型有效。
- 原文指出图像最高叶尖与人工茎顶的高度口径不同。M3 测量口径必须检查，不能仅因都叫株高而直接同化。
- 原文 DATA AVAILABILITY 指向 [ksmolen2/vtc](https://github.com/ksmolen2/vtc)。模型代码公开；器官参数化数据、图像分割、敏感性分析和优化脚本为按请求提供，不能称整套优化流程已公开。
- 已看 main.rgg、parametersMerlice.rgg、modules_organs.rgg；能核对株高输出、积温与 Logistic 节间伸长实现。建议运行 GroIMP 2.2.1。并非浏览器 Three.js 库。
- 根目录未发现 LICENSE，GitHub 元数据 license=null。公开可读不等于已授权直接复制到本项目。可基于论文公开公式自行实现；如要复用代码，先核实授权。论文 CC BY 4.0 与仓库代码许可是不同事项。
- Merlice 的基温 4°C、节间参数是作者品种配置，不应直接冒充广辉201参数。

### 3. GreenLight：环境与设备更完整

作者 David Katzin 的 [GreenLight](https://github.com/davkat1/GreenLight) 当前主分支为 Python 平台，旧 MATLAB 1.x 分支停止开发。作物机制参考 Vanthoor 2011，温室模型参考 Katzin 2021。

[LICENSE.txt](https://github.com/davkat1/GreenLight/blob/main/LICENSE.txt)已核对为 BSD-3-Clause-Clear。适合参考气候、设备与作物耦合关系；完整迁移需温室结构、室外天气、设备和控制信息，仅有 M3 棚内传感器不足以标定完整设备响应。

其他候选：
- [GreenLightPlus](https://github.com/greenpeer/GreenLightPlus)：作者独立扩展，结合 EnergyPlus 的模块需要额外软件与天气文件；仓库标记 GPL-3.0。本轮以方法参考为主。
- [GreenLight-Gym2 / GL-Gym](https://github.com/BartvLaatum/GreenLight-Gym2)：GreenLight 基础上的强化学习控制环境，仓库标记 AGPL-3.0。本项目首先需要能核对的生长预测与纠偏，再考虑策略优化。
- [TomatoXL](https://github.com/XiaolongNWAFU/TomatoXL)：GroIMP/XL 三维冠层光合模型，有气候/冠层/生理输入与运行说明，根目录未见 LICENSE；与 vtc 为不同项目。不是本次首选校准框架。
- [GroIMP 简单番茄教程](https://wiki.grogra.de/doku.php?id=tutorials%3Asimple-tomato-model)：番茄节间、复叶和果穗结构教程，能参考形态规律，不能当精度验证证据。

## 二、落到本项目的单一流程

环境观测 → 单位统一与缺测估计 → 生长模型预测 → 有真实长势观测时修正 → 预测与观测对照 → 三维展示。

M3 是当前的观测来源；未来接入传感器/人工长势测量时复用同一观测入口。参数校准与状态更新是同一流程的两个计算环节，不设置两种用户操作模式。

### 模拟算法建议

1. **生理层**：参考 Reduced TOMGRO 的温度/光/CO₂响应、光合、呼吸与器官干物质分配。保留参数出处与单位，不能把现有简化公式标成完整 TOMGRO。
2. **形态层**：先用已批准的积温伸长机制作为基线；下一阶段引入“积温控制节间出现 + Logistic 节间伸长 + 节间长度求和”。M3 缺少逐节初态，初态需明确假设，无法可靠拟合的参数固定。
3. **展示层**：Three.js 根据模型输出更新株高、叶片/节间几何；动画本身不能作为模型精度证据。果实、叶面积未校准时继续标为示意。

候选形态关系（自行按论文方法实现，不表示当前已有代码）：

- 累积热量 TT：按品种基温与温度响应计算，基温优先有出处，未知时固定为假设，不和所有参数同时自由拟合。
- 第 j 个节间年龄 A_j = TT - 该节间出现时的 TT。
- 节间长 L_j(A_j) = Lmax / [1 + exp(-k × (A_j - A50))]。
- 株高 H = 已测初态 H0 + 后续节间长度的净增量总和。必须处理起始时已有节间，不将每个节间初值无条件计入第二次。
- 可选择对 Logistic 导数积分，并检查时间步长；公式实现与整株数据拟合后再判断它是否优于线性基线。

### 修正算法建议

**参数校准**
- 当前首版选择2025中的 6 株、8 个日期，其中 3 个日期拟合，不是 18 次独立天气试验。该选定批次先只拟合 1–2 个可辨认的参数；完整三年覆盖见上文，扩大到可比批次后重新评估参数辨识能力。
- 有上下界的最小二乘适合低维、便宜的模型；先多起点与敏感性检查。
- 维度/局部极小增加时再比较 DE/PSO；计算昂贵的完整 FSPM 可参考贝叶斯优化。搜索器并不能弥补观测不足。
- 目标建议按日期平衡、按测量误差加权，并对参数偏离可信范围加正则项；这是本项目候选设计，不是复制作者目标。
- 原参数与修正参数分别保存版本与输入哈希。无需让用户选择两个工作模式。

**状态更新**
- 有真实观测到来时，UKF 适合小型非线性状态模型；EnKF 可用于并行样本表达模型/参数不确定性。
- 通用关系：先预测 x^- = f(x,u,θ)，再用观测误差 z-h(x^-)、协方差计算增益 K，更新 x^+ = x^- + K[z-h(x^-)]。
- h 是观测函数：株高/叶尖高度、LAI 与干重都需要单独对应，不能混用单位或器官。
- 当前先更新可核对的株高；不根据一个株高测量无依据地调整所有器官或产量。
- Q/R 代表过程和观测误差，要根据重复测量和保留数据合理确定；不能仅为让曲线贴合而调成近乎直接复制观测。
- UKF/EnKF 是候选后续能力，本轮尚未实现或验证。

### 缺测与评价

已按用户要求统一补值：主 CK 测点 → 同处理参考测点 → 其他处理测点 → 校准期同一时段均值。后两类更有偏差，全部保留来源并标为估计；不增加模式切换。

只有缺失环境输入参与上述填补。株高曲线可插值用于展示，但补值不增加独立实测样本，不参与保留误差统计。

固定划分：04-19 初态；04-26、05-10、05-17 校准；05-28、05-31、06-07、06-13 保留评估。

冻结参数、保留期不读长势观测是预测评估。若将保留日期观测喂给滤波再算当日误差，那是同化重建误差，不能冒充未来预测误差。后续实时能力应评价观测更新之后的下一次预测，可在同一流程内部记录不同指标，不必增设模式。

M3 光照原始数值与发表单位有冲突，目前 klux 解读仍是待核实假设；VWC 与旧水分状态、仪器 LAI/LDW 与模型状态的定义仍有差别。模型优先目标为株高，不对产量精度作承诺。

## 三、实施优先级

1. 完成已批准的原模型 / M3 观测 / 线性积温修正版对照，取得保留期误差作为基线。
2. 对照 vtc 论文增加少量形态参数，按同一划分评估节间模型是否改善。若缺少初态信息导致参数不可辨认，先采集所需信息或缩小参数集合。
3. 生理部分参考 Reduced TOMGRO，将光合/呼吸和单位规范化；仅在观测口径确认后逐项开放 LAI/干重校准。
4. 接入观测状态更新与未来传感器接口；保留每次预测和更新前后的结果。
5. 再研究 GreenLight 环境反馈与控制策略，扩展大棚设备交互的计算依据。

本轮调研完成。尚未运行外部项目或证明任何候选算法在本项目改善；当前实现与构建进度见 .planning/horti-m3-simulation-reference/progress.md。
