-- ============================================================
-- 04_extra_demo_data.sql - 演示数据增量(影片海报 + 多日场次)
-- 兼容: 可在已有数据基础上幂等执行, 重跑不会重复
--
-- 执行: mysql -uroot -p --default-character-set=utf8mb4 < sql/04_extra_demo_data.sql
--   (必须带 --default-character-set=utf8mb4, 否则片名等中文会被双重编码成乱码)
--
-- 海报来源: TMDB (https://www.themoviedb.org/) 公开海报 CDN
--   * 链接: image.tmdb.org/t/p/w500/<poster_path>.jpg
--   * 许可: TMDB 公开海报, 仅用于课程演示, 商用请使用正版海报源
--   * 角色: 给前端 Home.vue / MovieDetail.vue 提供真实可加载的海报 URL,
--           否则会显示 poster-fallback 占位(只是片名)
--
-- 场次生成规则:
--   * 日期:  CURDATE()+1 起, 共 14 天
--   * 时段:  10:00 / 14:30 / 19:30 / 22:00 (新增 22:00 夜场)
--   * 影厅:  1 号激光厅 (hall 1) 与 2 号 IMAX 厅 (hall 2) 轮转
--   * 影片:  所有 status=1 的影片, 每个 (日期 × 时段) 各排一场
--   * ID 规则: movie_id * 1e7 + hall_id * 1e5 + day * 100 + slot
--     与 02_init_data.sql 中的 1..9 及已有雪花 ID 均不会冲突
--   * 幂等:  INSERT IGNORE (主键 id 重复自动跳过)
-- ============================================================

USE cinema;

-- ============================================================
-- 1) 新增影片 (海报来自 TMDB 公开 CDN)
-- ============================================================
INSERT IGNORE INTO movie (id, title, poster, duration, description, status, genre, region, release_date) VALUES
(101, '流浪地球2',  'https://image.tmdb.org/t/p/w500/hEA7bpWw5IRKOW2MVjvx46SWevU.jpg', 173,
     '太阳危机来袭,人类启动"移山计划",用一万座发动机推动地球踏上流浪之旅。',
     1, '科幻', '中国大陆', '2023-01-22'),
(102, '哪吒之魔童降世', 'https://image.tmdb.org/t/p/w500/phM9bb6s9c60LA8qwsdk7U1N2cS.jpg', 110,
     '魔丸转世,陈塘关少年哪吒在偏见与命运中逆天改命。',
     1, '动画', '中国大陆', '2019-07-26'),
(103, '满江红',     'https://image.tmdb.org/t/p/w500/kOoxkXTYTi4OipM5G97gK9jnYIk.jpg',  157,
     '南宋绍兴年间,小兵张大与亲兵营副统领孙均被卷入一场朝堂风云。',
     1, '悬疑', '中国大陆', '2023-01-22'),
(104, '长安三万里',  'https://image.tmdb.org/t/p/w500/ltWuj0bEhITpiNYaTmpc2lUKy90.jpg',  168,
     '安史之乱后,高适回忆与诗仙李白跨越数十载的跌宕人生。',
     1, '动画', '中国大陆', '2023-07-08'),
(105, '孤注一掷',    'https://image.tmdb.org/t/p/w500/b3DBoHSGPIlqZclfZc6lHcVvGPz.jpg',  130,
     '程序员潘生与模特安娜被海外高薪骗局诱入境外诈骗园区。',
     1, '剧情', '中国大陆', '2023-08-08'),
(106, '热辣滚烫',    'https://image.tmdb.org/t/p/w500/eD1mfjEeDUOAAsptZjQA6skrdJy.jpg',  129,
     '宅家多年的乐莹走出舒适圈,在拳击场上重新定义自己。',
     1, '喜剧', '中国大陆', '2024-02-10'),
(107, '消失的她',    'https://image.tmdb.org/t/p/w500/oJ0X8ULclI1fQUXj14VcUFCjvXo.jpg',  122,
     '何非的妻子在周年旅行中离奇消失,重逢的她竟不再是同一个人。',
     1, '悬疑', '中国大陆', '2023-06-22'),
(108, '封神第一部',  'https://image.tmdb.org/t/p/w500/6VOV2wU3WEyN0H1QtrbAS6whXPc.jpg',  148,
     '商末周初,质子姬发在朝歌风云中寻找自我与天下苍生之路。',
     1, '动作', '中国大陆', '2023-07-20'),
(109, '周处除三害',  'https://image.tmdb.org/t/p/w500/9FhZ9VC999qeOWH2ytbangUMMt4.jpg',  134,
     '通缉犯陈桂林决意除掉排行前两位的恶人,自我了断前最后一段救赎。',
     1, '动作', '中国台湾',  '2023-10-13'),
(110, '沙丘: 第二部', 'https://image.tmdb.org/t/p/w500/6izwz7rsy95ARzTR3poZ8H6c5pp.jpg',  166,
     '保罗·亚崔迪携手弗雷曼人,在厄拉科斯沙漠中掀起复仇风暴。',
     1, '科幻', '美国',     '2024-03-01'),
(111, '奥本海默',    'https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg',  180,
     '二战期间,理论物理学家 J. Robert Oppenheimer 主导曼哈顿计划,创造毁灭性武器。',
     1, '剧情', '美国',     '2023-07-21'),
