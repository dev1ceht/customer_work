-- 退款任务 02：把用户确认后的申请、人工审批和履约冻结纳入同一安全边界。
-- V1 已冻结；本迁移只追加字段/表，不改变历史基线。

ALTER TABLE `cw_refund`
    ADD COLUMN `application_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW'
        COMMENT '退款申请状态：PENDING_REVIEW/REJECTED/APPROVED_PENDING_EXECUTION/EXECUTING/EXECUTED/EXECUTION_FAILED',
    ADD COLUMN `active_marker` VARCHAR(16) NULL
        COMMENT '活跃全额退款占位键；拒绝申请为空',
    ADD COLUMN `user_id` VARCHAR(64) NULL COMMENT '已验签的申请用户主体',
    ADD COLUMN `approval_id` VARCHAR(64) NULL COMMENT '关联人工审批单号',
    ADD COLUMN `policy_version` VARCHAR(64) NULL COMMENT '资格决策政策版本',
    ADD COLUMN `risk_level` VARCHAR(16) NULL COMMENT '资格决策风险等级',
    ADD COLUMN `rule_ids_json` VARCHAR(1024) NULL COMMENT '资格规则证据 JSON';

UPDATE `cw_refund`
SET `application_status` = CASE
        WHEN `status` = 'DENIED' THEN 'REJECTED'
        WHEN `status` = 'APPROVED' THEN 'APPROVED_PENDING_EXECUTION'
        ELSE 'PENDING_REVIEW'
    END,
    `active_marker` = CASE
        WHEN `type` = 'REFUND' AND `status` <> 'DENIED' THEN 'ACTIVE'
        ELSE NULL
    END
WHERE `application_status` = 'PENDING_REVIEW' AND `active_marker` IS NULL;

ALTER TABLE `cw_refund`
    ADD UNIQUE KEY `uk_refund_active_full` (`tenant_id`, `order_id`, `type`, `active_marker`),
    ADD INDEX `idx_refund_application_status` (`tenant_id`, `application_status`, `created_at_ms`);

ALTER TABLE `cw_approval`
    ADD COLUMN `user_id` VARCHAR(64) NULL COMMENT '已验签的终端用户主体';

CREATE TABLE IF NOT EXISTS `cw_refund_fulfillment_freeze` (
    `tenant_id`     VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）',
    `freeze_id`     VARCHAR(64) PRIMARY KEY COMMENT '履约冻结记录号',
    `order_id`      VARCHAR(64) NOT NULL COMMENT '订单号',
    `refund_no`     VARCHAR(64) NOT NULL COMMENT '退款申请号',
    `status`        VARCHAR(16) NOT NULL COMMENT 'ACTIVE/RELEASED',
    `active_marker` VARCHAR(16) NULL COMMENT 'ACTIVE 时占用订单唯一键，释放后为空',
    `reason`        VARCHAR(128) NOT NULL COMMENT '冻结原因',
    `created_at_ms` BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
    `released_at_ms` BIGINT DEFAULT 0 COMMENT '解除时间戳（毫秒）',
    UNIQUE KEY `uk_refund_freeze_active_order` (`tenant_id`, `order_id`, `active_marker`),
    INDEX `idx_refund_freeze_refund` (`tenant_id`, `refund_no`),
    INDEX `idx_refund_freeze_status` (`tenant_id`, `status`, `created_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款申请履约冻结（ACTIVE 同订单唯一）';
