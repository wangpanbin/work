package com.cinema.modules.admin.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.admin.dto.RevenueExportQuery;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.vo.RevenueRowVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * T1 营收导出服务.
 *
 * <p>三步独立: 校验补默认 → 拉行 → 渲染字节流. 每步可单独替换实现 (如换图表库 / 换 SQL) 而不影响其它步.
 *
 * <p>为什么返回 byte[] 而不是写 OutputStream:
 * 若 service 写外层流到一半抛 BizException, GlobalExceptionHandler 之后写 JSON body
 * 会因 response 已 commit 而抛 IllegalStateException 或得到半截损坏 .xlsx.
 * 把"写流"留给 controller, 失败时 advice 接管 JSON 写入, 此时 response 未 commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRevenueExportService {

    private final OrderMapper orderMapper;

    /**
     * 编排: 校验 → 拉行 → 渲染.
     *
     * @throws BizException query 为 null / 起始日期晚于结束日期 / Excel 生成失败
     */
    public byte[] renderBytes(RevenueExportQuery query) {
        validate(query);
        List<RevenueRowVO> rows = fetchRows(query);
        return renderToBytes(rows);
    }

    /** ① query 非空 + 补默认 + 范围校验. 失败抛 BizException(40001). */
    private void validate(RevenueExportQuery query) {
        if (query == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "查询参数为空");
        }
        query.normalize();
    }

    /** ② mapper 拉行; 含端点 → 半开区间由 mapper SQL 内部 `DATE_ADD(..., INTERVAL 1 DAY)` 处理. */
    private List<RevenueRowVO> fetchRows(RevenueExportQuery query) {
        List<RevenueRowVO> rows = orderMapper.selectRevenueRows(query.getFrom(), query.getTo());
        log.info("营收导出: from={}, to={}, rows={}", query.getFrom(), query.getTo(),
                rows == null ? 0 : rows.size());
        return rows == null ? List.of() : rows;
    }

    /** ③ EasyExcel 写到内存字节流. 写失败抛 BizException(50000). */
    private byte[] renderToBytes(List<RevenueRowVO> rows) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        try (ExcelWriter writer = EasyExcel.write(buf, RevenueRowVO.class).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(0, "营收明细").build();
            writer.write(rows, sheet);
        } catch (Exception e) {
            log.error("EasyExcel 写入失败", e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "Excel 生成失败");
        }
        return buf.toByteArray();
    }
}