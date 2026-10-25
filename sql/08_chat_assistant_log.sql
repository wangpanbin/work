-- ============================================================
-- v0.4.0 增量 - 对话助手「写审计 + 用量」日志
-- 兼容: 已运行 01+02+03 的库可幂等执行(重复跑不报错)
-- ============================================================

USE cinema;

-- 一张表两个口径:
--   口径一(IT-05 写审计): kind='WRITE_CONFIRM' 的行记录每一次「用户点确认 → 锁座」的尝试。
--     红线指标 —— confirmed_by_user=0 的写事件数必须**恒为 0**:
--     助手本身没有写工具(ChatTools 全是只读 @Tool),写入口只有确认端点一处,
--     所以这个计数结构上就是 0;本表的价值是把它变成**可查证的 0**,
--     并在将来有人加了写入口却忘了打标时立刻暴露出来。
--   口径二(IT-11 用量看板): CHAT 与 WRITE_CONFIRM 两类行统计调用量、失败率、
--     P95 耗时与估算成本。
CREATE TABLE IF NOT EXISTS chat_assistant_log (
  id                BIGINT       NOT NULL PRIMARY KEY COMMENT '雪花ID',
  chat_session_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '会话ID;后端缺省时自生成,故不会为空',
  user_id           BIGINT       NULL COMMENT '用户ID;匿名对话为 NULL',
  kind              VARCHAR(32)  NOT NULL COMMENT 'CHAT=一次对话调用 / WRITE_CONFIRM=一次确认下单',
  confirmed_by_user TINYINT      NULL COMMENT 'NULL=不适用(非写事件) 1=用户显式确认 0=未经确认(事故)',
  proposal_id       VARCHAR(64)  NULL COMMENT '下单提案ID',
  order_no          VARCHAR(32)  NULL COMMENT '生成的订单号',
  result            VARCHAR(32)  NOT NULL DEFAULT 'OK' COMMENT 'OK/CONFLICT/EXPIRED/DISABLED/ERROR',
  latency_ms        INT          NULL COMMENT '服务端处理耗时(毫秒)',
  prompt_tokens     INT          NULL COMMENT 'LLM输入token;NULL=用量未知(**不是0**,0会被误读成免费)',
  completion_tokens INT          NULL COMMENT 'LLM输出token;NULL=用量未知(**不是0**)',
  cost_cents        BIGINT       NULL COMMENT '估算成本(分);NULL=单价未配置或用量未知',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_kind_created (kind, created_at),
  KEY idx_session (chat_session_id),
  KEY idx_created (created_at)
) ENGINE=InnoDB COMMENT '对话助手审计与用量日志';
