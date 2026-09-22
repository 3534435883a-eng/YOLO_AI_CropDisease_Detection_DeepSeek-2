-- Agriculture-specific agent knowledge schema (RAG + knowledge graph).
-- Create-only migration. Never alter the legacy tables (disease/greenhouse/storage/...).

CREATE TABLE IF NOT EXISTS `agent_knowledge_chunk` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_table` VARCHAR(64) NOT NULL,
  `source_id` BIGINT NOT NULL,
  `crop_type` VARCHAR(64) NULL,
  `disease_name` VARCHAR(128) NULL,
  `field_type` VARCHAR(16) NOT NULL,
  `chunk_no` INT NOT NULL,
  `start_offset` INT NOT NULL DEFAULT 0,
  `content` TEXT NOT NULL,
  `content_hash` CHAR(64) NOT NULL,
  `embedding` VARBINARY(4096) NULL,
  `embedding_model` VARCHAR(64) NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_chunk_source` (`source_table`, `source_id`, `field_type`, `chunk_no`),
  UNIQUE KEY `uk_agent_chunk_hash` (`content_hash`),
  KEY `idx_agent_chunk_crop_disease` (`crop_type`, `disease_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_knowledge_node` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `node_type` VARCHAR(32) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `alias` VARCHAR(255) NULL,
  `source_name` VARCHAR(512) NOT NULL,
  `source_url` VARCHAR(1024) NULL,
  `version` VARCHAR(64) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_node_type_name` (`node_type`, `name`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_knowledge_edge` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `head_id` BIGINT NOT NULL,
  `relation` VARCHAR(32) NOT NULL,
  `tail_id` BIGINT NOT NULL,
  `weight` DECIMAL(6,3) NULL,
  `source_name` VARCHAR(512) NOT NULL,
  `version` VARCHAR(64) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_edge_triple` (`head_id`, `relation`, `tail_id`, `version`),
  KEY `idx_agent_edge_head` (`head_id`),
  KEY `idx_agent_edge_tail` (`tail_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_step_trace` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL,
  `run_id` BIGINT NULL,
  `step_no` INT NOT NULL,
  `tool_name` VARCHAR(64) NOT NULL,
  `input_digest` CHAR(64) NOT NULL,
  `output_digest` CHAR(64) NULL,
  `duration_ms` BIGINT NOT NULL,
  `degraded` TINYINT NOT NULL DEFAULT 0,
  `status` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_step_session` (`session_id`, `step_no`),
  KEY `idx_agent_step_run` (`run_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
