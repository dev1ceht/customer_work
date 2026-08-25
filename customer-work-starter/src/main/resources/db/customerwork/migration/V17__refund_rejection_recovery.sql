-- 退款任务 04：保存创建冻结前的订单状态，支持人工拒绝后的安全恢复。

ALTER TABLE `cw_refund_fulfillment_freeze`
    ADD COLUMN `previous_order_status` VARCHAR(32) NULL
        COMMENT '创建退款冻结前的订单状态，拒绝审批时恢复';
