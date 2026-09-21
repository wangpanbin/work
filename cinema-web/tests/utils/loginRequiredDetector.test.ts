import { describe, it, expect } from 'vitest'
import { detectLoginRequired } from '../../src/utils/loginRequiredDetector'

/**
 * loginRequiredDetector 纯函数测试(spec #17 ID-4 + TD-2).
 *
 * <p>关键不变量:
 * <ul>
 *   <li>文本匹配 "请先登录" / "登录后" / "LOGIN_REQUIRED" 任一即视为需要登录</li>
 *   <li>空串 / 普通对话 → false,避免误报</li>
 *   <li>LLM 输出不可信,需要防御性匹配 — "LOGIN_REQUIRED" 虽不应直接出现,
 *       但工具层万一透出仍要能识别</li>
 * </ul>
 *
 * <p>抽成独立 .ts 是为了走 vue-test-utils-free 测试路径
 * (参考 actionCardRoute.ts 的双文件模式 — ActionCard.vue 不能 export 函数)。
 */
describe('loginRequiredDetector (spec #17 ID-4)', () => {
  it('命中 "请先登录后再查看订单" → true', () => {
    expect(detectLoginRequired('请先登录后再查看订单')).toBe(true)
  })

  it('命中 "您还未登录,请先登录" → true', () => {
    expect(detectLoginRequired('您还未登录,请先登录')).toBe(true)
  })

  it('命中 "LOGIN_REQUIRED"(防御性,工具层透出场景) → true', () => {
    expect(detectLoginRequired('LOGIN_REQUIRED')).toBe(true)
  })

  it('命中 "登录后可以查看" → true', () => {
    expect(detectLoginRequired('登录后可以查看您的订单')).toBe(true)
  })

  it('空串 → false(无文本,不弹窗)', () => {
    expect(detectLoginRequired('')).toBe(false)
  })

  it('普通对话 "今天天气不错" → false', () => {
    expect(detectLoginRequired('今天天气不错,有什么推荐吗?')).toBe(false)
  })

  it('纯问句 "怎么买票" → false', () => {
    expect(detectLoginRequired('怎么买票?')).toBe(false)
  })
})