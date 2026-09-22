import { describe, it, expect } from 'vitest'
import zhCN from '../../src/i18n/locales/zh-CN'
import enUS from '../../src/i18n/locales/en-US'

/**
 * locales-shape.test.ts
 *
 * 第四性原则 (TDD seam):
 * - 运行时验证:zh-CN 与 en-US 都有非空对象,顶层 key 完全一致
 * - 类型层验证:en-US 受 `SameShape<typeof zhCN>` 约束 → tsc 编译期保证子 key 对齐
 *   (这里只测运行时;类型层由 `pnpm type-check` 覆盖)
 */

function topLevelKeys(messages: Record<string, unknown>): string[] {
  return Object.keys(messages).sort()
}

describe('locale 字典 — 运行时对齐', () => {
  it('zh-CN 是非空对象', () => {
    expect(typeof zhCN).toBe('object')
    expect(zhCN).not.toBeNull()
    expect(Object.keys(zhCN).length).toBeGreaterThan(0)
  })

  it('en-US 是非空对象', () => {
    expect(typeof enUS).toBe('object')
    expect(enUS).not.toBeNull()
    expect(Object.keys(enUS).length).toBeGreaterThan(0)
  })

  it('zh-CN 与 en-US 顶层 key 完全一致(顺序无关)', () => {
    expect(topLevelKeys(zhCN)).toEqual(topLevelKeys(enUS))
  })

  it('顶层 key 至少包含 §4.2 spec 标注的所有视图段名', () => {
    const required = ['common', 'app', 'home', 'login', 'movie', 'seat', 'order', 'payment', 'admin', 'chat', 'notFound', 'category']
    const actual = topLevelKeys(zhCN)
    for (const k of required) {
      expect(actual, `zh-CN 应包含 "${k}"`).toContain(k)
    }
  })

  it('zh-CN 的 "common" 段至少有 6 个键(对应 spec 列举的常用 UI 操作)', () => {
    expect(Object.keys(zhCN.common).length).toBeGreaterThanOrEqual(6)
  })

  it('zh-CN 的 "category" 段同时含 genre + region 子段', () => {
    expect(zhCN.category).toHaveProperty('genre')
    expect(zhCN.category).toHaveProperty('region')
  })
})