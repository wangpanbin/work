-- ============================================================
-- P0 增量 - 数据库变更补丁
-- 兼容: 已运行 01+02 的库可幂等执行
-- ============================================================

USE cinema;

-- F1: 电影扩展字段
ALTER TABLE movie
  ADD COLUMN genre        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '类型',
  ADD COLUMN region       VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '地区',
  ADD COLUMN release_date DATE         NULL COMMENT '上映日期',
  ADD INDEX idx_genre (genre),
  ADD INDEX idx_release (release_date);

-- 更新演示数据(类型/地区/上映日期)
UPDATE movie SET genre='科幻', region='中国', release_date='2026-07-15' WHERE id=1;
UPDATE movie SET genre='科幻', region='美国', release_date='2026-08-20' WHERE id=2;
UPDATE movie SET genre='动画', region='中国', release_date='2026-09-01' WHERE id=3;

-- N1: 订单表新增 refunded_at
ALTER TABLE `order`
  ADD COLUMN refunded_at DATETIME NULL COMMENT '退款完成时间' AFTER paid_at,
  ADD KEY idx_status_paid (status, paid_at) COMMENT '看板:按 status+paid_at 聚合';

-- N1: 退款日志
CREATE TABLE IF NOT EXISTS refund_log (
  id          BIGINT        NOT NULL PRIMARY KEY COMMENT '雪花ID',
  order_no    VARCHAR(32)   NOT NULL,
  user_id     BIGINT        NOT NULL,
  amount      DECIMAL(10,2) NOT NULL,
  status      TINYINT       NOT NULL DEFAULT 0 COMMENT '0成功 1失败',
  reason      VARCHAR(255)  NOT NULL DEFAULT '',
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_no),
  KEY idx_user (user_id, created_at)
) ENGINE=InnoDB COMMENT '退款日志';

-- N2: 电子票
CREATE TABLE IF NOT EXISTS ticket (
  order_no    VARCHAR(32)  NOT NULL PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  session_id  BIGINT       NOT NULL,
  payload     VARCHAR(512) NOT NULL,
  sig         VARCHAR(128) NOT NULL,
  exp_at      DATETIME     NOT NULL,
  verified_at DATETIME     NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id, created_at),
  KEY idx_session (session_id)
) ENGINE=InnoDB COMMENT '电子票';
