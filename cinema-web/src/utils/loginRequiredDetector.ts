/**
 * loginRequiredDetector — 检测 AI 回复是否含 "需要登录" 提示(spec #17 ID-4).
 *
 * <p>纯函数,无副作用,可独立测试。
 *
 * <p>关键不变量:
 * <ul>
 *   <li>文本匹配 "请先登录" / "登录后" / "LOGIN_REQUIRED" 任一即视为需要登录</li>
 *   <li>空串 / 普通对话 → false,避免误报</li>
 *   <li>LLM 输出不可信,需要防御性匹配 — "LOGIN_REQUIRED" 虽不应直接出现,
 *       但工具层万一透出仍要能识别</li>
 * </ul>
 *
 * <p><b>抽成独立 .ts 的原因</b>:Vue 3 SFC 的 {@code <script setup>} 不允许
 * ES module export。要给 ChatWidget.vue 调用 + 让 vitest 直接测,只能放
 * 独立 .ts 文件(参考 actionCardRoute.ts 的双文件模式)。
 *
 * @param reply - AI 回复文本(可能为 null/undefined,会被视为空)
 * @returns true 表示需要登录(应触发引导弹窗)
 */
export function detectLoginRequired(reply: string | null | undefined): boolean {
  if (!reply) return false
  return /请先登录|LOGIN_REQUIRED|登录后/.test(reply)
}