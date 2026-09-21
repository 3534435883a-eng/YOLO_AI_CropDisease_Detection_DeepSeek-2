-- Tomato greenhouse agent simulation schema.
-- This migration is create-only. Do not run cropdisease.sql on an existing database.

CREATE TABLE IF NOT EXISTS `agent_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_code` VARCHAR(64) NOT NULL,
  `greenhouse_code` VARCHAR(64) NOT NULL,
  `crop_type` VARCHAR(64) NOT NULL,
  `growth_stage` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `active_slot` TINYINT NULL,
  `step_no` INT NOT NULL DEFAULT 0,
  `simulated_at` DATETIME(3) NOT NULL,
  `tick_minutes` SMALLINT NOT NULL DEFAULT 15,
  `seed` BIGINT NOT NULL,
  `model_version` VARCHAR(64) NOT NULL,
  `baseline_json` LONGTEXT NOT NULL,
  `source_greenhouse_record_id` BIGINT NULL,
  `created_by` VARCHAR(255) NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_run_code` (`run_code`),
  UNIQUE KEY `uk_agent_run_active_slot` (`active_slot`),
  KEY `idx_agent_run_status_updated` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_environment_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `step_no` INT NOT NULL,
  `simulated_at` DATETIME(3) NOT NULL,
  `temperature_c` DECIMAL(6,2) NOT NULL,
  `air_humidity_pct` DECIMAL(5,2) NOT NULL,
  `soil_moisture_pct` DECIMAL(5,2) NOT NULL,
  `co2_ppm` DECIMAL(8,2) NOT NULL,
  `light_ppfd` DECIMAL(8,2) NOT NULL,
  `soil_ph` DECIMAL(4,2) NOT NULL,
  `vpd_kpa` DECIMAL(5,3) NOT NULL,
  `environment_risk` DECIMAL(5,2) NOT NULL,
  `disease_pressure` DECIMAL(5,2) NOT NULL,
  `risk_level` VARCHAR(16) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL,
  `input_json` LONGTEXT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_snapshot_run_step` (`run_id`, `step_no`),
  KEY `idx_agent_snapshot_run_time` (`run_id`, `simulated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_device` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `device_code` VARCHAR(32) NOT NULL,
  `device_name` VARCHAR(64) NOT NULL,
  `control_mode` VARCHAR(16) NOT NULL,
  `health_status` VARCHAR(16) NOT NULL,
  `desired_state` VARCHAR(16) NOT NULL,
  `actual_state` VARCHAR(16) NOT NULL,
  `last_changed_step` INT NOT NULL DEFAULT 0,
  `manual_lock_by` VARCHAR(255) NULL,
  `manual_lock_until` DATETIME(3) NULL,
  `legacy_storage_id` BIGINT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_device_run_code` (`run_id`, `device_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_policy_decision` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `snapshot_id` BIGINT NOT NULL,
  `step_no` INT NOT NULL,
  `rule_code` VARCHAR(64) NOT NULL,
  `priority` INT NOT NULL,
  `risk_level` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `summary` VARCHAR(1000) NOT NULL,
  `reason_json` LONGTEXT NOT NULL,
  `rule_version` VARCHAR(64) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_policy_run_step_rule` (`run_id`, `step_no`, `rule_code`),
  KEY `idx_agent_policy_run_step_priority` (`run_id`, `step_no`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_device_action` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `snapshot_id` BIGINT NOT NULL,
  `decision_id` BIGINT NULL,
  `step_no` INT NOT NULL,
  `device_id` BIGINT NOT NULL,
  `device_code` VARCHAR(32) NOT NULL,
  `command_id` VARCHAR(128) NOT NULL,
  `target_state` VARCHAR(16) NOT NULL,
  `execution_status` VARCHAR(16) NOT NULL,
  `block_reason` VARCHAR(1000) NULL,
  `executor_type` VARCHAR(16) NOT NULL,
  `actor_username` VARCHAR(255) NULL,
  `executed_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_action_command` (`command_id`),
  UNIQUE KEY `uk_agent_action_run_step_device` (`run_id`, `step_no`, `device_code`),
  KEY `idx_agent_action_decision` (`decision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_resource_stock` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `resource_code` VARCHAR(32) NOT NULL,
  `resource_name` VARCHAR(64) NOT NULL,
  `unit` VARCHAR(16) NOT NULL,
  `opening_quantity` DECIMAL(12,3) NOT NULL,
  `available_quantity` DECIMAL(12,3) NOT NULL,
  `low_threshold` DECIMAL(12,3) NOT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_resource_run_code` (`run_id`, `resource_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_resource_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `resource_stock_id` BIGINT NOT NULL,
  `device_action_id` BIGINT NOT NULL,
  `change_quantity` DECIMAL(12,3) NOT NULL,
  `balance_after` DECIMAL(12,3) NOT NULL,
  `reason` VARCHAR(512) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_ledger_action_resource` (`device_action_id`, `resource_stock_id`),
  KEY `idx_agent_ledger_stock_time` (`resource_stock_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_vision_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `source_type` VARCHAR(32) NOT NULL,
  `source_record_id` BIGINT NOT NULL,
  `observed_at` DATETIME(3) NOT NULL,
  `crop_type` VARCHAR(64) NOT NULL,
  `disease_id` BIGINT NULL,
  `detected_label` VARCHAR(512) NOT NULL,
  `confidence` DECIMAL(5,2) NULL,
  `severity` VARCHAR(16) NOT NULL,
  `evidence_url` VARCHAR(1024) NULL,
  `raw_payload` LONGTEXT NOT NULL,
  `review_status` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_vision_run_source` (`run_id`, `source_type`, `source_record_id`),
  KEY `idx_agent_vision_run_observed` (`run_id`, `observed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_alert` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `snapshot_id` BIGINT NULL,
  `step_no` INT NOT NULL,
  `dedupe_key` VARCHAR(128) NOT NULL,
  `alert_code` VARCHAR(64) NOT NULL,
  `severity` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `message` VARCHAR(1000) NOT NULL,
  `first_seen_at` DATETIME(3) NOT NULL,
  `last_seen_at` DATETIME(3) NOT NULL,
  `ack_by` VARCHAR(255) NULL,
  `ack_at` DATETIME(3) NULL,
  `metadata_json` LONGTEXT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_alert_run_dedupe` (`run_id`, `dedupe_key`),
  KEY `idx_agent_alert_run_status_severity` (`run_id`, `status`, `severity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `entity_type` VARCHAR(64) NOT NULL,
  `entity_id` BIGINT NULL,
  `actor_username` VARCHAR(255) NULL,
  `trace_id` VARCHAR(64) NOT NULL,
  `before_json` LONGTEXT NULL,
  `after_json` LONGTEXT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_audit_run_time` (`run_id`, `created_at`),
  KEY `idx_agent_audit_entity_time` (`entity_type`, `entity_id`, `created_at`),
  KEY `idx_agent_audit_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_parameter_source` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `parameter_code` VARCHAR(64) NOT NULL,
  `parameter_name` VARCHAR(128) NOT NULL,
  `value_text` VARCHAR(512) NOT NULL,
  `unit` VARCHAR(32) NULL,
  `source_name` VARCHAR(512) NOT NULL,
  `source_url` VARCHAR(1024) NULL,
  `version` VARCHAR(64) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_parameter_source_version` (`parameter_code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
