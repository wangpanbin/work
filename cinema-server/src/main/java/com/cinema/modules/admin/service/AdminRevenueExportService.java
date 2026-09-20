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
 * <p>职责单一: ① 复用 {@code RevenueExportQuery.normalize()} 校验+补默认;
 * ② 调 mapper 拉区间数据; ③ EasyExcel 写到内存字节流 (返回 byte[]).
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
     * @param query 入参; from/to 任一为 null 时按"最近 7 日"兜底
     * @return 完整的 .xlsx 字节流 (UTF-8 BOM 由 EasyExcel 自动写入 sheet 头)
     * @throws BizException 起始日期晚于结束日期 / Excel 生成失败
     */
    public byte[] renderBytes(RevenueExportQuery query) {
        if (query == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "查询参数为空");
        }
        // 1. 补默认 + 校验 (query 内部抛 BizException, 此处不重复)
        query.normalize();

        // 2. 拉数据: 半开区间由 mapper 内部 `+1 day` 处理
        List<RevenueRowVO> rows = orderMapper.selectRevenueRows(query.getFrom(), query.getTo());
        log.info("营收导出: from={}, to={}, rows={}", query.getFrom(), query.getTo(),
                rows == null ? 0 : rows.size());

        // 3. EasyExcel 写到内存
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