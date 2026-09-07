-- =============================================================
-- 影院抢票选座系统 - 演示数据
-- 执行(在 01_schema.sql 之后): mysql -uroot -p < sql/02_init_data.sql
-- 说明: 测试账号 user1/user2 的密码由后端 DataInitializer 启动时
--       重置为 123456 (BCrypt), 此处仅占位。
-- =============================================================

USE cinema;

-- 影院
INSERT INTO cinema (id, name, address) VALUES
(1, '星辉影城(高新店)', 'demo市高新区科技路88号');

-- 影厅
INSERT INTO hall (id, cinema_id, name, seat_rows, seat_cols, seat_count) VALUES
(1, 1, '1号激光厅',   8, 12,  96),
(2, 1, '2号IMAX厅',  10, 14, 140);

-- 座位(含VIP区: 后排)
DELIMITER $$
DROP PROCEDURE IF EXISTS gen_seats $$
CREATE PROCEDURE gen_seats(IN p_hall_id BIGINT, IN p_rows INT, IN p_cols INT, IN p_vip_from_row INT)
BEGIN
  DECLARE r INT DEFAULT 1;
  DECLARE c INT DEFAULT 1;
  WHILE r <= p_rows DO
    SET c = 1;
    WHILE c <= p_cols DO
      INSERT INTO seat (id, hall_id, seat_index, row_no, col_no, seat_type, x, y)
      VALUES (p_hall_id * 100000 + (r - 1) * p_cols + (c - 1),
              p_hall_id, (r - 1) * p_cols + (c - 1), r, c,
              IF(r >= p_vip_from_row, 1, 0),
              c * 40, r * 40);
      SET c = c + 1;
    END WHILE;
    SET r = r + 1;
  END WHILE;
END $$
DELIMITER ;

CALL gen_seats(1, 8, 12, 7);
CALL gen_seats(2, 10, 14, 8);
DROP PROCEDURE gen_seats;

-- 影片
INSERT INTO movie (id, title, poster, duration, description, status) VALUES
(1, '流浪地球3',   '', 150, '太阳即将毁灭,人类启动流浪地球计划第三阶段。', 1),
(2, '沙丘3',       '', 166, '厄拉科斯的沙暴再起,预言之子迎来终局。',       1),
(3, '深海奇航',    '', 118, '一场潜入马里亚纳海沟的奇幻冒险。',             1);

-- 场次: 明天起3天 x 每天3个时段(10:00/14:30/19:30), 影片与影厅轮转
-- 说明: insert_session_at 是单条场次的插入工具 (duration 查表 + end_time 计算 + INSERT),
--       gen_sessions / gen_extra_sessions (sql/04_extra_demo_data.sql) 都调用它,
--       避免重复实现 duration→end_time 的核心逻辑。
DELIMITER $$
DROP PROCEDURE IF EXISTS insert_session_at $$
CREATE PROCEDURE insert_session_at(
  IN p_movie BIGINT,
  IN p_hall  BIGINT,
  IN p_start DATETIME,
  IN p_price DECIMAL(10,2),
  IN p_id    BIGINT
)
BEGIN
  DECLARE v_dur INT;
  SELECT duration INTO v_dur FROM movie WHERE id = p_movie;
  -- 用 INSERT IGNORE: 与 gen_extra_sessions (sql/04) 保持一致,
  -- 也让 02 在重复执行时不会因 ID 冲突报错 (仅静默跳过)。
  INSERT IGNORE INTO `session` (id, movie_id, hall_id, start_time, end_time, price, status)
  VALUES (p_id, p_movie, p_hall, p_start,
          DATE_ADD(p_start, INTERVAL v_dur MINUTE), p_price, 1);
END $$

DROP PROCEDURE IF EXISTS gen_sessions $$
CREATE PROCEDURE gen_sessions()
BEGIN
  DECLARE d INT DEFAULT 1;
  DECLARE s INT DEFAULT 0;
  DECLARE v_id INT DEFAULT 0;
  DECLARE v_start DATETIME;
  DECLARE v_movie BIGINT;
  DECLARE v_hall  BIGINT;
  DECLARE v_price DECIMAL(10,2);
  WHILE d <= 3 DO
    SET s = 0;
    WHILE s <= 2 DO
      SET v_movie = MOD(s, 3) + 1;
      SET v_hall  = IF(MOD(d + s, 2) = 0, 1, 2);
      SET v_start = TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d DAY),
                              ELT(s + 1, '10:00:00', '14:30:00', '19:30:00'));
      SET v_price = ELT(s + 1, 39.90, 49.90, 59.90);
      SET v_id = v_id + 1;
      CALL insert_session_at(v_movie, v_hall, v_start, v_price, v_id);
      SET s = s + 1;
    END WHILE;
    SET d = d + 1;
  END WHILE;
END $$
DELIMITER ;

CALL gen_sessions();
DROP PROCEDURE gen_sessions;
DROP PROCEDURE insert_session_at;

-- 测试用户(密码由后端启动时初始化为 123456)
INSERT INTO `user` (id, username, password, nickname, phone) VALUES
(1, 'user1', 'DEV_INIT_RUNTIME', '测试用户1', '13800000001'),
(2, 'user2', 'DEV_INIT_RUNTIME', '测试用户2', '13800000002');

-- 校验
SELECT 'hall'   AS t, COUNT(*) AS cnt FROM hall
UNION ALL SELECT 'seat',    COUNT(*) FROM seat
UNION ALL SELECT 'movie',   COUNT(*) FROM movie
UNION ALL SELECT 'session', COUNT(*) FROM `session`
UNION ALL SELECT 'user',    COUNT(*) FROM `user`;
