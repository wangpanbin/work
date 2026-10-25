/**
 * loginRequiredDetector — 检测 AI 回复是否含 "需要登录" 提示(spec #17 ID-4)。
 *
 * <p>纯函数,无副作用,可独立测试。
 *
 * <p>关键不变量:
 * <ul>
 *   <li>文本命中「要登录」类措辞即视为需要登录</li>
 *   <li>空串 / 普通对话 → false,避免误报</li>
 *   <li>LLM 输出不可信,需要防御性匹配 — "LOGIN_REQUIRED" 虽不应直接出现,
 *       但工具层万一透出仍要能识别</li>
 * </ul>
 *
 * <p><b>措辞要放宽</b>(E2E 2026-09-29 P2-5):系统提示词只引导 LLM 说"请先登录",
 * 但模型实际可能说"需要先登录"/"您尚未登录"。这些说法在正常对话里不会出现,
 * 所以全部纳入匹配,避免该弹引导框时漏弹。
 *
 * <p><b>再放宽一档</b>(交互流程审查 2026-10-25 批次四):模型还会说
 * 「需要先完成登录」——「先」和「登录」中间夹着「完成」, 原来那条正则匹配不上,
 * 引导框就不弹, 用户不知道自己要先登录。
 *
 * <p>⚠️ <b>放宽的边界</b>:不能只看「登录」两个字 —— 影片简介里也有
 * 「主角要登录银行系统」这种普通叙述。所以规则拆成「限定词 + 填充词 + 登录」,
 * 限定词走白名单, 白名单里**没有**「要」「得」——
 * 「主角要登录银行系统」「我觉得登录体验不好」都属普通叙述, 命中就是误报。
 *
 * <p><b>抽成独立 .ts 的原因</b>:Vue 3 SFC 的 <script setup> 不允许
 * ES module export。要给 ChatWidget.vue 调用 + 让 vitest 直接测,只能放
 * 独立 .ts 文件(参考 actionCardRoute.ts 的双文件模式)。
 *
 * @param reply - AI 回复文本(可能为 null/undefined,会被视为空)
 * @returns true 表示需要登录(应触发引导弹窗)
 */

/**
 * 要求词 —— 出现在「登录」前面的限定词。
 *
 * <p>⚠️ 白名单里**没有**「要」「得」:
 * 「主角要登录银行系统」「我觉得登录体验不好」是普通叙述, 命中就是误报。
 */
const REQUIREMENT_WORDS = '(?:需要|请|必须|应该|重新)'

/** 填充词 —— 要求词与「登录」之间允许夹的词(模型爱加的口语填充)。 */
const FILLER_WORDS = '(?:先|再|您|你|一下|完成|进行|尽快|马上|重新)*'

const LOGIN_REQUIRED_RE = new RegExp([
  // ① 直接措辞
  '先登录',
  '登录后',
  '未登录',
  '尚未登录',
  '需要登录',
  'LOGIN_REQUIRED',
  // ② 要求词 + 填充词 + 登录:需要先完成登录 / 请先完成登录 / 需要您登录 / 请重新登录
  `${REQUIREMENT_WORDS}${FILLER_WORDS}登录`,
  // ③ 填充词 + 完成登录:先完成登录 / 完成登录
  `${FILLER_WORDS}完成登录`,
  // ④ 登录 + 填充词 + (后|才能|方可|即可):登录完成后才能查看
  `登录${FILLER_WORDS}(?:后|才能|方可|即可)`,
  // ⑤ 英文界面(模型切英文时也是这么措辞)
  'log ?in first',
  '(?:please|must|need to|should) log ?in',
  'not logged in',
  'login required',
  // ⚠️ `i` 标志: 模型在英文语境下会用句首大写(「Please Log In to continue」),
  // 没有它就只认全小写 —— 引导框不弹, 用户不知道自己要先登录。
  // 中文部分不受大小写标志影响。
].join('|'), 'i')

export function detectLoginRequired(reply: string | null | undefined): boolean {
  if (!reply) return false
  return LOGIN_REQUIRED_RE.test(reply)
}
