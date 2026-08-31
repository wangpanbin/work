package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.order.vo.OrderVO;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单查询 — OrderService 拆分的 4 个 service 之一.
 * <p>E1 拆分: 负责我的订单 + 订单详情 + VO 组装, 与锁座/支付/取消解耦.
 * <p>Phase F-① 优化: 6 SQL 批量查(与页大小无关), 已落地.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;
    private final OrderCore orderCore;

    public Page<OrderVO> myOrders(Long userId, Integer status, int page, int size) {
        // 1. 查订单
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(status != null, Order::getStatus, status)
                        .orderByDesc(Order::getCreatedAt));
        List<Order> orders = p.getRecords();
        if (orders.isEmpty()) {
            return new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        }

        // 2. 一次查 order_item
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        Map<Long, List<Integer>> orderSeatMap = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(
                        OrderItem::getOrderId,
                        Collectors.mapping(OrderItem::getSeatIndex, Collectors.toList())));

        // 3. 一次查 session
        List<Long> sessionIds = orders.stream().map(Order::getSessionId).distinct().toList();
        Map<Long, Session> sessionMap = sessionMapper.selectBatchIds(sessionIds)
                .stream().collect(Collectors.toMap(Session::getId, Function.identity()));

        // 4. 一次查 movie
        List<Long> movieIds = sessionMap.values().stream()
                .map(Session::getMovieId).distinct().toList();
        Map<Long, Movie> movieMap = movieIds.isEmpty() ? Map.of() :
                movieMapper.selectBatchIds(movieIds)
                        .stream().collect(Collectors.toMap(Movie::getId, Function.identity()));

        // 5. 一次查 hall
        List<Long> hallIds = sessionMap.values().stream()
                .map(Session::getHallId).distinct().toList();
        Map<Long, Hall> hallMap = hallIds.isEmpty() ? Map.of() :
                hallMapper.selectBatchIds(hallIds)
                        .stream().collect(Collectors.toMap(Hall::getId, Function.identity()));

        // 6. 一次查 seat
        List<Integer> allSeatIdx = orderSeatMap.values().stream()
                .flatMap(List::stream).distinct().toList();
        Map<String, Seat> seatKey = new HashMap<>();
        if (!allSeatIdx.isEmpty() && !hallIds.isEmpty()) {
            seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                            .in(Seat::getHallId, hallIds)
                            .in(Seat::getSeatIndex, allSeatIdx))
                    .forEach(s -> seatKey.put(s.getHallId() + ":" + s.getSeatIndex(), s));
        }

        Page<OrderVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(orders.stream()
                .map(o -> toVOBatched(o,
                        orderSeatMap.getOrDefault(o.getId(), List.of()),
                        sessionMap, movieMap, hallMap, seatKey))
                .toList());
        return result;
    }

    public OrderVO detail(String orderNo, Long userId) {
        Order order = orderCore.getOwnedOrder(orderNo, userId);
        List<Integer> seatIndexes = orderCore.seatIndexesOf(order);
        OrderVO vo = toVOBase(order, seatIndexes);
        fillSessionInfo(vo, order, seatIndexes);
        return vo;
    }

    /** 批量组装 VO: 6 SQL 路径, 与 myOrders 共享 */
    private OrderVO toVOBatched(Order order, List<Integer> seatIndexes,
                                Map<Long, Session> sessionMap,
                                Map<Long, Movie> movieMap,
                                Map<Long, Hall> hallMap,
                                Map<String, Seat> seatKey) {
        OrderVO vo = toVOBase(order, seatIndexes);
        Session session = sessionMap.get(order.getSessionId());
        if (session != null) {
            Movie movie = movieMap.get(session.getMovieId());
            Hall hall = hallMap.get(session.getHallId());
            vo.setMovieTitle(movie == null ? "" : movie.getTitle());
            vo.setHallName(hall == null ? "" : hall.getName());
            vo.setStartTime(session.getStartTime());
            if (!seatIndexes.isEmpty() && hall != null) {
                StringBuilder sb = new StringBuilder();
                List<Integer> sorted = seatIndexes.stream().sorted().toList();
                for (int i = 0; i < sorted.size(); i++) {
                    if (i > 0) sb.append("、");
                    Seat s = seatKey.get(hall.getId() + ":" + sorted.get(i));
                    if (s != null) sb.append(s.getRowNo()).append("排").append(s.getColNo()).append("座");
                }
                vo.setSeatDesc(sb.toString());
            }
        }
        return vo;
    }

    private OrderVO toVOBase(Order order, List<Integer> seatIndexes) {
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setSessionId(order.getSessionId());
        vo.setStatus(order.getStatus());
        vo.setStatusText(OrderStatus.textOf(order.getStatus()));
        vo.setTotalAmount(order.getTotalAmount());
        vo.setSeatCount(order.getSeatCount());
        vo.setExpireAt(order.getExpireAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setSeatIndexes(seatIndexes);
        return vo;
    }

    /** 单订单详情补 session/movie/hall/seat 信息 */
    private void fillSessionInfo(OrderVO vo, Order order, List<Integer> seatIndexes) {
        Session session = sessionMapper.selectById(order.getSessionId());
        if (session == null) return;
        Movie movie = movieMapper.selectById(session.getMovieId());
        Hall hall = hallMapper.selectById(session.getHallId());
        vo.setMovieTitle(movie == null ? "" : movie.getTitle());
        vo.setHallName(hall == null ? "" : hall.getName());
        vo.setStartTime(session.getStartTime());
        vo.setSeatDesc(buildSeatDesc(session, seatIndexes));
    }

    private String buildSeatDesc(Session session, List<Integer> seatIndexes) {
        if (session == null || seatIndexes.isEmpty()) {
            return "";
        }
        return seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, session.getHallId())
                        .in(Seat::getSeatIndex, seatIndexes))
                .stream()
                .sorted(Comparator.comparing(Seat::getSeatIndex))
                .map(s -> s.getRowNo() + "排" + s.getColNo() + "座")
                .collect(Collectors.joining("、"));
    }
}
