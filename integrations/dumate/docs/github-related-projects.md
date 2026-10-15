# 相近开源项目调研（2026-10-14）

只查阅官方仓库和其维护者文档，未复制实现或安装新框架。农业仿真仍使用本项目原服务。

| 项目 | 已核对的内容 | 对本轮的启发 | 仓库声明许可证 |
| --- | --- | --- | --- |
| [cafzal/frontier](https://github.com/cafzal/frontier) | 自然语言问题建模、目标/硬约束、工具求解、结果解释、无解处理 | 语言模型负责明确需求，计算程序负责验证候选是否合格 | Apache-2.0 |
| [optuna/optuna](https://github.com/optuna/optuna) | 参数优化、受约束多目标场景 | 将目标和约束分别表示；有限策略集无需引入采样器 | MIT |
| [langchain-ai/langgraph](https://github.com/langchain-ai/langgraph) | 持久状态、暂停并接收人工反馈 | 未确认阈值先澄清，任务状态和调用键持久保存 | MIT |
| [K-Dense-AI/scientific-agent-skills](https://github.com/K-Dense-AI/scientific-agent-skills) | 科研Skill组织、pymoo优化说明 | 将领域约束写入可复用Skill，把重复数学计算封装为脚本 | 仓库MIT；各Skill须分别核对 |

参考原文：[Frontier工作流与设计](https://github.com/cafzal/frontier#workflow)、[Frontier最佳实践](https://github.com/cafzal/frontier/blob/main/best-practices.md)、[LangGraph人工反馈](https://github.com/langchain-ai/langgraphjs/blob/main/docs/docs/agents/human-in-the-loop.md)、[pymoo Skill](https://github.com/K-Dense-AI/scientific-agent-skills/blob/main/skills/pymoo/SKILL.md)。

本轮独立实现的是四个已有管理策略的约束检查与优先级排序。未采用Frontier的进化求解器、Optuna采样器、LangGraph运行时或K-Dense脚本；不得把上述项目能力写成本作品已经实现的功能。
