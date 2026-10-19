package com.cinema.modules.chat.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.service.OrderLockService;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.seat.SeatAvailability;
import com.cinema.modules.seat.SeatLabel;
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

        // 票数: count 是「用户要买几张票」, preferredSeats 只是模型的座位提示。
        // 两者长度不一致时以 count 为准, 提示直接作废(交给服务端重找)——
        // 否则用户说 3 张却拿到 2 张, 票数被模型的座位数组悄悄改了。
        List<Integer> hint = normalizeHint(preferredSeats);
        int seats = resolveSeatCount(count, hint);
        List<Integer> usableHint = hint.size() == seats ? hint : List.of();

        // 场次可售 + 未开场(复用既有校验,不要另写一套)
        Session session = orderCore.requirePurchasableSession(sessionId);

        // 实时位图 —— 必须重读,模型拿到索引到用户点确认之间座位可能已被抢走
        SeatMapVO vo = seatService.seatMap(sessionId, userId);
        int seatCount = vo.getSeatCount();
        if (seatCount <= 0) {
            throw new BizException("场次座位数异常,无法生成下单方案");
        }
        // 位图解码 / 选座统一交给 SeatAvailability —— 判定口径与搬迁前逐条一致
        SeatAvailability availability = SeatAvailability.of(vo);

        // 座位决策: 模型的提示全通过才采纳, 否则服务端重找(D8)
        List<Integer> chosen = null;
        if (!usableHint.isEmpty() && allAvailable(usableHint, availability)
                && availability.isSameRowContiguous(usableHint)) {
            chosen = new ArrayList<>(usableHint);
        }
        if (chosen == null) {
            chosen = availability.findRun(seats, SeatAvailability.RunPolicy.CENTERED);
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
            throw new BizException(ResultCode.PROPOSAL_EXPIRED.getCode(), "下单方案已过期,请重新发起", null);
        }
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "请先登录后再下单", null);
        }
        String raw = redisTemplate.opsForValue().getAndDelete(RedisKeys.chatProposal(proposalId));
        if (raw == null) {
            throw new BizException(ResultCode.PROPOSAL_EXPIRED.getCode(), "下单方案已过期,请重新发起", null);
        }
        OrderProposal proposal = deserialize(raw);
        if (proposal == null) {
            throw new BizException(ResultCode.PROPOSAL_EXPIRED.getCode(), "下单方案已损坏,请重新发起", null);
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

    /**
     * 票数解析 —— count 优先。
     *
     * <p>{@code count} 是用户(或模型转述用户)说的「要几张票」, 是票数的唯一权威;
     * {@code preferredSeats} 只是模型的座位提示, 长度与票数对不上时一律作废。
     * 只有 count 缺失(理论上不该发生, 解析器已保证 1~4)才退回按提示长度估。
     */
    private int resolveSeatCount(Integer count, List<Integer> hint) {
        if (count != null) {
            return Math.max(1, Math.min(OrderProposalLimits.MAX_SEATS, count));
        }
        if (!hint.isEmpty()) {
            return Math.max(1, Math.min(OrderProposalLimits.MAX_SEATS, hint.size()));
        }
        return 1;
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
                .limit(OrderProposalLimits.MAX_SEATS)
                .toList();
    }

    /**
     * 提示里的每个座位都必须当前可选。
     *
     * <p>越界索引(负数 / {@code >= seatCount})一律判不可选 —— 绝不能让它进到确认流程。
     */
    private boolean allAvailable(List<Integer> seats, SeatAvailability availability) {
        for (Integer idx : seats) {
            if (idx == null || idx < 0 || !availability.isAvailable(idx)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 座位描述 —— 走 seat 表拿权威的排/座号, 与订单页同源(渲染统一在 {@link SeatLabel})。
     *
     * <p>降级用 {@code INDEX_ONLY}: 提案当场就要把座位摆给用户看,
     * 缺一个座位也不能让用户以为没选上, 所以退化成「&lt;索引&gt;号座位」而不是跳过。
     */
    private String buildSeatDesc(Long hallId, List<Integer> seatIndexes) {
        if (hallId == null || seatIndexes.isEmpty()) {
            return "";
        }
        Map<Integer, Seat> labels = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, hallId)
                        .in(Seat::getSeatIndex, seatIndexes))
                .stream()
                .collect(Collectors.toMap(Seat::getSeatIndex, s -> s, (a, b) -> a));
        return SeatLabel.describe(seatIndexes, labels, SeatLabel.Fallback.INDEX_ONLY);
    }
}
