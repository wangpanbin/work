package com.cinema.modules.admin.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.cinema.common.exception.BizException;
import com.cinema.modules.admin.dto.RevenueExportQuery;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.vo.RevenueRowVO;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AdminRevenueExportService 单测 — T2. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminRevenueExportServiceTest {

    @Mock private OrderMapper orderMapper;
    private AdminRevenueExportService service;

    @BeforeEach
    void setUp() {
        service = new AdminRevenueExportService(orderMapper);
    }

    @Test
    @DisplayName("正常路径: 3 行数据 → 字节流以 PK\\03\\04 开头, 二次解析能读到 4 行 (1 表头 + 3 数据)")
    void renderBytes_basic() {
        when(orderMapper.selectRevenueRows(any(), any()))
                .thenReturn(List.of(
                        row("ORD-1", "user1", "阿凡达", "1号厅", 2, "79.80"),
                        row("ORD-2", "user2", "星际穿越", "2号厅", 1, "39.90"),
                        row("ORD-3", "user1", "阿凡达", "1号厅", 3, "119.70")
                ));

        RevenueExportQuery q = new RevenueExportQuery();
        q.setMode("preset");
        q.setFrom(LocalDate.of(2026, 9, 14));
        q.setTo(LocalDate.of(2026, 9, 20));

        byte[] bytes = service.renderBytes(q);

        assertThat(bytes).isNotEmpty();
        // ZIP magic: "PK\03\04"
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
        assertThat(bytes[2]).isEqualTo((byte) 0x03);
        assertThat(bytes[3]).isEqualTo((byte) 0x04);

        // 二次解析: headRowNumber=0 让所有行 (含表头) 都进 invoke
        List<Integer> rowCount = new ArrayList<>();
        EasyExcel.read(new ByteArrayInputStream(bytes), HeadRow.class, new ReadListener<HeadRow>() {
            @Override
            public void invoke(HeadRow data, AnalysisContext context) {
                rowCount.add(9);
            }
            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {}
        }).headRowNumber(0).sheet().doRead();

        // 1 表头 + 3 数据 = 4 行
        assertThat(rowCount).hasSize(4);
    }

    @Test
    @DisplayName("表头: 9 列中文与 RevenueRowVO @ExcelProperty 一致")
    void renderBytes_headers() {
        when(orderMapper.selectRevenueRows(any(), any())).thenReturn(new ArrayList<>());

        RevenueExportQuery q = new RevenueExportQuery();
        q.setFrom(LocalDate.of(2026, 9, 14));
        q.setTo(LocalDate.of(2026, 9, 20));

        byte[] bytes = service.renderBytes(q);

        // headRowNumber=0 时第 0 行(原始表头)进 listener 当作数据; mapper 返回空时只有 1 行(表头)
        final List<HeadRow> all = new ArrayList<>();
        EasyExcel.read(new ByteArrayInputStream(bytes), HeadRow.class, new ReadListener<HeadRow>() {
            @Override
            public void invoke(HeadRow data, AnalysisContext context) {
                all.add(data);
            }
            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {}
        }).headRowNumber(0).sheet().doRead();

        assertThat(all).hasSize(1); // 0 数据 + 1 表头
        HeadRow header = all.get(0);
        assertThat(header.getOrderNo()).isEqualTo("订单号");
        assertThat(header.getUsername()).isEqualTo("用户");
        assertThat(header.getMovieTitle()).isEqualTo("影片");
        assertThat(header.getHallName()).isEqualTo("影厅");
        assertThat(header.getStartTime()).isEqualTo("开场时间");
        assertThat(header.getSeatCount()).isEqualTo("座位数");
        assertThat(header.getTotalAmount()).isEqualTo("订单金额(元)");
        assertThat(header.getPaidAt()).isEqualTo("支付时间");
        assertThat(header.getStatus()).isEqualTo("状态");
    }

    @Test
    @DisplayName("入参校验: from > to 抛 BizException")
    void renderBytes_invalidRange() {
        RevenueExportQuery q = new RevenueExportQuery();
        q.setFrom(LocalDate.now());
        q.setTo(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.renderBytes(q))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("起始日期不能晚于结束日期");
    }

    @Test
    @DisplayName("缺省: from/to 都为 null 时, mapper 收到 today-6 / today")
    void renderBytes_defaultRange() {
        when(orderMapper.selectRevenueRows(any(), any())).thenReturn(new ArrayList<>());

        RevenueExportQuery q = new RevenueExportQuery();
        // from/to 故意为 null, 由 normalize() 兜底

        service.renderBytes(q);

        ArgumentCaptor<LocalDate> fromCap = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCap = ArgumentCaptor.forClass(LocalDate.class);
        verify(orderMapper).selectRevenueRows(fromCap.capture(), toCap.capture());

        LocalDate fromCaptured = fromCap.getValue();
        LocalDate toCaptured = toCap.getValue();
        LocalDate today = LocalDate.now();

        // mapper 入参是 LocalDate (含端点), +1 day 由 mapper SQL 内部处理
        assertThat(fromCaptured).isEqualTo(today.minusDays(6));
        assertThat(toCaptured).isEqualTo(today);
    }

    @Test
    @DisplayName("null query: 抛 BizException")
    void renderBytes_nullQuery() {
        assertThatThrownBy(() -> service.renderBytes(null))
                .isInstanceOf(BizException.class);
    }

    // ---- helpers ----

    private RevenueRowVO row(String orderNo, String username, String movie, String hall,
                             int seatCount, String totalAmount) {
        RevenueRowVO v = new RevenueRowVO();
        v.setOrderNo(orderNo);
        v.setUsername(username);
        v.setMovieTitle(movie);
        v.setHallName(hall);
        v.setStartTime(LocalDateTime.of(2026, 9, 18, 19, 30));
        v.setSeatCount(seatCount);
        v.setTotalAmount(new BigDecimal(totalAmount));
        v.setPaidAt(LocalDateTime.of(2026, 9, 18, 19, 35));
        v.setStatus(1);
        return v;
    }

    /** 二次解析用的 headClass; 字段顺序和注解与 RevenueRowVO 完全一致 */
    @Data
    public static class HeadRow {
        @ExcelProperty("订单号") private String orderNo;
        @ExcelProperty("用户")   private String username;
        @ExcelProperty("影片")   private String movieTitle;
        @ExcelProperty("影厅")   private String hallName;
        @ExcelProperty("开场时间") private String startTime;
        @ExcelProperty("座位数") private String seatCount;
        @ExcelProperty("订单金额(元)") private String totalAmount;
        @ExcelProperty("支付时间") private String paidAt;
        @ExcelProperty("状态")   private String status;
    }
}