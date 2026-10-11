-- ============================================================
-- 05_refresh_sessions_for_demo.sql - 课堂演示用场次补种(今天起 3 天)
--
-- 执行: mysql -uroot -p --default-character-set=utf8mb4 < sql/05_refresh_sessions_for_demo.sql
--   (必须带 --default-character-set=utf8mb4, 否则中文片名会被双重编码成乱码)
--
-- 用途:
--   cinema-web/src/views/MovieDetail.vue 的日期选择器只提供
--   "今天 / 明天 / 后天" 三个选项 (dateOptions 用 dayjs().add(0|1|2,'day')),
--   且 selectedDate 默认选中明天。演示前若这三天没有场次,
--   点进任何影片都是 el-empty "暂无场次", 核心秒杀选座链路无法演示。
--   本脚本把这三天补齐。
--
-- 为什么不能直接重跑 04_extra_demo_data.sql:
--   04 的确定性 ID = movie_id*1e7 + hall_id*1e5 + d*100 + s,
--   其中 d 是**相对天数**(1..14), 不含绝对日期。已执行过一次后,
--   9-08 那批已占用 d=0..13 的全部 ID; 隔天重跑时 d=0 会再次算出
--   同一组 ID, 撞上历史数据, 被 04 里的 INSERT IGNORE **静默跳过** —
--   脚本返回成功, 实际一行新数据都没插。这是最危险的失败模式:
--   执行时看不出问题, 到演示当天才暴露"暂无场次"。
--
-- 本脚本的 ID 规则(绝对日期编码, 真幂等):
--   id = movie_id * 1e7 + hall_id * 1e5 + DAYOFMONTH * 100 + slot
--     * DAYOFMONTH ∈ [1,31] → 乘 100 后占 4 位,
--       11 日 slot 1 = 1101, 12 日 slot 2 = 1202
--     * slot ∈ [1,4] 对应 10:00 / 14:30 / 19:30 / 22:00
--   跨天执行永远不撞(日期不同 → ID 不同);
--   同一天重跑 ID 完全相同 → INSERT IGNORE 全部跳过 → 结果一致。
--
-- 场次生成规则:
--   * 日期:  今天(CURDATE()) 起, 共 3 天 (今天/明天/后天)
--   * 时段:  10:00 / 14:30 / 19:30 / 22:00
--   * 影厅:  hall 1 (1号激光厅, 96 座) 与 hall 2 (2号IMAX厅, 140 座) 轮转
--     —— 只用这两个厅, 它们在 seat 表里有种子座位数据;
--        另外两个厅是雪花 ID 的测试厅, 不纳入演示排片
--   * 影片:  所有 status=1 且 id<1000 的影片 (避开雪花 ID 防 BIGINT 溢出),
--           与 04_extra_demo_data.sql 的筛选一致
--   * 价格:  早 39.90 / 午 49.90 / 晚 59.90 / 夜 45.90 (与 04 一致)
--
-- 预期规模: 15 部影片 × 3 天 × 4 时段 = 180 个场次
--   (若先执行了 sql/07_real_demo_movies.sql 新增 5 部影片, 则为 20 部 × 12 = 240 个场次)
--
-- 关于座位图(无需预热):
--   SeatService.seatMap 用 readWithPadding(key, seatCount) 读位图,
--   新场次在 Redis 里没有对应 key 时返回全 0, 即"全部可选"。
--   所以插完库直接就能选座, 不需要任何 Redis 预热动作。
--
-- 回滚(修正 2026-10-11):
--   本脚本的 id = movie_id*1e7 + hall_id*1e5 + DAYOFMONTH*100 + slot,
--   且只取 status=1 且 id<1000 的影片, 因此 id 实际落在 10000000 .. 9990203104
--   (上界 = 999*1e7 + 2*1e5 + 31*100 + 4 = 9990203104)。
--
--   原注释写的 "10000000..11299999" 只有 8 位, 只能盖住 movie_id=1(10,000,000);
--   101-112 的场次是 1,010,000,000(10 位), 全部落在上界之外 ——
--   照原区间 DELETE 等于一条都没删掉, 回滚形同虚设。
--
--   注意: 04_extra_demo_data.sql 的场次也在同一数值空间(区别是它用相对天数 d=1..14,
--   不是 DAYOFMONTH)。若 04 是最近几天内跑的, 下面的时间窗口会连它一起删。
--   需要精确回滚时, 按上面的 id 公式手工列出本脚本插入的 id 再删。
--   另外 02_init_data.sql 的场次 id 是 1..9 的顺序号, 不在这个区间内, 不会被误伤。
--
--   DELETE FROM `session`
--    WHERE id BETWEEN 10000000 AND 9990203104
--      AND start_time >= CURDATE() AND start_time < DATE_ADD(CURDATE(), INTERVAL 3 DAY);
-- ============================================================

