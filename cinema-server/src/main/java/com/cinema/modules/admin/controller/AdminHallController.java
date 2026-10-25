package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.hall.dto.HallDTO;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.reader.HallLayoutCache;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/halls")
@RequiredArgsConstructor
public class AdminHallController {

    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;
    private final HallLayoutCache hallLayoutCache;
    private final TransactionTemplate transactionTemplate;
    private final SessionMapper sessionMapper;
    private final OrderMapper orderMapper;
    private final SessionInfoCacheService sessionInfoCacheService;

    @GetMapping
    public R<List<Hall>> list(@RequestParam(required = false) Long cinemaId) {
        LambdaQueryWrapper<Hall> qw = new LambdaQueryWrapper<Hall>()
                .eq(cinemaId != null, Hall::getCinemaId, cinemaId);
        return R.ok(hallMapper.selectList(qw));
    }

    @GetMapping("/{id}")
    public R<Hall> detail(@PathVariable Long id) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            throw new BizException("影厅不存在");
        }
        return R.ok(hall);
    }

    /**
     * 创建影厅: 同时按 行x列 自动生成座位(含 VIP 区), 整体在一个事务中
     */
    @PostMapping
    public R<Hall> create(@Valid @RequestBody HallDTO dto) {
        int count = dto.getSeatRows() * dto.getSeatCols();
        Hall hall = new Hall();
        hall.setCinemaId(dto.getCinemaId());
        hall.setName(dto.getName());
        hall.setSeatRows(dto.getSeatRows());
        hall.setSeatCols(dto.getSeatCols());
        hall.setSeatCount(count);
        Long hallId = transactionTemplate.execute(tx -> {
            hallMapper.insert(hall);
            insertSeats(hall, dto.getVipFromRow());
            return hall.getId();
        });
        hall.setId(hallId);
        return R.ok(hall);
    }

    /**
     * 更新影厅。
     *
     * <p><b>改行列的防护</b>(2026-10-24): 座位索引是 {@code seatIndex = (r-1)*cols + (c-1)},
     * 所以 {@code cols} 一变, 同一个 {@code seat.id} 对应的 {@code seatIndex} 全变。
     * 而已付费订单的 {@code order_item.seat_index} 与按场次的 Redis 位图
     * (<b>以 seatIndex 为下标</b>)都指向旧映射 —— 改完之后会出现:
     * <ul>
     *   <li>已售座位变成「可选」→ 可能超卖;</li>
     *   <li>真实座位反而显示被占 → 用户买不到自己的位置。</li>
     * </ul>
     * 所以只要该影厅**有过任何订单**, 就不允许改行列数 —— 请新建影厅。
     * 只改名字 / VIP 区是安全的(不动 seatIndex 映射)。
     *
     * <p><b>缓存失效</b>: 改影厅后要把该厅名下所有场次的元数据缓存清掉。
     * {@code SessionInfoCacheService} 缓存着场次的 rows/cols/seatCount,
     * 不清的话 {@code GET /seat-map} 与 {@code SeatRangeGate} 会在 30 分钟内
     * 读到旧座位数 —— 客户端看到的座位数与服务端允许锁的范围对不上。
     */
    @PutMapping("/{id}")
    public R<Hall> update(@PathVariable Long id, @Valid @RequestBody HallDTO dto) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            throw new BizException("影厅不存在");
        }
        // ⚠️ 行列都是 Integer, 必须用 equals 比较 —— `!=` 是**引用比较**,
        // 值大于 127 的行/列会各自装箱成不同对象, 于是「没改也被判成改了」。
        boolean layoutChanged = !Objects.equals(hall.getSeatRows(), dto.getSeatRows())
                || !Objects.equals(hall.getSeatCols(), dto.getSeatCols());
        if (layoutChanged) {
            rejectLayoutChangeIfOrdersExist(id);
        }
        hall.setName(dto.getName());
        hall.setSeatRows(dto.getSeatRows());
        hall.setSeatCols(dto.getSeatCols());
        hall.setSeatCount(dto.getSeatRows() * dto.getSeatCols());
        transactionTemplate.executeWithoutResult(tx -> {
            hallMapper.updateById(hall);
            seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getHallId, id));
            insertSeats(hall, dto.getVipFromRow());
        });
        hallLayoutCache.invalidate(id);
        invalidateSessionInfoCache(id);
        return R.ok(hall);
    }

    /**
     * 影厅有过订单时拒绝改行列数。
     *
     * <p>判据是「该影厅名下任何场次是否存在订单」, 包含已退票/已取消的历史订单 ——
     * 历史订单的 {@code order_item.seat_index} 同样会被重映射, 营收对账与电子票
     * 都要靠它还原物理座位。
     */
    private void rejectLayoutChangeIfOrdersExist(Long hallId) {
        List<Session> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<Session>().eq(Session::getHallId, hallId));
        if (sessions.isEmpty()) {
            return;
        }
        List<Long> sessionIds = sessions.stream().map(Session::getId).toList();
        Long orderCount = orderMapper.selectCount(
                new LambdaQueryWrapper<Order>().in(Order::getSessionId, sessionIds));
        if (orderCount != null && orderCount > 0) {
            throw new BizException("该影厅已有订单(" + orderCount + " 笔), 不能修改行列数(座位索引会错位); 请新建影厅");
        }
    }

    /** 清掉该影厅名下所有场次的元数据缓存(场次数量有限, 直接逐个删)。 */
    private void invalidateSessionInfoCache(Long hallId) {
        List<Session> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<Session>().eq(Session::getHallId, hallId));
        for (Session s : sessions) {
            sessionInfoCacheService.invalidate(s.getId());
        }
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            throw new BizException("影厅不存在");
        }
        transactionTemplate.executeWithoutResult(tx -> {
            seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getHallId, id));
            hallMapper.deleteById(id);
        });
        hallLayoutCache.invalidate(id);
        invalidateSessionInfoCache(id);
        return R.ok();
    }

    private void insertSeats(Hall hall, Integer vipFromRow) {
        int rows = hall.getSeatRows();
        int cols = hall.getSeatCols();
        long hallId = hall.getId();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                Seat seat = new Seat();
                seat.setId(hallId * 100000L + (long) (r - 1) * cols + (c - 1));
                seat.setHallId(hallId);
                seat.setSeatIndex((r - 1) * cols + (c - 1));
                seat.setRowNo(r);
                seat.setColNo(c);
                seat.setSeatType(vipFromRow != null && r >= vipFromRow ? 1 : 0);
                seat.setX(c * 40);
                seat.setY(r * 40);
                seatMapper.insert(seat);
            }
        }
    }
}