package com.cinema.modules.chat.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.service.OrderLockService;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.seat.service.SeatService;
import com.cinema.modules.seat.vo.SeatMapVO;
import com.cinema.modules.session.entity.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 下单提案服务 —— 对话助手「一键下单」的服务端快照管理(ADR-0004)。
 *
 * <p><b>职责边界</b>:
 * <ul>
 *   <li><b>建提案</b>:重读实时位图 → 选座 → 按 session 表算价 → 存 Redis(TTL)。</li>
 *   <li><b>消费提案</b>:读出即删,归属校验后交给上层去锁座。</li>
 * </ul>
 *
 * <p><b>提案不占位</b>:TTL 期间座位不保证可用。占位只发生在
 * {@code OrderLockService#lockSeats} 那一刻(见 {@link OrderProposal} 的说明)。
 *
 * <p><b>座位以服务端为准</b>(ADR-0004 决策 D8):模型给的座位只是「提示」,
 * 必须逐个通过「在范围内 + 当前可选 + 同行连座」三重校验才被采纳;
 * 任一条不满足就由服务端重读位图重新找一组。模型的报价一律丢弃。
 */
@Slf4j
@Service
public class OrderProposalService {

    /** 单次最多几个座位 —— 与 {@code LockSeatsDTO @Size(max=4)} 和前端 maxSelect=4 一致。 */
    public static final int MAX_SEATS = 4;

    /** 影院业务时区(与 {@code ChatAssistantService} 一致),用于把订单的 LocalDateTime 转 epoch millis。 */
    private static final ZoneId CINEMA_ZONE = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderCore orderCore;
    private final SeatService seatService;
    private final SeatMapper seatMapper;
    private final OrderLockService orderLockService;
    private final long ttlSeconds;

    public OrderProposalService(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                OrderCore orderCore,
                                SeatService seatService,
                                SeatMapper seatMapper,
                                OrderLockService orderLockService,
                                @Value("${cinema.chat.proposal-ttl-seconds:90}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.orderCore = orderCore;
        this.seatService = seatService;
        this.seatMapper = seatMapper;
        this.orderLockService = orderLockService;
        this.ttlSeconds = ttlSeconds;
    }

    // =================================================================
    // 建提案
    // =================================================================

    /**
     * 建一份下单提案(服务端核验过的座位 + 价格 + 归属 + 存量待支付单提示)。
     *
     * @param userId        归属用户(服务端登录态,不是模型给的)
     * @param sessionId     目标场次
     * @param count         模型给出的张数;{@code preferredSeats} 非空时以座位数为准
     * @param preferredSeats 模型给的座位提示,可空;不合法时由服务端重找
     */
    public OrderProposal createProposal(Long userId, Long sessionId, Integer count,
                                        List<Integer> preferredSeats) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "请先登录后再下单", null);
        }
        if (sessionId == null || sessionId <= 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "场次不合法", null);
        }

        // 张数: 模型给了具体座位就用座位数(更具体), 否则用 count 字段; 一律夹到 [1,4]
        List<Integer> hint = normalizeHint(preferredSeats);
        int seats = Math.max(1, Math.min(MAX_SEATS, hint.isEmpty() ? (count == null ? 1 : count) : hint.size()));

        // 场次可售 + 未开场(复用既有校验,不要另写一套)
        Session session = orderCore.requirePurchasableSession(sessionId);

        // 实时位图 —— 必须重读,模型拿到索引到用户点确认之间座位可能已被抢走
        SeatMapVO vo = seatService.seatMap(sessionId, userId);
        int seatCount = vo.getSeatCount();
        if (seatCount <= 0) {
            throw new BizException("场次座位数异常,无法生成下单方案");
        }
        int cols = vo.getCols() > 0 ? vo.getCols() : 1;
        int rows = vo.getRows() > 0 ? vo.getRows() : 1;
        boolean[] available = decodeAvailable(vo, seatCount);

        // 座位决策: 模型的提示全通过才采纳, 否则服务端重找(D8)
        List<Integer> chosen = null;
        if (!hint.isEmpty() && allAvailable(hint, available) && isSameRowContiguous(hint, cols)) {
            chosen = new ArrayList<>(hint);
        }
        if (chosen == null) {
            chosen = pickCenteredRun(available, rows, cols, seatCount, seats);
        }
        if (chosen == null || chosen.size() != seats) {
            throw new BizException("该场次没有 " + seats + " 个连座可选");
        }
        Collections.sort(chosen);

        // 越界防护: 提案里的座位绝不能越界,否则确认时会落进 lock_seat.lua 的 SETBIT
        // 把 Redis 位图按 offset 撑大(AGENTS.md 记录:offset=100000 → 694 倍膨胀)
        orderCore.requireSeatsInRange(sessionId, chosen);

        // 价格一律取 session 表 —— 模型的 price/totalAmount 直接丢弃
        BigDecimal unitPrice = session.getPrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(chosen.size()));

        // 同场次存量待支付单 —— 确认后会被 closePending 取消,必须提前告知用户(ADR-0002 证据 1)
        Order pending = orderLockService.findUserPendingOrder(userId, sessionId);
        String pendingCancelOrderNo = pending == null ? null : pending.getOrderNo();
        Long pendingCancelExpireAt = (pending == null || pending.getExpireAt() == null)
                ? null
                : pending.getExpireAt().atZone(CINEMA_ZONE).toInstant().toEpochMilli();

        OrderProposal proposal = OrderProposal.builder()
                .proposalId(UUID.randomUUID().toString())
                .userId(userId)
                .sessionId(sessionId)
                .seatIndexes(List.copyOf(chosen))
                .seatDesc(buildSeatDesc(session.getHallId(), chosen))
                .unitPrice(unitPrice)
                .totalAmount(totalAmount)
                .movieTitle(vo.getMovieTitle())
                .hallName(vo.getHallName())
                .startTime(vo.getStartTime() == null ? null : vo.getStartTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .expiresAt(System.currentTimeMillis() + ttlSeconds * 1000L)
                .pendingCancelOrderNo(pendingCancelOrderNo)
                .pendingCancelExpireAt(pendingCancelExpireAt)
                .build();

        save(proposal);
        log.info("[chat-order] 建提案 proposalId={} user={} session={} seats={} amount={}",
                proposal.getProposalId(), userId, sessionId, chosen, totalAmount);
        return proposal;
    }

    // =================================================================
    // 读 / 消费提案
    // =================================================================

    /** 读提案(不消费)。读不到返 null。 */
    public OrderProposal getProposal(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) {
            return null;
        }
        String raw = redisTemplate.opsForValue().get(RedisKeys.chatProposal(proposalId));
        return raw == null ? null : deserialize(raw);
    }

    /**
     * 一次性消费提案: 读出即删,并校验归属。
     *
     * <p>用 Redis 的原子 {@code GETDEL}(Spring Data Redis 的 {@code getAndDelete})消费,
     * 避免两个并发确认同时读到同一份快照而锁两次座。{@code @Idempotent}(8s) 挡的是
     * 「同一请求重放」,这里挡的是「同一份快照被用两次」—— 两者互补。
     *
     * <p><b>归属校验在删除之后</b>:proposalId 是不可猜测的 UUID,冒用需要先拿到它,
     * 因此「误删他人提案」的代价可接受;而反过来「先校验再删」的读-改-写窗口
     * 会让并发双消费重新变得可能。
     *
     * @throws BizException 提案不存在/已过期/不属于当前用户
     */
    public OrderProposal consumeProposal(String proposalId, Long userId) {
        if (proposalId == null || proposalId.isBlank()) {
            throw new BizException("下单方案已过期,请重新发起");
        }
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "请先登录后再下单", null);
        }
        String raw = redisTemplate.opsForValue().getAndDelete(RedisKeys.chatProposal(proposalId));
        if (raw == null) {
            throw new BizException("下单方案已过期,请重新发起");
        }
        OrderProposal proposal = deserialize(raw);
        if (proposal == null) {
            throw new BizException("下单方案已损坏,请重新发起");
        }
        if (!userId.equals(proposal.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该下单方案", null);
        }
        return proposal;
    }

    // =================================================================
    // 内部
    // =================================================================

    private void save(OrderProposal proposal) {
        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.chatProposal(proposal.getProposalId()),
                    objectMapper.writeValueAsString(proposal),
                    Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.error("[chat-order] 写提案失败 proposalId={}", proposal.getProposalId(), e);
            throw new BizException("下单方案生成失败,请稍后重试");
        }
    }

    private OrderProposal deserialize(String raw) {
        try {
            return objectMapper.readValue(raw, OrderProposal.class);
        } catch (Exception e) {
            log.warn("[chat-order] 提案反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 清理模型给的座位提示: 去 null / 负数 / 重复, 排序。 */
    private List<Integer> normalizeHint(List<Integer> preferredSeats) {
        if (preferredSeats == null || preferredSeats.isEmpty()) {
            return List.of();
        }
        return preferredSeats.stream()
                .filter(java.util.Objects::nonNull)
                .filter(i -> i >= 0)
                .distinct()
                .sorted()
                .limit(MAX_SEATS)
                .toList();
    }

    /**
     * 解码双位图 → 每座位是否可选。
     *
     * <p>位图是 Base64(字节内高位在前),第 i 位对应 seat_index=i,
     * 与 {@code utils/bitmap.ts} 前端解码一致。lock 位(任何人锁的)与 sold 位都算不可选。
     */
    private boolean[] decodeAvailable(SeatMapVO vo, int seatCount) {
        byte[] lock = Base64.getDecoder().decode(vo.getLockBitmap());
        byte[] sold = Base64.getDecoder().decode(vo.getSoldBitmap());
        boolean[] available = new boolean[seatCount];
        for (int i = 0; i < seatCount; i++) {
            available[i] = !bitSet(lock, i) && !bitSet(sold, i);
        }
        return available;
    }

    private boolean bitSet(byte[] bitmap, int index) {
        int byteIdx = index >>> 3;
        if (byteIdx >= bitmap.length) {
            return false;
        }
        int bitInByte = 7 - (index & 7);
        return ((bitmap[byteIdx] >> bitInByte) & 1) == 1;
    }

    private boolean allAvailable(List<Integer> seats, boolean[] available) {
        for (Integer idx : seats) {
            if (idx == null || idx < 0 || idx >= available.length || !available[idx]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否同行连座(索引连续且在同一行)。
     *
     * <p>网格映射与前端 {@code stores/seat.ts} 的 {@code rowOfIdx/colOfIdx} 一致:
     * {@code row = floor(idx / cols), col = idx % cols}。
     */
    private boolean isSameRowContiguous(List<Integer> seats, int cols) {
        for (int i = 1; i < seats.size(); i++) {
            int prev = seats.get(i - 1);
            int curr = seats.get(i);
            if (prev / cols != curr / cols || curr != prev + 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * 找一组连座,优先「中段」(靠中间的行 + 靠中间的列)。
     *
     * <p>与 {@code ChatTools.findContiguousSeats} 的差别: 那个工具取<b>第一行</b>的连座,
     * 且用 {@code List.contains} 做 O(n) 扫描。这里改成遍历所有行、在同分时取更居中的段。
     */
    private List<Integer> pickCenteredRun(boolean[] available, int rows, int cols, int seatCount, int count) {
        int centerRow = (rows - 1) / 2;
        int centerCol = (cols - 1) / 2;
        int bestScore = Integer.MAX_VALUE;
        List<Integer> best = null;

        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            if (base >= seatCount) {
                break;
            }
            int rowCols = Math.min(cols, seatCount - base);
            int c = 0;
            while (c < rowCols) {
                if (!available[base + c]) {
                    c++;
                    continue;
                }
                int start = c;
                while (c < rowCols && available[base + c]) {
                    c++;
                }
                int runEnd = c; // exclusive
                for (int s = start; s + count <= runEnd; s++) {
                    int mid = s + count / 2;
                    int score = Math.abs(r - centerRow) * cols + Math.abs(mid - centerCol);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new ArrayList<>(count);
                        for (int k = 0; k < count; k++) {
                            best.add(base + s + k);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 座位描述 —— 走 seat 表拿权威的排/座号, 与订单页 {@code buildSeatDesc} 同源。 */
    private String buildSeatDesc(Long hallId, List<Integer> seatIndexes) {
        if (hallId == null || seatIndexes.isEmpty()) {
            return "";
        }
        Map<Integer, String> labels = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, hallId)
                        .in(Seat::getSeatIndex, seatIndexes))
                .stream()
                .collect(Collectors.toMap(Seat::getSeatIndex,
                        s -> s.getRowNo() + "排" + s.getColNo() + "座", (a, b) -> a));
        return seatIndexes.stream()
                .map(idx -> labels.getOrDefault(idx, idx + "号座位"))
                .collect(Collectors.joining("、"));
    }
}
