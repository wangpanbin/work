package com.cinema.modules.admin.controller;

import com.cinema.modules.admin.service.AdminRevenueExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3 — AdminRevenueExportController 单测.
 *
 * <p>回归点: {@code Content-Disposition} header 的 {@code filename="..."} 段必须是
 * ISO-8859-1 安全子集 (ASCII ≤ 0x7F), 否则 Tomcat 在 commit response 时会抛
 * {@code IllegalArgumentException: The Unicode character [...] cannot be encoded as
 * it is outside the permitted range of 0 to 255}.
 *
 * <p>中文全名走 RFC 5987 {@code filename*=UTF-8''...} 段, 这部分 percent-encoded
 * 后天然全是 ASCII, Tomcat 不会拦截.
 */
@ExtendWith(MockitoExtension.class)
class AdminRevenueExportControllerTest {

    @Mock private AdminRevenueExportService exportService;
    @Mock private HttpServletResponse response;

    private AdminRevenueExportController controller;
    private ArgumentCaptor<String> headerCaptor;

    @BeforeEach
    void setUp() throws Exception {
        controller = new AdminRevenueExportController(exportService);
        headerCaptor = ArgumentCaptor.forClass(String.class);
        // response.getOutputStream() 在 export() 里被用到; 给一个空字节数组写出即可
        when(response.getOutputStream()).thenReturn(new jakarta.servlet.ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener writeListener) {}
            @Override public void write(int b) { /* swallow */ }
            @Override public void write(byte[] b) { /* swallow */ }
        });
    }

    @Test
    @DisplayName("正常路径: 中文 filename 不进 Content-Disposition ASCII 段, 整段 header 不抛 IAE")
    void export_setsAsciiSafeFilenameWithRfc5987Fallback() throws Exception {
        when(exportService.renderBytes(any())).thenReturn(new byte[]{1, 2, 3});

        controller.export(
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20),
                "preset",
                response);

        verify(response).setHeader(eq("Content-Disposition"), headerCaptor.capture());
        String headerValue = headerCaptor.getValue();

        // 契约 1: 整段 header 在 ISO-8859-1 (Latin-1) 下能无损 round-trip.
        // 这是 Tomcat MessageBytes.toBytes() 的实际检查 — 任何 char > 0xFF 都会爆.
        byte[] latin1 = headerValue.getBytes(StandardCharsets.ISO_8859_1);
        assertThat(new String(latin1, StandardCharsets.ISO_8859_1))
                .as("Content-Disposition header 必须在 ISO-8859-1 安全 (≤ 0xFF) 范围, 否则 Tomcat 写 header 时报 IAE")
                .isEqualTo(headerValue);

        // 契约 2: 解析出 filename="..." ASCII 段, 其值必须不含汉字等非 ASCII 字符.
        String asciiName = extractAsciiFilename(headerValue);
        assertThat(asciiName)
                .as("filename=\"...\" ASCII fallback 段必须非空且纯 ASCII")
                .isNotBlank();
        assertThat(asciiName)
                .as("filename=\"...\" ASCII fallback 段不能含汉字等非 ASCII 字符")
                .matches("[\\x20-\\x7E]+"); // 可打印 ASCII

        // 契约 3: 解析 filename*=UTF-8''... 段, 解码后必须等于完整中文全名.
        String decodedUtf8Name = extractRfc5987Filename(headerValue);
        String expectedFull = "营收报表_2026-09-14_至_2026-09-20.xlsx";
        assertThat(decodedUtf8Name)
                .as("filename*=UTF-8'' 段解码后必须等于完整中文名")
                .isEqualTo(expectedFull);
    }

    @Test
    @DisplayName("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    void export_setsCorrectContentType() throws Exception {
        when(exportService.renderBytes(any())).thenReturn(new byte[]{0});

        controller.export(
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20),
                "preset",
                response);

        verify(response).setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    @DisplayName("字节流: response 输出 service.renderBytes 返回的字节")
    void export_writesServiceBytes() throws Exception {
        byte[] payload = new byte[]{0x50, 0x4B, 0x03, 0x04}; // ZIP magic, mock xlsx
        when(exportService.renderBytes(any())).thenReturn(payload);

        controller.export(
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20),
                "preset",
                response);

        // 验证 setHeader 没抛 IAE (即上面的契约已通过), 且 service 被调用.
        verify(exportService).renderBytes(any());
    }

    // ---- helpers ----

    /** 从 {@code Content-Disposition} header 中解出 {@code filename="..."} ASCII 段. */
    static String extractAsciiFilename(String header) {
        int start = header.indexOf("filename=\"");
        if (start < 0) return null;
        start += "filename=\"".length();
        int end = header.indexOf('"', start);
        return end < 0 ? null : header.substring(start, end);
    }

    /** 从 {@code Content-Disposition} header 中解出 {@code filename*=UTF-8''...} 并 percent-decode. */
    static String extractRfc5987Filename(String header) {
        String marker = "filename*=UTF-8''";
        int start = header.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        // 终止于 header 末尾 (段尾) 或下一个分号
        int end = header.length();
        int semi = header.indexOf(';', start);
        if (semi >= 0 && semi < end) end = semi;
        String encoded = header.substring(start, end).trim();
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    /** Mockito 静态 import 包装, 方便阅读. */
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    // 抑制 OutputStream unused import 警告 (writer 用到)
    @SuppressWarnings("unused")
    private static final OutputStream UNUSED = null;
}