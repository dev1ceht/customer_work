-- 退款任务 05：持久化支付执行凭证、失败原因和明确受理时间。

ALTER TABLE `cw_refund`
    ADD COLUMN `execution_reference` VARCHAR(128) NULL
        COMMENT '支付渠道受理/执行凭证';

ALTER TABLE `cw_refund`
    ADD COLUMN `execution_failure_reason` VARCHAR(256) NULL
        COMMENT '支付执行失败或结果未知的内部原因';

ALTER TABLE `cw_refund`
    ADD COLUMN `executed_at_ms` BIGINT NOT NULL DEFAULT 0
        COMMENT '支付渠道明确受理时间戳（毫秒）';
