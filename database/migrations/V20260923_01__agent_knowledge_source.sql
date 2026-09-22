-- 权威知识库来源登记与块来源映射。
-- create-only；不改动遗留表，也不对既有 agent_knowledge_chunk 做 ALTER
-- （MySQL 8 的 ADD COLUMN 没有 IF NOT EXISTS，而本迁移每次启动都会被执行，ALTER 会在第二次失败）。
-- 因此用独立的来源映射表承载"块属于哪个来源、权威度多少"，效果等价且可重复执行。

CREATE TABLE IF NOT EXISTS `agent_knowledge_source` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_code` VARCHAR(64) NOT NULL,
  `source_name` VARCHAR(512) NOT NULL,
  `source_type` VARCHAR(8) NOT NULL,
  `authority_level` TINYINT NOT NULL,
  `url` VARCHAR(1024) NULL,
  `license_note` VARCHAR(512) NULL,
  `version` VARCHAR(64) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_source_code_version` (`source_code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_knowledge_chunk_origin` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `content_hash` CHAR(64) NOT NULL,
  `source_code` VARCHAR(64) NOT NULL,
  `authority_level` TINYINT NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_chunk_origin_hash_source` (`content_hash`, `source_code`),
  KEY `idx_agent_chunk_origin_source` (`source_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
