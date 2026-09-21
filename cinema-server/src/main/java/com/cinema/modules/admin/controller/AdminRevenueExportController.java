package com.cinema.modules.admin.controller;

import com.cinema.modules.admin.dto.RevenueExportQuery;
import com.cinema.modules.admin.service.AdminRevenueExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * T1 营收导出端点.
 *
 * <p>响应是裸二进制流 ({@code application/vnd.openxmlformats-officedocument.spreadsheetml.sheet}),
 * 不走 {@code R<>} 包装; 业务异常仍由 {@code GlobalExceptionHandler} 转 JSON (此时 response 还未 commit).
 *
 * <p>鉴权复用现有 {@code AdminInterceptor}: 该拦截器已对 {@code /api/admin/**} 全部生效, role=1 才能进.
 */
@RestController
@RequestMapping("/api/admin/revenue")
@RequiredArgsConstructor
public class AdminRevenueExportController {

    private final AdminRevenueExportService exportService;

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @GetMapping("/export")
    public void export(@RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String mode,
                       HttpServletResponse response) throws IOException {
        // 1. 拼 query + 校验/补默认 (BizException 抛出后由 GlobalExceptionHandler 接管, 此时 response 未 commit)
        RevenueExportQuery query = new RevenueExportQuery();
        query.setMode(mode);
        query.setFrom(from);
        query.setTo(to);
        query.normalize();

        // 2. 渲染字节流 (mapper + EasyExcel); 此处抛 BizException 也由 GlobalExceptionHandler 接管
        byte[] bytes = exportService.renderBytes(query);

        // 3. 设置响应头 + 写流 (第一次写 OutputStream 才 commit response)
        //    Header value 按 RFC 7230 §3.2.4 默认 ISO-8859-1; 汉字 (U+8425 营, U+81F3 至 ...) 直接
        //    拼进 filename="..." 段会让 Tomcat 在 MessageBytes.toBytes() 抛 IAE.
        //    修法: ASCII fallback 用纯英文+日期, 完整中文名走 RFC 5987 filename*=UTF-8'' 段
        //    (现代浏览器 / Chrome / Firefox / Safari / Edge / wget 都支持).
        String displayName = "营收报表_" + query.getFrom() + "_至_" + query.getTo() + ".xlsx";
        String asciiFallback = "revenue_" + query.getFrom() + "_" + query.getTo() + ".xlsx";
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded);

        try (OutputStream os = response.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
    }
}