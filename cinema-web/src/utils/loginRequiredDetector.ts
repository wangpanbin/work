/**
 * loginRequiredDetector — 检测 AI 回复是否含 "需要登录" 提示(spec #17 ID-4).
 *
 * <p>纯函数,无副作用,可独立测试。
 *
 * <p>关键不变量:
 * <ul>
 *   <li>文本匹配 "先登录" / "登录后" / "未登录" / "LOGIN_REQUIRED" 任一即视为需要登录</li>
 *   <li>空串 / 普通对话 → false,避免误报</li>
 *   <li>LLM 输出不可信,需要防御性匹配 — "LOGIN_REQUIRED" 虽不应直接出现,
 *       但工具层万一透出仍要能识别</li>
 * </ul>
 *
 * <p><b>措辞要放宽</b>(E2E 2026-09-29 P2-5):系统提示词只引导 LLM 说"请先登录",
 * 但模型实际可能说"需要先登录"/"您尚未登录"。这些说法在正常对话里不会出现,
 * 所以全部纳入匹配,避免该弹引导框时漏弹。
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
  return /先登录|登录后|未登录|尚未登录|需要登录|LOGIN_REQUIRED/.test(reply)
}