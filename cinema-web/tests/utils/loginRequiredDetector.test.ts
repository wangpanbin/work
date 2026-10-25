import { describe, it, expect } from 'vitest'
import { detectLoginRequired } from '../../src/utils/loginRequiredDetector'

/**
 * loginRequiredDetector 纯函数测试(spec #17 ID-4 + TD-2)。
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

  // P2-5:系统提示词只引导"请先登录",但模型措辞不可控,这些变体也必须能触发引导弹窗
  it('命中 "需要先登录才能查询" → true', () => {
    expect(detectLoginRequired('需要先登录才能查询订单')).toBe(true)
  })

  it('命中 "您尚未登录" → true', () => {
    expect(detectLoginRequired('您尚未登录,我无法查看订单')).toBe(true)
  })

  it('命中 "未登录状态" → true', () => {
    expect(detectLoginRequired('当前是未登录状态')).toBe(true)
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

/**
 * 措辞放宽(交互流程审查批次四)。
 *
 * <p>原正则漏判「需要先完成登录」这类说法 —— 模型这么一说, 引导弹窗就不弹,
 * 用户不知道自己要先登录。
 *
 * <p>放宽的**边界**: 不能只看「登录」两个字。影片简介里也有
 * 「主角要登录银行系统」这种普通叙述, 命中就是误报。
 * 所以正例(该弹)与反例(不该弹)必须一起加, 两者都在下面。
 */
describe('loginRequiredDetector — 措辞放宽(该弹的别漏)', () => {
  it('命中 "需要先完成登录后才能查询订单" → true', () => {
    expect(detectLoginRequired('需要先完成登录后才能查询订单')).toBe(true)
  })

  it('命中 "请先完成登录,再查看您的订单" → true', () => {
    expect(detectLoginRequired('请先完成登录,再查看您的订单')).toBe(true)
  })

  it('命中 "您需要先完成登录操作" → true', () => {
    expect(detectLoginRequired('您需要先完成登录操作')).toBe(true)
  })

  it('命中 "请完成登录" → true', () => {
    expect(detectLoginRequired('请完成登录')).toBe(true)
  })

  it('命中 "需要您登录后才能查订单" → true', () => {
    expect(detectLoginRequired('需要您登录后才能查订单')).toBe(true)
  })

  it('命中 "完成登录才能继续操作" → true', () => {
    expect(detectLoginRequired('完成登录才能继续操作')).toBe(true)
  })

  it('命中 "请重新登录" → true', () => {
    expect(detectLoginRequired('登录失败,请重新登录')).toBe(true)
  })

  it('命中英文 "Please log in first to view your orders" → true', () => {
    expect(detectLoginRequired('Please log in first to view your orders')).toBe(true)
  })

  it('命中英文 "You need to log in to see this order" → true', () => {
    expect(detectLoginRequired('You need to log in to see this order')).toBe(true)
  })

  it('命中英文句首大写 "Please Log In to continue" → true(大小写标志)', () => {
    expect(detectLoginRequired('Please Log In to continue')).toBe(true)
  })

  it('命中英文 "You Are Not Logged In" → true', () => {
    expect(detectLoginRequired('You Are Not Logged In')).toBe(true)
  })

  it('命中英文 "You are not logged in right now" → true', () => {
    expect(detectLoginRequired('You are not logged in right now')).toBe(true)
  })
})

describe('loginRequiredDetector — 措辞放宽(不该弹的别误报)', () => {
  it('"登录页面加载有点慢" → false(界面闲聊, 不是要求登录)', () => {
    expect(detectLoginRequired('登录页面加载有点慢')).toBe(false)
  })

  it('"主角要登录银行系统" → false(影片简介, 不是要求登录)', () => {
    expect(detectLoginRequired('这个影片讲的是黑客入侵服务器,主角要登录银行系统')).toBe(false)
  })

  it('"电影里有一段主角登录系统的剧情" → false', () => {
    expect(detectLoginRequired('电影里有一段主角登录系统的剧情')).toBe(false)
  })

  it('"请问这个登录入口在哪里" → false', () => {
    expect(detectLoginRequired('请问这个登录入口在哪里')).toBe(false)
  })

  it('"怎么注册账号?登录和注册有什么区别?" → false', () => {
    expect(detectLoginRequired('怎么注册账号?登录和注册有什么区别?')).toBe(false)
  })

  it('"我觉得登录体验还不错" → false', () => {
    expect(detectLoginRequired('我觉得登录体验还不错')).toBe(false)
  })

  it('英文 "a hacker logging into a bank system" → false', () => {
    expect(detectLoginRequired('The movie shows a hacker logging into a bank system')).toBe(false)
  })
})