package com.cinema.infra.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 座位位图 Lua 脚本封装
 * <p>Phase B-④ 优化: 启动期通过 SCRIPT LOAD 把 3 个脚本预热到 Redis,
 * 后续业务调用由 Spring Data Redis 走 EVALSHA(若 Redis 重启脚本丢失,
 * Spring Data Redis 会自动 fallback EVAL, 不影响功能)。
 * <p>脚本经 DefaultRedisScript 预加载(首次执行后缓存于 Redis, 后续只传 SHA)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatLuaService {

    private static final DefaultRedisScript<String> LOCK_SCRIPT = script("lua/lock_seat.lua");
    private static final DefaultRedisScript<String> CONFIRM_SCRIPT = script("lua/confirm_seat.lua");
    private static final DefaultRedisScript<String> RELEASE_SCRIPT = script("lua/release_seat.lua");
    private static final DefaultRedisScript<String> REFUND_SCRIPT = script("lua/refund_seat.lua");
    private static final DefaultRedisScript<String> REPLACE_SCRIPT = script("lua/replace_seat_bitmap.lua");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void preloadScripts() {
        // 预热脚本到 Redis, 把 SHA 打到日志方便 MONITOR 验证
        preload(LOCK_SCRIPT, "lock_seat");
        preload(CONFIRM_SCRIPT, "confirm_seat");
        preload(RELEASE_SCRIPT, "release_seat");
        preload(REFUND_SCRIPT, "refund_seat");
        preload(REPLACE_SCRIPT, "replace_seat_bitmap");
    }

    private void preload(DefaultRedisScript<String> script, String name) {
        try {
            // 强制 lazy 计算 SHA, 与业务调用走相同的 SHA 计算路径
            String sha = script.getSha1();
            if (sha == null) {
                // 触发 ClassPathResource 读取 + SHA 计算
                script.getScriptAsString();
                sha = script.getSha1();
            }
            // 把脚本内容显式 SCRIPT LOAD 一次, Redis 端缓存, 后续业务走 EVALSHA
            String body = script.getScriptAsString();
            String loadedSha = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) conn ->
                    conn.scriptingCommands().scriptLoad(body.getBytes(StandardCharsets.UTF_8)));
            log.info("[SeatLua] 预热脚本 {} → Redis SHA={} 本地 SHA={} bytes={}",
                    name, loadedSha, sha, body.length());
        } catch (Exception e) {
            // 预热失败不影响启动, 首次业务调用 Spring Data Redis 会自动 fallback EVAL
            log.warn("[SeatLua] 预热脚本 {} 失败 err={}", name, e.toString());
        }
    }

    /**
     * 原子锁座: 冲突返回 conflict 列表, 成功返回空列表。
     *
     * <p>对应 {@code lock_seat.lua} —— 先查 sold+lock 冲突, 再原子置 lock 位。
     */
    public LuaLockResult lock(Long sessionId, List<Integer> seats) {
        String json = redisTemplate.execute(LOCK_SCRIPT, keys(sessionId), args(seats));
        return parse(json);
    }

    /**
     * 支付确认: 置 sold 并清 lock, 返回实际置为 sold 的座位。
     *
     * <p>对应 {@code confirm_seat.lua}。
     */
    public List<Integer> confirmPaid(Long sessionId, List<Integer> seats) {
        String json = redisTemplate.execute(CONFIRM_SCRIPT, keys(sessionId), args(seats));
        return parseList(json);
    }

    /**
     * 释放**未支付**订单的座位: 只清 lock 位, 返回实际释放的座位。
     *
     * <p>对应 {@code release_seat.lua} —— 已售座位一个位都不动(sold==0 才清)。
     *
     * <p>⚠️ 已支付订单的座位**绝不能**走这里: 退票必须用 {@link #refundSold}。
     * 类型 {@link SeatWrite.Unpaid} 就是为了在编译期挡住这件事。
     */
    public List<Integer> releaseUnpaid(SeatWrite.Unpaid target) {
        String json = redisTemplate.execute(RELEASE_SCRIPT, keys(target.sessionId()),
                args(target.seats()));
        return parseList(json);
    }

    /**
     * 退票释放**已售**座位: <b>同时清 sold + lock</b>, 让已售座位真正回到可选池。
     *
     * <p>对应 {@code refund_seat.lua}。
     *
     * <p>E2E 2026-09-29 P1-1: 退票原先走释放路径, 而该脚本只清"未售出的锁定位"
     * (sold==1 时一个位都不动) → 退票成功后座位在位图里仍是已售, 刷新后依然灰着不可选。
     * 已支付订单的座位必须用这个方法, 待支付单的取消/超时关单才用 {@link #releaseUnpaid}。
     *
     * <p><b>按订单幂等</b>(2026-10-24): 脚本带第三个 KEYS = 释放标记
     * ({@code cinema:order:seat-released:{orderNo}})。标记已存在 = 本单座位已释放过,
     * 脚本直接返回空列表, 不再碰任何位。这一条挡住的是**超卖**:
     * 退票主流程中途中断 → 补偿任务二次释放 → 座位若已被他人买走,
     * 第二次无条件清位会把别人已付款的座位清成可选(同一座位两张 PAID 订单)。
     *
     * @return 实际发生变化的座位(幂等:座位已是可选、或本单已释放过时返回空列表)
     */
    public List<Integer> refundSold(SeatWrite.Paid target) {
        String json = redisTemplate.execute(REFUND_SCRIPT,
                refundKeys(target.sessionId(), target.orderNo()),
                refundArgs(target.seats()));
        return parseList(json);
    }

    /**
     * 退票脚本的 KEYS: (lock, sold, 按订单幂等标记)。
     *
     * <p>顺序固定, 与 {@code refund_seat.lua} 的 KEYS[1..3] 一一对应。
     */
    private static List<String> refundKeys(Long sessionId, String orderNo) {
        return List.of(RedisKeys.sessionLock(sessionId), RedisKeys.sessionSold(sessionId),
                RedisKeys.orderSeatReleased(orderNo));
    }

    /**
     * 退票脚本的 ARGV: [标记 TTL 秒, seat1..seatN]。
     */
    private static Object[] refundArgs(List<Integer> seats) {
        Object[] argv = new Object[1 + seats.size()];
        argv[0] = String.valueOf(RedisKeys.SEAT_RELEASED_TTL_SECONDS);
        for (int i = 0; i < seats.size(); i++) {
            argv[1 + i] = String.valueOf(seats.get(i));
        }
        return argv;
    }

    /**
     * **原子替换**场次座位位图(2026-10-25)。
     *
     * <p>对应 {@code replace_seat_bitmap.lua} —— 位图先建在**临时 key** 上, 建好后再 RENAME
     * 覆盖正式 key。
     *
     * <p><b>为什么不直接「先删后建」</b>: 删除与重建是两次 Redis 调用, 中间正式位图是空的,
     * 此刻任何一次锁座都会看到「座位可选」→ 已付款的座位被再次卖出 = 超卖; 而重建本身
     * 还可能失败(Redis 抖动 / OOM), 失败后位图就永久停在「空」状态。
     *
     * <p><b>为什么这样就安全</b>: 换名之前正式位图原封不动 —— 重建失败 = 座位保持
     * 「已锁/已售」(宁可锁死也不误放); 换名在同一个 Lua 脚本里完成, 脚本执行期间不会有
     * 其他客户端的命令插进来, 不存在「换了一半」的中间态。
     *
     * @param sessionId 场次 id
     * @param lockSeats  待支付订单占用的座位
     * @param soldSeats 已售订单占用的座位
     * @return 重建后的位数 (lock, sold)
     */
    public RecoverResult replaceSeats(Long sessionId, List<Integer> lockSeats, List<Integer> soldSeats) {
        String tmpLock = RedisKeys.sessionLockRebuild(sessionId);
        String tmpSold = RedisKeys.sessionSoldRebuild(sessionId);
        // 拼 ARGV: [N, lock1..lockN, M, sold1..soldM] —— 与 recover_seat_bitmap.lua 同一套布局
        Object[] argv = new Object[2 + lockSeats.size() + soldSeats.size()];
        argv[0] = String.valueOf(lockSeats.size());
        for (int i = 0; i < lockSeats.size(); i++) argv[1 + i] = String.valueOf(lockSeats.get(i));
        argv[1 + lockSeats.size()] = String.valueOf(soldSeats.size());
        for (int j = 0; j < soldSeats.size(); j++) {
            argv[2 + lockSeats.size() + j] = String.valueOf(soldSeats.get(j));
        }
        String json;
        try {
            json = redisTemplate.execute(REPLACE_SCRIPT,
                    List.of(tmpLock, tmpSold, RedisKeys.sessionLock(sessionId), RedisKeys.sessionSold(sessionId)),
                    argv);
        } catch (Exception e) {
            // 脚本失败 → 正式位图没被动过(这是本方法存在的全部意义), 只清掉临时 key。
            cleanupRebuildKeys(tmpLock, tmpSold);
            throw e;
        }
        try {
            return objectMapper.readValue(json, RecoverResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 位图替换结果解析失败: " + json, e);
        }
    }

    /**
     * 清理重建残留的临时 key —— 只删临时 key, 正式位图一个字节都不碰。
     */
    private void cleanupRebuildKeys(String tmpLock, String tmpSold) {
        try {
            redisTemplate.delete(List.of(tmpLock, tmpSold));
        } catch (Exception e) {
            log.warn("[SeatLua] 清理位图重建临时 key 失败 tmpLock={} tmpSold={} err={}",
                    tmpLock, tmpSold, e.toString());
        }
    }

    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(location));
        s.setResultType(String.class);
        return s;
    }

    private static Object[] args(List<Integer> seats) {
        return seats.stream().map(String::valueOf).toArray();
    }

    /**
     * 位图 key 对 —— 拼 key 这件事沉到接缝之后, 调用方不再自己拼。
     *
     * <p>顺序固定为 (lock, sold), 与三个 Lua 脚本的 KEYS[1]/KEYS[2] 一一对应。
     */
    private static List<String> keys(Long sessionId) {
        return List.of(RedisKeys.sessionLock(sessionId), RedisKeys.sessionSold(sessionId));
    }

    private LuaLockResult parse(String json) {
        try {
            return objectMapper.readValue(json, LuaLockResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 锁座结果解析失败: " + json, e);
        }
    }

    private List<Integer> parseList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Lua 座位结果解析失败: " + json, e);
        }
    }
}
