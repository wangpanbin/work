package com.cinema.modules.order.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 营收明细行 (T1): 一个已支付订单.
 *
 * <p>{@code @ExcelProperty} 即中文表头，EasyExcel 自动按字段顺序写入。
 * 日期/金额走 EasyExcel 内置 converter。
 *
 * <p>包归属说明：本 VO 同时承载 {@code OrderMapper.selectRevenueRows} 的返回类型与 Excel 写出模型,
 * 放在 order.vo 避免 mapper 反向依赖 admin.dto.
 */
@Data
public class RevenueRowVO {

    @ExcelProperty("订单号")
    @ColumnWidth(22)
    private String orderNo;

    @ExcelProperty("用户")
    @ColumnWidth(14)
    private String username;

    @ExcelProperty("影片")
    @ColumnWidth(22)
    private String movieTitle;

    @ExcelProperty("影厅")
    @ColumnWidth(14)
    private String hallName;

    @ExcelProperty("开场时间")
    @ColumnWidth(20)
    private LocalDateTime startTime;

    @ExcelProperty("座位数")
    @ColumnWidth(8)
    private Integer seatCount;

    @ExcelProperty("订单金额(元)")
    @ColumnWidth(14)
    private BigDecimal totalAmount;

    @ExcelProperty("支付时间")
    @ColumnWidth(20)
    private LocalDateTime paidAt;

    @ExcelProperty("状态")
    @ColumnWidth(8)
    private Integer status;
}