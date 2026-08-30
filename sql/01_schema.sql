-- =============================================================
-- 影院抢票选座系统 - 建库建表脚本
-- 执行: mysql -uroot -p < sql/01_schema.sql
-- 环境: MySQL 8.0+
-- =============================================================

CREATE DATABASE IF NOT EXISTS cinema DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cinema;

-- 影院
CREATE TABLE IF NOT EXISTS cinema (
  id        BIGINT       NOT NULL PRIMARY KEY,
  name      VARCHAR(64)  NOT NULL,
  address   VARCHAR(255) NOT NULL DEFAULT ''
) ENGINE = InnoDB COMMENT '影院';

-- 影厅
CREATE TABLE IF NOT EXISTS hall (
  id         BIGINT      NOT NULL PRIMARY KEY,
  cinema_id  BIGINT      NOT NULL,
  name       VARCHAR(64) NOT NULL,
  seat_rows  INT         NOT NULL COMMENT '行数',
  seat_cols  INT         NOT NULL COMMENT '列数',
  seat_count INT         NOT NULL COMMENT '座位总数(=rows*cols)',
  KEY idx_cinema (cinema_id)
) ENGINE = InnoDB COMMENT '影厅';

-- 座位 (seat_index 即 Redis Bitmap 的位序号 0~N-1)
CREATE TABLE IF NOT EXISTS seat (
  id         BIGINT      NOT NULL PRIMARY KEY,
  hall_id    BIGINT      NOT NULL,
  seat_index INT         NOT NULL COMMENT '座位序号,对应bitmap位',
  row_no     INT         NOT NULL COMMENT '第几行(1起)',
  col_no     INT         NOT NULL COMMENT '第几列(1起)',
  seat_type  TINYINT     NOT NULL DEFAULT 0 COMMENT '0普通 1VIP',
  x          INT         NOT NULL DEFAULT 0 COMMENT '渲染横坐标',
  y          INT         NOT NULL DEFAULT 0 COMMENT '渲染纵坐标',
  UNIQUE KEY uk_hall_seat (hall_id, seat_index)
) ENGINE = InnoDB COMMENT '座位';

-- 影片
CREATE TABLE IF NOT EXISTS movie (
  id          BIGINT       NOT NULL PRIMARY KEY,
  title       VARCHAR(128) NOT NULL,
  poster      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '海报URL,空则前端用占位图',
  duration    INT          NOT NULL COMMENT '时长(分钟)',
  description TEXT,
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0下架 1热映',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB COMMENT '影片';

-- 场次
CREATE TABLE IF NOT EXISTS `session` (
  id         BIGINT        NOT NULL PRIMARY KEY,
  movie_id   BIGINT        NOT NULL,
  hall_id    BIGINT        NOT NULL,
  start_time DATETIME      NOT NULL,
  end_time   DATETIME      NOT NULL,
  price      DECIMAL(10,2) NOT NULL COMMENT '基础票价(VIP座位加价逻辑见实现方案)',
  status     TINYINT       NOT NULL DEFAULT 1 COMMENT '0待开售 1在售 2已开场 3已结束',
  KEY idx_movie_time (movie_id, start_time),
  KEY idx_hall (hall_id)
) ENGINE = InnoDB COMMENT '场次';

-- 用户
CREATE TABLE IF NOT EXISTS `user` (
  id         BIGINT      NOT NULL PRIMARY KEY,
  username   VARCHAR(32) NOT NULL,
  password   VARCHAR(80) NOT NULL COMMENT 'BCrypt散列',
  nickname   VARCHAR(32) NOT NULL DEFAULT '',
  phone      VARCHAR(20) NOT NULL DEFAULT '',
  role       TINYINT     NOT NULL DEFAULT 0 COMMENT '0普通用户 1管理员',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '用户';

-- 订单
CREATE TABLE IF NOT EXISTS `order` (
  id           BIGINT        NOT NULL PRIMARY KEY COMMENT '雪花ID',
  order_no     VARCHAR(32)   NOT NULL COMMENT '业务单号(雪花)',
  user_id      BIGINT        NOT NULL,
  session_id   BIGINT        NOT NULL,
  status       TINYINT       NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
  total_amount DECIMAL(10,2) NOT NULL,
  seat_count   INT           NOT NULL,
  expire_at    DATETIME      NOT NULL COMMENT '支付截止时间',
  paid_at      DATETIME      NULL,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  -- 防重核心: 仅待支付订单参与唯一约束, 同用户同场次只允许一张待支付单
  pending_key  BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 0 THEN session_id END) STORED,
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_user_pending (user_id, pending_key),
  KEY idx_status_expire (status, expire_at) COMMENT '超时补偿扫描',
  KEY idx_user (user_id, created_at)
) ENGINE = InnoDB COMMENT '订单';

-- 订单座位明细 (锁座时即写入)
CREATE TABLE IF NOT EXISTS order_item (
  id          BIGINT        NOT NULL PRIMARY KEY,
  order_id    BIGINT        NOT NULL,
  session_id  BIGINT        NOT NULL,
  seat_index  INT           NOT NULL,
  price       DECIMAL(10,2) NOT NULL COMMENT '成交单价',
  UNIQUE KEY uk_order_seat (order_id, seat_index),
  KEY idx_session_seat (session_id, seat_index) COMMENT '座位图冷启动恢复/对账'
) ENGINE = InnoDB COMMENT '订单座位';
