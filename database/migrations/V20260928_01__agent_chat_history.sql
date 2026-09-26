-- 智能体会话历史持久化（半永久化）。
-- Create-only migration. Never alter the legacy tables (disease/greenhouse/storage/...) or the agent_* tables.
--
-- 背景：AgentChatController 走 SSE 流式返回且不落库，SessionHistoryStore 是进程内内存，
-- agent_step_trace 只存 input_digest/output_digest 审计摘要。结果是**回答正文没有任何持久化**，
-- 重启即失，也无法作为回答质量的评分对象。
--
-- 本表只保存"问题 + 最终回答 + 终态元数据"，刻意**不保存**检索命中的证据正文与内部提示：
-- 证据正文已在 agent_knowledge_chunk 里，重复存储会让历史表随知识库一起膨胀。
-- 引用列表以 JSON 存 id/名称等指向性信息，需要正文时按指向回查。

CREATE TABLE IF NOT EXISTS `agent_chat_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL,
  `crop` VARCHAR(64) NULL,
  `question` TEXT NOT NULL,
  `answer` TEXT NOT NULL,
  `status` VARCHAR(16) NOT NULL COMMENT 'DONE / REFUSED / ERROR，与 AgentResult.Status 一致',
  `refusal_reason` VARCHAR(64) NULL COMMENT '拒答或拦截原因，DONE 时为 NULL',
  `citation_count` INT NOT NULL DEFAULT 0,
  `citations_json` TEXT NULL COMMENT '引用列表的指向性信息（编号/名称/来源），不含证据正文',
  `steps` INT NOT NULL DEFAULT 0 COMMENT '本次编排执行的步数',
  `tools` VARCHAR(512) NULL COMMENT '按顺序调用的工具名，逗号分隔，供审计与评测追溯',
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_chat_history_session` (`session_id`, `created_at`),
  KEY `idx_agent_chat_history_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
