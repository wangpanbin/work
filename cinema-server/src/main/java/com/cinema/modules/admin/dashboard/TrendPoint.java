package com.cinema.modules.admin.dashboard;

/**
 * 7 日票房趋势的一个点。
 *
 * <p><b>为什么要有这个类型</b>: {@code weeklyTrend} 此前是 {@code List<Map<String,Object>>} ——
 * 字段名只能靠字符串字面量约定, 编译器不认, 拼错了要到运行时才发现。
 * 换成强类型 record 后, 字段名由类型保证。
 *
 * <p><b>wire 格式逐字节不变</b>: record 的组件顺序即 JSON 字段顺序,
 * 类型是 {@code String} / {@code Long}, 与原先 {@code LinkedHashMap} 里放的两个值完全一致 ——
 * 序列化结果仍是 {@code {"date":"2026-10-19","amount":123}}, 前端 {@code Dashboard.vue} 零改动。
 */
public record TrendPoint(String date, Long amount) {
}
