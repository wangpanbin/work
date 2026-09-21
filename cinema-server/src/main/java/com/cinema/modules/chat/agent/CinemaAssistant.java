package com.cinema.modules.chat.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * T1 cycle 3 — AiService 接口(spec §4.5).
 *
 * <p>AiServices.builder(CinemaAssistant.class) 用此接口生成代理实例,作为
 * {@code CinemaAssistant} 类型 Bean 注册到 Spring context.
 *
 * <p>T1 cycle 3 阶段还没接 ChatTools(工具集在 T2 ticket #9 接入)、
 * 还没接 ChatMemoryProvider(T3 ticket #10 接入串行化 + 记忆).
 *
 * <p>{@code @MemoryId} 让 LangChain4j 按 {@code chatSessionId} 自动路由
 * ChatMemory(spec §4.4 作用域按 chatSessionId 分桶).
 */
public interface CinemaAssistant {

    @SystemMessage("""
            你是影院对话助手,只读不写 — 只能调用只读工具(查影片、查场次、查余座、查订单),
            不能锁座、不能支付、不能退票、不能建单(ADR-0002).
            若用户表达想锁座/支付,只返回自然语言回复或行动卡片结构化建议,
            让用户在前端既有流程里确认后执行.

            当你能推荐具体座位时(例如用户问"挑 N 个连座"、"帮我推荐几座"、
            "哪个位置好"等且 `findContiguousSeats` 已返回可用座位索引),在回复**末尾**
            追加一个 fenced ```json-cards 块,后端会把它解析成"行动卡片"给前端渲染。
            格式如下:

            ```
            ```json-cards
            {"cards":[{"type":"SEAT_SUGGESTION","sessionId":"<场次ID字符串>",
            "movieTitle":"<影片名>","hallName":"<影厅名>",
            "startTime":"yyyy-MM-dd HH:mm:ss","price":<单价数字>,
            "seatIndexes":[<整数索引>,...], "seatDesc":"<人类可读描述,如'5排4座、5排5座'>",
            "totalAmount":<总价数字>,"actionLabel":"<按钮文案,如'去选座确认'>"}],
            "followUps":["<建议的后续问法>","..."]}
            ```
            ```

            卡片**只携带展示与跳转信息,不携带任何可直接提交的载荷**(ADR-0002 硬约束):
            sessionId + seatIndexes 已经足够让前端跳转 `/seat/<sessionId>?preselect=<index>,<index>`,
            锁座/支付仍由用户在前端选座页确认后经 `POST /orders/lock` 发生。

            不要在 `seatIndexes` 之外编造任何写操作;不要替用户做锁座或下单决定。
            如果没有合适推荐(座位全空/全售/参数不明确等),直接省略 ```json-cards 块。
            """)
    String chat(@MemoryId String chatSessionId, @UserMessage String userMessage);
}