(112, '芭比',        'https://image.tmdb.org/t/p/w500/iuFNMS8U5cb6xfzi51Dbkovj7vM.jpg',  114,
     '芭比与肯离开完美芭比乐园,踏入真实世界后引发的爆笑思考。',
     1, '喜剧', '美国',     '2023-07-21');

-- ============================================================
-- 2) 为原有 3 部影片也补上海报, 便于演示 (UPDATE, 重跑幂等)
-- ============================================================
UPDATE movie SET poster = 'https://image.tmdb.org/t/p/w500/hEA7bpWw5IRKOW2MVjvx46SWevU.jpg' WHERE id = 1 AND poster = '';
-- 注: 流浪地球3 尚未上映, 此处借用《流浪地球2》海报作为占位
UPDATE movie SET poster = 'https://image.tmdb.org/t/p/w500/6izwz7rsy95ARzTR3poZ8H6c5pp.jpg' WHERE id = 2 AND poster = '';
-- 注: 沙丘3 尚未上映, 此处借用《沙丘2》海报作为占位
UPDATE movie SET poster = 'https://image.tmdb.org/t/p/w500/phM9bb6s9c60LA8qwsdk7U1N2cS.jpg' WHERE id = 3 AND poster = '';
-- 注: 深海奇航 (虚构影片) 借用《哪吒之魔童降世》海报作为占位

-- ============================================================
-- 3) 生成未来 14 天的场次
--    * 影片: 所有 status=1 且 id<1000 的影片 (避开雪花 ID 防 BIGINT 溢出)
--    * 影厅: hall 1 / hall 2 (有 seed 座位数据)
--    * 时段: 4 档 (10:00 / 14:30 / 19:30 / 22:00)
--    * 价格: 早场 39.9, 下午 49.9, 晚场 59.9, 夜场 45.9
-- ============================================================
DELIMITER $$
DROP PROCEDURE IF EXISTS gen_extra_sessions $$
CREATE PROCEDURE gen_extra_sessions()
BEGIN
  DECLARE v_movie BIGINT DEFAULT 0;
  DECLARE v_hall  BIGINT DEFAULT 0;
  DECLARE v_dur   INT    DEFAULT 0;
  DECLARE v_start DATETIME;
  DECLARE v_end   DATETIME;
  DECLARE v_price DECIMAL(10,2);
  DECLARE v_id    BIGINT;
  DECLARE v_done  INT DEFAULT 0;

  DECLARE d INT DEFAULT 1;   -- day offset (1..14)
  DECLARE s INT DEFAULT 1;   -- slot index (1..4)

  -- 只为演示影片生成场次 (id 小于 1000, 避开雪花 ID; 否则 10000000 倍数会溢出 BIGINT)
  DECLARE cur CURSOR FOR
    SELECT id, duration FROM movie WHERE status = 1 AND id < 1000 ORDER BY id;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  OPEN cur;
  movie_loop: WHILE v_done = 0 DO
    FETCH cur INTO v_movie, v_dur;
    IF v_done = 1 THEN LEAVE movie_loop; END IF;

    SET d = 1;
    day_loop: WHILE d <= 14 DO
      SET s = 1;
      slot_loop: WHILE s <= 4 DO
        -- 1 号厅与 2 号厅轮转: 按 (movie + day + slot) 奇偶分配
        SET v_hall = IF(MOD(v_movie + d + s, 2) = 0, 1, 2);

        SET v_start = TIMESTAMP(
          DATE_ADD(CURDATE(), INTERVAL d DAY),
          ELT(s, '10:00:00', '14:30:00', '19:30:00', '22:00:00')
        );
        SET v_end   = DATE_ADD(v_start, INTERVAL v_dur MINUTE);
        SET v_price = ELT(s, 39.90, 49.90, 59.90, 45.90);

        -- 确定性 ID: 与 02 脚本的 1..9 及雪花 ID 均不冲突
        SET v_id = v_movie * 10000000 + v_hall * 100000 + d * 100 + s;

        INSERT IGNORE INTO `session`
          (id, movie_id, hall_id, start_time, end_time, price, status)
          VALUES (v_id, v_movie, v_hall, v_start, v_end, v_price, 1);

        SET s = s + 1;
      END WHILE slot_loop;
      SET d = d + 1;
    END WHILE day_loop;
  END WHILE movie_loop;
  CLOSE cur;
END $$
DELIMITER ;

CALL gen_extra_sessions();
DROP PROCEDURE gen_extra_sessions;

-- ============================================================
-- 4) 校验
-- ============================================================
SELECT 'movie'   AS t, COUNT(*) AS cnt FROM movie
UNION ALL SELECT 'session', COUNT(*) FROM `session`;

-- 抽样展示 14 天场次覆盖情况
SELECT s.id, m.title, h.name AS hall, s.start_time, s.price
  FROM `session` s
  JOIN movie m ON m.id = s.movie_id
  JOIN hall  h ON h.id = s.hall_id
 WHERE s.start_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY)
   AND s.start_time <  DATE_ADD(CURDATE(), INTERVAL 4 DAY)
 ORDER BY s.start_time, s.hall_id
 LIMIT 24;