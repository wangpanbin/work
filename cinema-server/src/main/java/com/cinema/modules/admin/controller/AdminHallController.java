package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<Hall> halls = hallMapper.selectList(qw);
        fillVipFromRow(halls);
        return R.ok(halls);
    }

    @GetMapping("/{id}")
    public R<Hall> detail(@PathVariable Long id) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            throw new BizException("影厅不存在");
        }
        fillVipFromRow(List.of(hall));
        return R.ok(hall);
    }

    // =====================================================================
    // VIP 起始行(派生值)
    //
    // VIP 归属的唯一真源是 seat 表的 seat_type=1 —— hall 表里根本没有这一列。
    // 所以这里用 MIN(row_no) 反推, 而不是另存一份: 另存一份就造出两个真源
    // (改了列不重建座位 / 重建了座位不改列), 两者必然漂移。
    //
    // 语义是**行号**(1 起算), 与 insertSeats 里 `r >= vipFromRow ? 1 : 0` 的 r 同一种;
    // 前端回填后原样提交, 两侧必须同一种, 否则 VIP 区会整个错位。
    // =====================================================================

    /**
     * 回填这些影厅的 VIP 起始行; 一个 VIP 座位都没有的影厅 → null。
     *
     * <p>一次批量查(GROUP BY hall_id)取回所有影厅的值, 不在循环里逐个查(N+1)。
     */
    private void fillVipFromRow(List<Hall> halls) {
        if (halls == null || halls.isEmpty()) {
            return;
        }
        Map<Long, Integer> vipFromRows = loadVipFromRows(halls.stream().map(Hall::getId).toList());
        for (Hall hall : halls) {
            hall.setVipFromRow(vipFromRows.get(hall.getId()));
        }
    }

    /**
     * 批量取这些影厅的 VIP 起始行: {@code MIN(row_no) WHERE seat_type = 1}(按 hall_id 分组)。
     *
     * <p>没有 VIP 座位的影厅不会出现在结果里 → 读出来就是 null。
     */
    private Map<Long, Integer> loadVipFromRows(Collection<Long> hallIds) {
        if (hallIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = seatMapper.selectMaps(
                new QueryWrapper<Seat>()
                        .select("hall_id AS hallId", "MIN(row_no) AS vipFromRow")
                        .in("hall_id", hallIds)
                        .eq("seat_type", 1)
                        .groupBy("hall_id"));
        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object hallId = columnValue(row, "hallId");
            Object vipFromRow = columnValue(row, "vipFromRow");
            if (hallId instanceof Number id && vipFromRow instanceof Number v) {
                result.put(id.longValue(), v.intValue());
            }
        }
        return result;
    }

    /** 取聚合行里的列值(列名大小写不敏感 —— 免得驱动改了别名大小写就读不到)。 */
    private static Object columnValue(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
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
        fillVipFromRow(List.of(hall));
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
        // ⚠️ **vipFromRow 为 null 的语义 = 保持原样**, 不是「清空 VIP 区」(2026-10-25 交互审查 P2-1)。
        //
        // <p>为什么: 请求里没有这个值, 有两种完全不同的原因 ——
        // ① 管理员确实要把 VIP 区清掉;
        // ② 调用方**根本没这个字段**(老前端 / 老 Hall 实体没有这一列), 发的就是 null。
        // ②与①在 wire 上长得一模一样, 却是**破坏性**的: update 会删光该厅全部座位,
        // 再按 null 重插 → 整厅 VIP 座位变成普通座位, 界面只提示「重建座位布局」。
        //
        // <p>所以这里取「当前值」兜底: 原来有 VIP 就继续有, 原来没有就还是没有。
        // 真要清空 VIP 区请**新建影厅** —— 与「有过订单的影厅不允许改行列数」同一条规矩。
        //
        // <p>注意取值必须在**删座位之前**做 —— 座位删掉后就派生不出来了。
        Integer vipFromRow = dto.getVipFromRow() != null
                ? dto.getVipFromRow()
                : loadVipFromRows(List.of(id)).get(id);
        // ⚠️ VIP 起始行不能超过行数 —— 否则**整厅 VIP 会静默消失**:
        // `insertSeats` 按 `r >= vipFromRow` 插 VIP, 起始行 7 而影厅只有 6 行时
        // 一行 VIP 都不会插, 而前端还显示着「7」(它不知道后端把 VIP 丢了)。
        // 这条要在**删座位之前**拦住 —— 拦晚了座位已经重建完了。
        if (vipFromRow != null && vipFromRow > dto.getSeatRows()) {
            throw new BizException("VIP 起始行 " + vipFromRow
                    + " 超过影厅行数 " + dto.getSeatRows() + ", 请把 VIP 起始行调到行数以内");
        }
        transactionTemplate.executeWithoutResult(tx -> {
            hallMapper.updateById(hall);
            seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getHallId, id));
            insertSeats(hall, vipFromRow);
        });
        hallLayoutCache.invalidate(id);
        invalidateSessionInfoCache(id);
        fillVipFromRow(List.of(hall));
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