package com.cinema.modules.admin.dto;

import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import lombok.Data;

import java.time.LocalDate;

/**
 * 营收导出请求参数 (T1).
 *
 * <p>「mode」为前端 UI 状态语义（preset/custom），后端只关心 from/to 两个日期；
 * 范围补默认（最近 7 日）由 {@link #normalize()} 统一处理, controller 与 service 复用.
 */
@Data
public class RevenueExportQuery {

    /** "preset" | "custom" | null; 后端不再二次推断，仅调试可见 */
    private String mode;

    /** 含端点 (paid_at >= from 00:00:00) */
    private LocalDate from;

    /** 含端点 (paid_at <= to 当天 23:59:59.999) */
    private LocalDate to;

    /**
     * 补默认 + 校验. controller 调一次用于拼文件名, service 调一次用于拉数据,
     * 任何位置调一次即可保证 from/to 在 (today-6, today) 区间内且合法.
     *
     * @throws BizException 起始日期晚于结束日期时
     */
    public void normalize() {
        LocalDate today = LocalDate.now();
        if (from == null) {
            from = today.minusDays(6);
        }
        if (to == null) {
            to = today;
        }
        if (from.isAfter(to)) {
            throw new BizException(ResultCode.BAD_REQUEST, "起始日期不能晚于结束日期");
        }
    }
}