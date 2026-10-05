# 禾序：农业智能体决策与番茄温室仿真平台

当前版本整理于 2026-10-05，包含桌面农业平台 UI、病害识别、DeepSeek 决策助手、农业知识检索、本地文献目录、番茄温室三维展示、随机事件自动接管及 M3 在线观测修正。

## 项目流程

农情输入 → 视觉观察与状态预测 → 知识与模型研判 → 受约束仿真处置 → 执行反馈 → 新观测到达与状态修正。

- 图像检测输出病害候选、检测框与模型分数，结果可带入助手核对。
- 决策助手提供普通独立问答和关联大棚问答，保留工具及引用证据。
- 生长预测采用有效积温驱动节间生长，扩展卡尔曼滤波递推修正有效热龄状态。
- 数字温室可生成天气和设备故障事件，AI 形成设备目标，服务端核查后在仿真中应用，并与未干预分支对照。
- 文献与数据模块提供本地 PDF 目录、登记来源和原文链接；农事规划支持文本、表单和 CSV 输入。

当前 M3 数据为公开历史观测的顺序回放，温室设备响应属于仿真。株高评价不能替代果实、产量或现场设备效果验证。

## 代码结构

| 目录 | 内容 |
| --- | --- |
| `YOLO_AI_CropDisease_Detection_Vue` | Vue 3、TypeScript、Element Plus 与 Three.js 前端 |
| `YOLO_AI_CropDisease_Detection_SpringBoot` | 业务、智能体编排、知识库、模型、校准与仿真服务 |
| `YOLO_AI_CropDisease_Detection_Flask` | YOLO 推理、视频处理与文本向量服务 |
| `database` | 数据库迁移及知识数据 |
| `scripts` | 启停脚本与 M3 原始数据导入 |
| `assets/datasets/horti-m3` | 可恢复的标准化 M3 参考观测与校准结果 |
| `docs/presentation` | 最新计划书介绍、在线修正机制表述、截图及六条演示录像 |

## 运行准备

1. 配置 MySQL，导入项目数据库，并按 `database` 中的迁移说明更新表和知识数据。
2. 按后端 `pom.xml` 准备兼容 JDK 和 Maven，前端按 `package.json` 安装 Node 依赖，Flask 按 `requirements.txt` 安装 Python 依赖。
3. 数据库账号、密码及 DeepSeek 密钥使用环境变量 `CROPDISEASE_DB_USER`、`CROPDISEASE_DB_PASSWORD`、`DEEPSEEK_API_KEY`。相关配置见后端 `application.properties`，请在本机设置，不写入仓库。
4. 按 [M3 参考数据恢复说明](assets/datasets/horti-m3/README.md) 恢复标准化观测与已保存校准结果。
5. Windows 可参考 `scripts/start-local-platform.ps1` 启动。现有脚本包含开发机的运行时路径，换机器需调整为本机安装目录；手动启动顺序为 MySQL → Flask → Spring Boot → Vue。

默认服务端口：前端 8100、Spring Boot 9999、Flask 5000。前端默认入口为 `http://localhost:8100/index.html#/homePage`。

FFmpeg 从官方发行包单独恢复到项目的 `ffmpeg-7.1-full_build` 目录。原始大体积数据压缩包、本机数据库、依赖缓存和个人上传的论文文件由本地管理；文献目录使用 `AGRI_KNOWLEDGE_LIBRARY_ROOT` 配置。M3 数据来源和许可见参考数据目录，其他第三方依赖和素材沿用各自许可。

## 计划书与演示

- [项目介绍与计划书重点](docs/presentation/项目介绍与计划书重点.md)
- [在线预测修正机制：计划书与答辩表述](docs/presentation/在线预测修正机制-计划书表述.md)
- [截图与资料入口](docs/presentation/先看这里.md)
- [完整流程与五条重点录像](docs/presentation/新版录像/使用说明.md)
- [技术说明](docs/tomato-greenhouse-agent.md)

最新材料将修正机制表述为“机理预测—观测接入—状态同化—递推预测”，并区分生长预测反馈与决策执行反馈。