USE cinema;

SET NAMES utf8mb4;

-- ============================================================
-- 1) 单条场次插入工具: duration 查表 + end_time 计算 + INSERT IGNORE
--    (自包含定义, 不依赖 04 的同名过程, 可独立执行)
-- ============================================================
DELIMITER $$
DROP PROCEDURE IF EXISTS insert_demo_session $$
CREATE PROCEDURE insert_demo_session(
  IN p_movie BIGINT,
  IN p_hall  BIGINT,
  IN p_start DATETIME,
  IN p_price DECIMAL(10,2),
  IN p_id    BIGINT
)
BEGIN
  DECLARE v_dur INT;
  SELECT duration INTO v_dur FROM movie WHERE id = p_movie;
  INSERT IGNORE INTO `session` (id, movie_id, hall_id, start_time, end_time, price, status)
  VALUES (p_id, p_movie, p_hall, p_start,
          DATE_ADD(p_start, INTERVAL v_dur MINUTE), p_price, 1);
END $$
DELIMITER ;

-- ============================================================
-- 2) 生成今天/明天/后天 共 3 天的场次
-- ============================================================
DELIMITER $$
DROP PROCEDURE IF EXISTS gen_demo_sessions $$
CREATE PROCEDURE gen_demo_sessions()
BEGIN
  DECLARE v_movie BIGINT;
  DECLARE v_hall  BIGINT;
  DECLARE d, s, v_dom INT;
  DECLARE v_start DATETIME;
  DECLARE v_price DECIMAL(10,2);
  DECLARE v_done  INT DEFAULT 0;
  DECLARE cur CURSOR FOR
    SELECT id FROM movie WHERE status = 1 AND id < 1000 ORDER BY id;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  OPEN cur;
  movie_loop: WHILE v_done = 0 DO
    FETCH cur INTO v_movie;
    IF v_done = 1 THEN LEAVE movie_loop; END IF;

    SET d = 0;
    day_loop: WHILE d <= 2 DO
      SET v_dom = DAYOFMONTH(DATE_ADD(CURDATE(), INTERVAL d DAY));

      SET s = 1;
      slot_loop: WHILE s <= 4 DO
        -- 1 号厅与 2 号厅轮转: 按 (movie + day + slot) 奇偶分配
        SET v_hall = IF(MOD(v_movie + d + s, 2) = 0, 1, 2);

        SET v_start = TIMESTAMP(
          DATE_ADD(CURDATE(), INTERVAL d DAY),
          ELT(s, '10:00:00', '14:30:00', '19:30:00', '22:00:00')
        );
        SET v_price = ELT(s, 39.90, 49.90, 59.90, 45.90);

        -- 绝对日期编码的确定性 ID(见文件头说明): 跨天不冲突, 同天真幂等
        SET @sid = v_movie * 10000000 + v_hall * 100000 + v_dom * 100 + s;

        CALL insert_demo_session(v_movie, v_hall, v_start, v_price, @sid);

        SET s = s + 1;
      END WHILE slot_loop;
      SET d = d + 1;
    END WHILE day_loop;
  END WHILE movie_loop;
  CLOSE cur;
END $$
DELIMITER ;

CALL gen_demo_sessions();
DROP PROCEDURE gen_demo_sessions;
DROP PROCEDURE insert_demo_session;

-- ============================================================
-- 3) 校验 —— 预期输出 3 行, 每天 60 个场次
-- ============================================================
SELECT DATE(start_time) AS d, COUNT(*) AS cnt
  FROM `session`
 WHERE start_time >= CURDATE()
   AND start_time <  DATE_ADD(CURDATE(), INTERVAL 3 DAY)
 GROUP BY d
 ORDER BY d;

-- 按影片抽样, 确认每部片都排到了 3 天
SELECT m.title, COUNT(*) AS cnt, MIN(s.start_time) AS first_show
  FROM `session` s
  JOIN movie m ON m.id = s.movie_id
 WHERE s.start_time >= CURDATE()
   AND s.start_time <  DATE_ADD(CURDATE(), INTERVAL 3 DAY)
 GROUP BY m.id, m.title
 ORDER BY cnt DESC, m.id;