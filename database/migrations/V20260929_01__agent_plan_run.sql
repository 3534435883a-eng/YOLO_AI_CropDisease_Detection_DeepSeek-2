-- 农事规划推演的运行记录（半永久化）。
-- Create-only migration. Never alter the legacy tables (disease/greenhouse/storage/...) or the agent_* tables.
--
-- 背景：推演通道（/ai/agri/plan/deduce）是**独立于 /ai/agent/chat 的一条链路**，不经过
-- AgentOrchestrator，因此也不会写 agent_chat_history。它的输出是大模型用自身知识做的推演，
-- 没有任何可核对的引用，所以"留下了什么、当时的农情是什么、参考基线是哪一批"这几件事
-- 必须整体留存——否则事后无法判断一份方案是在什么输入下产出的，而这正是这条通道最需要的审计。
--
-- 与 agent_chat_history 的区别：那张表存问答，本表存推演。两者刻意不合并——
-- 推演的输入（结构化农情）、附带的基线标识、以及是否降级/是否补过声明，
-- 在问答那边都没有对应物；硬塞进一张表会让两边都出现一堆恒为 NULL 的列。

CREATE TABLE IF NOT EXISTS `agent_plan_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `seed` BIGINT NOT NULL COMMENT '参考基线天气相位种子；与 days 一起决定可复算的基线批次',
  `days` INT NOT NULL COMMENT '参考基线推演天数',
  `question` TEXT NULL COMMENT '用户的自然语言诉求',
  `situation_json` TEXT NULL COMMENT '结构化农情输入（键名见 SituationFields）',
  `answer_markdown` MEDIUMTEXT NOT NULL COMMENT '模型推演正文，含首行推演声明',
  `structured_json` TEXT NULL COMMENT '正文尾部 JSON 块解析出的动作清单；模型没给可解析结果时为 NULL',
  `banner_injected` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 表示推演声明是服务端补的（模型漏写）',
  `stream_mode` VARCHAR(16) NOT NULL COMMENT 'STREAM 或 FALLBACK，与 DeductionResult.StreamMode 一致',
  `fallback_reason` VARCHAR(255) NULL COMMENT '降级原因，正常流式时为 NULL',
  `baseline_batch_id` VARCHAR(64) NULL COMMENT '参考基线批次号，可用于事后复算比对',
  `baseline_json` TEXT NULL COMMENT '随推演下发的基线数值与 scopeNote/windowNote',
  `sections` VARCHAR(512) NULL COMMENT '正文里实际出现的二级标题，逗号分隔，供检查分节骨架是否齐全',
  `missing_fields` VARCHAR(512) NULL COMMENT '本次未提供的农情字段（中文标签），逗号分隔',
  `elapsed_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '本次推演耗时（毫秒）',
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_plan_run_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
