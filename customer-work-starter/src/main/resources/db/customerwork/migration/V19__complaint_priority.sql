-- 投诉工单优先级：由 Skill/工具提交的安全分级结果持久化，查询时不得退回默认优先级。
ALTER TABLE `cw_complaint`
    ADD COLUMN `priority` VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'
        COMMENT '投诉优先级：LOW/MEDIUM/HIGH/URGENT'
        AFTER `status`;
