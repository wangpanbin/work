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
