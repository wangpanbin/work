import { describe, it, expect, vi, afterEach } from 'vitest'
import { decodeBitmap } from '../../src/utils/bitmap'

/**
 * 座位位图解码 —— 「缺失位按占用」与「非法 base64 抛错」两条契约(2026-10-24)。
 *
 * 背景: 原先缺失位落进 `result[i] = 0` → 也就是「可选」。位图与座位布局一旦错位
 * (影厅改过行列、位图是历史脏数据), 用户就能选到一个其实已经被占的座位 ——
 * 点了才被服务端拒绝。宁可显示成占着(点不动), 也不能显示成可选。
 *
 * 另外, atob 的异常原本会直接把整页带崩; 现在改成抛一个语义明确的错误,
 * 交给调用方渲染错误态。
 */
describe('decodeBitmap — 位序', () => {
  it('字节内 MSB 优先(与 Redis 位图同序)', () => {
    // 0xC0 = 1100 0000 → 第 0、1 位为 1, 其余为 0
    const bits = decodeBitmap('wA==', 8)
    expect(Array.from(bits)).toEqual([1, 1, 0, 0, 0, 0, 0, 0])
  })

  it('逐位展开与手算一致(0x5A = 0101 1010)', () => {
    const bits = decodeBitmap('Wg==', 8)
    expect(Array.from(bits)).toEqual([0, 1, 0, 1, 1, 0, 1, 0])
  })

  it('结果长度严格等于座位数', () => {
    expect(decodeBitmap('wA==', 20).length).toBe(20)
    expect(decodeBitmap('wA==', 3).length).toBe(3)
  })
})

describe('decodeBitmap — 位图短于座位数', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('缺失的位按「占用」处理(不能显示成可选)', () => {
    // 位图只有 8 位, 座位 16 个 → 后 8 个座位必须全是 1
    const bits = decodeBitmap('wA==', 16)
    expect(Array.from(bits)).toEqual([1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1])
  })

  it('⚠️ 回归锁: 缺失位绝不能是 0(0 = 可选 = 能点一个其实被占的座位)', () => {
    const bits = decodeBitmap('wA==', 16)
    expect(bits[8]).toBe(1)
    expect(bits[15]).toBe(1)
  })

  it('位图不足时记一条 warn(便于排查布局与位图不一致)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    decodeBitmap('wA==', 16)
    expect(warn).toHaveBeenCalled()
  })

  it('空位图(全部座位都缺失)→ 全部占用', () => {
    const bits = decodeBitmap('', 4)
    expect(Array.from(bits)).toEqual([1, 1, 1, 1])
  })
})

describe('decodeBitmap — 非法 base64', () => {
  it('抛错而不是静默返回空图', () => {
    expect(() => decodeBitmap('!!!not-base64!!!', 8)).toThrow()
  })

  it('错误信息可读(不是 atob 的原始英文异常)', () => {
    expect(() => decodeBitmap('!!!not-base64!!!', 8)).toThrow(/位图解码失败/)
  })

  it('⚠️ 回归锁: 抛错而不是整页崩掉 —— 调用方要能渲染错误态', () => {
    // 若改成返回全 0(全可选), 用户就能点任意座位 —— 比抛错危险得多
    let result: Uint8Array | null = null
    try {
      result = decodeBitmap('!!!not-base64!!!', 8)
    } catch {
      result = null
    }
    expect(result).toBeNull()
  })
})
