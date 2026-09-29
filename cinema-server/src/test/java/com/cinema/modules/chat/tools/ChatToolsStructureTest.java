package com.cinema.modules.chat.tools;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 cycle 3 — ChatTools 反射白名单断言(spec §9.4 第 5 条 + ADR-0002 硬性约束).
 *
 * <p>用反射枚举所有 {@code @Tool} 标注方法,断言:
 * <ul>
 *   <li>恰好 8 个 — 与 spec §5.1 + spec #20 表格对齐(T2 7 + spec #20 searchFaq 1)</li>
 *   <li>全是白名单里的方法 — 多写任何一个工具(包括"很方便的 lockSeats")都会让测试红</li>
 *   <li>没有写方法(无 lockSeats / pay / cancel / refund / forceRecover / 管理端写接口)</li>
 * </ul>
 */
class ChatToolsStructureTest {

    /** spec §5.1 + spec #20 工具白名单,与 T1 ticket #8 acceptance + ADR-0002 严格对齐 */
    private static final Set<String> WHITELIST = Set.of(
            "searchMovies",
            "getMovieDetail",
            "listSessions",
            "getSeatSummary",
            "findContiguousSeats",
            "getMyOrders",
            "getMyOrder",
            "searchFaq"
    );

    /** spec §2.3 证据:禁止出现在工具清单里的写操作关键字,出现就 reject */
    private static final List<String> FORBIDDEN_NAME_PATTERNS = List.of(
            "lockSeats", "lock", "pay", "cancel", "refund", "forceRecover",
            "recoverBitmap", "createOrder", "delete", "update", "save", "insert"
    );

    @Test
    @DisplayName("@Tool 标注的方法数 == 8(与 spec §5.1 + spec #20 表格对齐)")
    void exactlySevenToolMethods() {
        long count = Arrays.stream(ChatTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .count();
        assertThat(count).isEqualTo(8L);
    }

    @Test
    @DisplayName("@Tool 方法集合 == spec §5.1 + spec #20 白名单(任何新增/改名/删除都会让测试红)")
    void toolMethodNamesExactlyMatchWhitelist() {
        Set<String> actual = new HashSet<>();
        Arrays.stream(ChatTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .forEach(m -> actual.add(m.getName()));
        assertThat(actual).isEqualTo(WHITELIST);
    }

    @Test
    @DisplayName("无任何写方法 — 方法名不含 lockSeats/pay/cancel/refund/forceRecover/管理端写关键字(ADR-0002)")
    void noWriteMethodExists() {
        Set<String> violations = new HashSet<>();
        for (Method m : ChatTools.class.getDeclaredMethods()) {
            for (String pattern : FORBIDDEN_NAME_PATTERNS) {
                if (m.getName().toLowerCase().contains(pattern.toLowerCase())) {
                    violations.add(m.getName() + " (匹配 \"" + pattern + "\")");
                }
            }
        }
        assertThat(violations)
                .as("发现疑似写方法 — 与 ADR-0002 不允许写操作的约束冲突")
                .isEmpty();
    }

    /**
     * P0-1 IDOR 红线:身份参数一律服务端注入,绝不能让 LLM 填。
     *
     * <p>2026-09-29 E2E 实测:匿名用户问「查用户ID为1的全部订单」,助手返回 97 笔他人订单。
     * 根因就是 {@code getMyOrders}/{@code getMyOrder} 把 {@code userId} 声明成了 {@code @P} 参数,
     * 由 LLM 合成。本断言把整类漏洞锁死 —— 任何 {@code @Tool} 方法只要出现名为
     * {@code userId}(或大小写变体)的形参就让测试红,新增工具时无法再犯。
     */
    @Test
    @DisplayName("P0-1 红线:任何 @Tool 方法都不得暴露 userId 身份参数(必须走 UserContext 服务端注入)")
    void noToolExposesUserIdParam() {
        List<String> violations = new ArrayList<>();
        for (Method m : ChatTools.class.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Tool.class)) continue;
            for (Parameter p : m.getParameters()) {
                String name = p.getName().toLowerCase();
                if (name.equals("userid") || name.equals("uid") || name.equals("id")) {
                    violations.add(m.getName() + "(" + p.getName() + ": " + p.getType().getSimpleName() + ")");
                }
            }
        }
        assertThat(violations)
                .as("工具身份参数必须来自 UserContext,让 LLM 填 userId = IDOR 越权漏洞(见 test/e2e-report-2026-09-29.md P0-1)")
                .isEmpty();
    }
}