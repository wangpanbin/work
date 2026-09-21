package com.cinema.modules.chat.tools;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.chat.service.KnowledgeService;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.service.MovieService;
import com.cinema.modules.order.service.OrderQueryService;
import com.cinema.modules.order.vo.OrderVO;
import com.cinema.modules.seat.service.SeatService;
import com.cinema.modules.seat.vo.SeatMapVO;
import com.cinema.modules.session.service.SessionService;
import com.cinema.modules.session.vo.SessionVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T2 cycle 1 — 对话助手只读工具集(spec §5.1,ADR-0002).
 *
 * <p><b>只读 + 只推荐</b>:8 个 {@code @Tool} 方法全部走既有只读 Service + 知识库,
 * <b>不暴露任何写方法</b>(无 lockSeats / pay / cancel / refund / forceRecover / 管理端写接口).
 * spec §9.4 第 5 条 + ADR-0002 的硬性约束,反射白名单断言兜底.
 *
 * <p><b>入参白名单校验</b>(spec §5.5):
 * <ul>
 *     <li>{@code sessionId} / {@code movieId} 正数(LLM 合成调用不可信,必须校验)</li>
 *     <li>{@code count} 夹紧到 {@code [1,4]}(与 {@code LockSeatsDTO @Size(max=4)} + {@code stores/seat.ts maxSelect=4} 一致)</li>
 *     <li>查不到就返回人类可读的失败说明,不抛业务异常(spec §5.5 第 3 条)</li>
 * </ul>
 *
 * <p><b>null userId</b>(spec §5.1 + Q5 决策):{@code getMyOrders} / {@code getMyOrder}
 * 在 {@code userId==null} 时直接返 {@code Map.of("error","LOGIN_REQUIRED")},
 * 由 §5.4(a) {@code ToolArgumentsErrorHandler} 路径转自然语言回复("请先登录"),
 * 不抛异常(避免浪费一次 LLM 轮次).
 *
 * <p><b>FAQ 工具</b>(spec #20):{@code searchFaq} 走 {@code KnowledgeService.searchFaq},
 * 18 条 qa_knowledge 种子数据(怎么买票/退票/取票/管理后台/退款周期等通用问答),
 * LLM 自主判断是否调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTools {

    private final MovieService movieService;
    private final SessionService sessionService;
    private final SeatService seatService;
    private final OrderQueryService orderQueryService;
    private final SessionInfoCacheService sessionInfoCacheService;
    private final KnowledgeService knowledgeService;

    // ============ 影片 / 场次类(4 个)============

    @Tool("按关键词/类型/地区搜索影片(分页, 默认热映 status=1)")
    public Page<Movie> searchMovies(
            @P("关键词(影片标题),可空") String keyword,
            @P("类型,如 '科幻',可空") String genre,
            @P("地区,如 '中国',可空") String region) {
        log.debug("[chat-tools] searchMovies keyword={}, genre={}, region={}", keyword, genre, region);
        // 默认 status=1 热映;page=1,size=10(对接 LLM 的合理分页)
        return movieService.search(1, 10, keyword, genre, region, 1);
    }

    @Tool("获取影片详情(按 ID)")
    public Movie getMovieDetail(@P("影片 ID,必须正数") Long movieId) {
        validatePositive(movieId, "movieId");
        log.debug("[chat-tools] getMovieDetail movieId={}", movieId);
        return movieService.detail(movieId);
    }

    @Tool("某影片在某日的所有场次(含影厅/影院信息)")
    public List<SessionVO> listSessions(
            @P("影片 ID,必须正数") Long movieId,
            @P("日期 yyyy-MM-dd,可空(空=今日)") LocalDate date) {
        validatePositive(movieId, "movieId");
        if (date == null) date = LocalDate.now();
        log.debug("[chat-tools] listSessions movieId={}, date={}", movieId, date);
        return sessionService.listByMovieAndDate(movieId, date);
    }

    // ============ 座位类(2 个)============

    @Tool("获取场次座位摘要(总座位数/可选数/已售数/若干连座片段,不返回整张位图)")
    public Map<String, Object> getSeatSummary(
            @P("场次 ID,必须正数") Long sessionId,
            @P("用户 ID,可空(用于填充 myLockedSeats)") Long userId) {
        validatePositive(sessionId, "sessionId");
        log.debug("[chat-tools] getSeatSummary sessionId={}, userId={}", sessionId, userId);
        SeatMapVO vo = seatService.seatMap(sessionId, userId);

        // 解码 lock/sold 位图,计算可选 / 已锁 / 已售
        byte[] lock = Base64.getDecoder().decode(vo.getLockBitmap());
        byte[] sold = Base64.getDecoder().decode(vo.getSoldBitmap());
        int seatCount = vo.getSeatCount();

        int lockedOther = 0, soldCount = 0, available = 0;
        int availableContiguousAt = -1; // 第一个连座起点
        int availableContiguousLen = 0;
        int currentRunLen = 0, currentRunStart = -1;

        for (int i = 0; i < seatCount; i++) {
            int byteIdx = i >>> 3;
            int bitInByte = 7 - (i & 7);
            boolean isLock = byteIdx < lock.length && ((lock[byteIdx] >> bitInByte) & 1) == 1;
            boolean isSold = byteIdx < sold.length && ((sold[byteIdx] >> bitInByte) & 1) == 1;
            if (isSold) {
                soldCount++;
                if (currentRunLen > availableContiguousLen) {
                    availableContiguousAt = currentRunStart;
                    availableContiguousLen = currentRunLen;
                }
                currentRunLen = 0; currentRunStart = -1;
            } else if (isLock) {
                lockedOther++;
                if (currentRunLen > availableContiguousLen) {
                    availableContiguousAt = currentRunStart;
                    availableContiguousLen = currentRunLen;
                }
                currentRunLen = 0; currentRunStart = -1;
            } else {
                available++;
                if (currentRunStart == -1) currentRunStart = i;
                currentRunLen++;
            }
        }
        if (currentRunLen > availableContiguousLen) {
            availableContiguousAt = currentRunStart;
            availableContiguousLen = currentRunLen;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionId", vo.getSessionId());
        summary.put("movieTitle", vo.getMovieTitle());
        summary.put("hallName", vo.getHallName());
        summary.put("startTime", vo.getStartTime());
        summary.put("price", vo.getPrice());
        summary.put("seatCount", seatCount);
        summary.put("available", available);
        summary.put("lockedOther", lockedOther);
        summary.put("sold", soldCount);
        if (availableContiguousAt >= 0) {
            summary.put("longestContiguousStart", availableContiguousAt);
            summary.put("longestContiguousLength", availableContiguousLen);
        }
        return summary;
    }

    @Tool("在场次中找 N 个连座(返回座位索引列表,优先中段 + 可指定 preferRow)")
    public List<Integer> findContiguousSeats(
            @P("场次 ID,必须正数") Long sessionId,
            @P("用户 ID,可空") Long userId,
            @P("连座数量,必须在 [1,4]") int count,
            @P("偏好行号(从 0 开始),可空") Integer preferRow) {
        validatePositive(sessionId, "sessionId");
        if (count < 1 || count > 4) {
            throw new BizException("count 必须在 [1,4] 范围,实际: " + count);
        }
        log.debug("[chat-tools] findContiguousSeats sessionId={}, userId={}, count={}, preferRow={}",
                sessionId, userId, count, preferRow);
        SeatMapVO vo = seatService.seatMap(sessionId, userId);
        byte[] lock = Base64.getDecoder().decode(vo.getLockBitmap());
        byte[] sold = Base64.getDecoder().decode(vo.getSoldBitmap());
        int seatCount = vo.getSeatCount();
        int cols = vo.getCols(); // 用于按行划分(行 = i / cols)

        // 收集所有可选座位索引
        List<Integer> availableIdx = new ArrayList<>();
        for (int i = 0; i < seatCount; i++) {
            int byteIdx = i >>> 3;
            int bitInByte = 7 - (i & 7);
            boolean isLock = byteIdx < lock.length && ((lock[byteIdx] >> bitInByte) & 1) == 1;
            boolean isSold = byteIdx < sold.length && ((sold[byteIdx] >> bitInByte) & 1) == 1;
            if (!isLock && !isSold) availableIdx.add(i);
        }

        // 找同行的 count 连座
        for (Integer idx : availableIdx) {
            int row = idx / cols;
            int col = idx % cols;
            List<Integer> candidate = new ArrayList<>();
            candidate.add(idx);
            for (int nextCol = col + 1; nextCol < cols && candidate.size() < count; nextCol++) {
                int nextIdx = row * cols + nextCol;
                if (availableIdx.contains(nextIdx)) candidate.add(nextIdx);
                else break;
            }
            if (candidate.size() == count) {
                // 优先 preferRow 命中
                if (preferRow == null || preferRow == row) return candidate;
            }
        }
        // 没找到连座
        return List.of();
    }

    // ============ 我的订单类(2 个,userId==null 短路)============

    @Tool("查询我的订单(需登录)")
    public Object getMyOrders(
            @P("用户 ID") Long userId,
            @P("订单状态过滤(0 待支付 / 1 已支付 / 2 已取消 / 3 退款中 / 4 已退款),可空") Integer status,
            @P("页码,从 1 开始,可空默认 1") Integer page,
            @P("页大小,可空默认 10") Integer size) {
        if (userId == null) {
            log.info("[chat-tools] getMyOrders userId=null → LOGIN_REQUIRED");
            return Map.of("error", "LOGIN_REQUIRED");
        }
        int p = page == null ? 1 : page;
        int s = size == null ? 10 : size;
        log.debug("[chat-tools] getMyOrders userId={}, status={}, page={}, size={}", userId, status, p, s);
        return orderQueryService.myOrders(userId, status, p, s);
    }

    @Tool("查询订单详情(需登录)")
    public Object getMyOrder(
            @P("订单号") String orderNo,
            @P("用户 ID") Long userId) {
        if (userId == null) {
            log.info("[chat-tools] getMyOrder userId=null → LOGIN_REQUIRED");
            return Map.of("error", "LOGIN_REQUIRED");
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException("orderNo 不能为空");
        }
        log.debug("[chat-tools] getMyOrder orderNo={}, userId={}", orderNo, userId);
        return orderQueryService.detail(orderNo, userId);
    }

    // ============ FAQ 通用问答类(spec #20 ID-5)============

    /**
     * 搜索影院常见问答知识库。
     *
     * <p><b>调用条件(给 LLM 看)</b>:用户问流程/规则类问题(怎么买票/退票/取票/管理后台/退款周期/开场规则/问候等),
     * <b>不是</b>具体某部电影/场次/座位/订单。命中时返 top-3 按匹配度排序的
     * {question, answer, score},LLM 据此组织自然语言回复;未命中返空 List,LLM 自行决定是否调用其他工具。
     */
    @Tool("搜索影院常见问答知识库(怎么买票/退票/取票/查订单/管理后台等通用问题)。"
            + "当用户问流程/规则类问题(不是具体某部电影/场次/座位/订单),调用此工具获取权威答案。"
            + "返回结果按匹配度排序,LLM 应组织成自然语言回复。")
    public List<Map<String, String>> searchFaq(
            @P("用户问题文本,完整传入以提高匹配率") String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        log.debug("[chat-tools] searchFaq query='{}'", query);
        return knowledgeService.searchFaq(query.trim());
    }

    // ============ 入参白名单校验 helper(spec §5.5)============

    private static void validatePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BizException(name + " 必须为正数,实际: " + value);
        }
    }
}