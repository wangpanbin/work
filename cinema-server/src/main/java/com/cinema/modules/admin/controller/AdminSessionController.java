package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.common.web.PageParams;
import com.cinema.infra.redis.RecoverResult;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.session.dto.SessionDTO;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final SeatBitmapGuard seatBitmapGuard;
    private final SessionInfoCacheService sessionInfoCacheService;
    private final OrderMapper orderMapper;

    /**
     * 场次列表(全量)。
     *
     * <p><b>保留不动</b>(2026-10-24): 这个端点一次返回全部场次(实测 1089 行),
     * 稳定性报告登记为 P2-1b「完全不分页」。它没有分页参数、返回的是裸
     * {@code List<Session>} —— 改它会破坏前端 {@code SessionManage.vue} 的返回类型契约。
     * 分页走另一个端点 {@code GET /api/admin/sessions/page}, 两者并存, 旧契约不受影响。
     */
    @GetMapping
    public R<List<Session>> list(@RequestParam(required = false) Long movieId) {
        LambdaQueryWrapper<Session> qw = new LambdaQueryWrapper<Session>()
                .eq(movieId != null, Session::getMovieId, movieId)
                .orderByAsc(Session::getStartTime);
        return R.ok(sessionMapper.selectList(qw));
    }

    /**
     * 场次列表(分页) —— 2026-10-24 新增。
     *
     * <p>为什么与 {@link #list} 并存而不是改它: 那条端点返回裸 {@code List<Session>},
     * 同一个端点塞两种响应形状会让前端与外部脚本都要分支判断, 且老调用方会被静默改语义。
     * 另开一条路径, 老端点行为一字不变, 前端按需切换。
     *
     * <p>入参走 {@link PageParams} 钳制: page 归一到 >= 1, size 钳到 [1, 100]
     * —— {@code size <= 0} 在 MyBatis-Plus 里会**静默关闭分页**并把 total 记成 0,
     * 那正是 2026-10-11 稳定性报告 P2-1 修掉的问题, 不能在这里重犯。
     */
    @GetMapping("/page")
    public R<Page<Session>> page(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  @RequestParam(required = false) Long movieId) {
        LambdaQueryWrapper<Session> qw = new LambdaQueryWrapper<Session>()
                .eq(movieId != null, Session::getMovieId, movieId)
                .orderByAsc(Session::getStartTime);
        return R.ok(sessionMapper.selectPage(
                new Page<>(PageParams.page(page), PageParams.size(size)), qw));
    }

    @PostMapping
    public R<Session> create(@Valid @RequestBody SessionDTO dto) {
        Session s = new Session();
        applyDto(s, dto);
        if (s.getEndTime() == null) {
            Movie movie = movieMapper.selectById(dto.getMovieId());
            if (movie != null && movie.getDuration() != null) {
                s.setEndTime(s.getStartTime().plusMinutes(movie.getDuration()));
            }
        }
        sessionMapper.insert(s);
        sessionInfoCacheService.invalidate(s.getId());
        return R.ok(s);
    }

    @PutMapping("/{id}")
    public R<Session> update(@PathVariable Long id, @Valid @RequestBody SessionDTO dto) {
        Session s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException("场次不存在");
        }
        applyDto(s, dto);
        sessionMapper.updateById(s);
        sessionInfoCacheService.invalidate(s.getId());
        return R.ok(s);
    }

    /**
     * 删除场次。
     *
     * <p><b>存量订单校验</b>(2026-10-24): 原先直接删, 没有查过这个场次有没有订单。
     * 删掉之后已付费订单变孤儿 —— 营收导出与看板的 LEFT JOIN 全变 NULL,
     * 占用率统计跟着失真, 而且**静默**: 报表少一行, 看板上少一块, 没人收到任何报错。
     *
     * <p>所以: 存在**未完结**订单(待支付 / 已支付 / 退款中)时拒绝删除, 并把订单号列出来。
     * 已取消 / 已退款的历史订单允许删 —— 否则历史营收会跟着场次一起消失。
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Session s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException("场次不存在");
        }
        List<Order> active = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getSessionId, id)
                .in(Order::getStatus,
                        OrderStatus.PENDING_PAY.getCode(),
                        OrderStatus.PAID.getCode(),
                        OrderStatus.REFUNDING.getCode()));
        if (!active.isEmpty()) {
            String orderNos = active.stream().map(Order::getOrderNo).limit(10).toList().toString();
            throw new BizException("该场次还有 " + active.size() + " 笔未完结订单, 不能删除: " + orderNos);
        }
        sessionMapper.deleteById(id);
        sessionInfoCacheService.invalidate(s.getId());
        seatBitmapGuard.clear(s.getId());
        return R.ok();
    }

    /** P5 手动恢复位图 — 从 DB 重建 Redis Bitmap */
    @PostMapping("/bitmaps/recover")
    public R<RecoverResult> recoverBitmaps(@RequestParam Long sessionId) {
        Session s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BizException("场次不存在");
        }
        return R.ok(seatBitmapGuard.forceRecover(sessionId));
    }

    private void applyDto(Session s, SessionDTO dto) {
        s.setMovieId(dto.getMovieId());
        s.setHallId(dto.getHallId());
        s.setStartTime(dto.getStartTime());
        s.setEndTime(dto.getEndTime());
        s.setPrice(dto.getPrice());
        s.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }
}