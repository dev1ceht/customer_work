-- 退款任务 03：把服务端生成的业务幂等键持久化，支持写入结果不确定时按同一键查询。
-- V15 已提供租户/订单/类型的活跃唯一约束；本迁移补充可追踪的稳定键。

ALTER TABLE `cw_refund`
    ADD COLUMN `idempotency_key` VARCHAR(160) NULL
        COMMENT '服务端生成的全额退款业务幂等键';

UPDATE `cw_refund`
SET `idempotency_key` = CONCAT('REFUND:FULL:', `tenant_id`, ':', `order_id`)
WHERE `type` = 'REFUND'
  AND `active_marker` = 'ACTIVE'
  AND `idempotency_key` IS NULL;

ALTER TABLE `cw_refund`
    ADD UNIQUE KEY `uk_refund_idempotency` (`tenant_id`, `idempotency_key`);
