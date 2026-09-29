import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createI18n } from 'vue-i18n'
import zhCN from '../../src/i18n/locales/zh-CN'
import enUS from '../../src/i18n/locales/en-US'

/**
 * P3-4:i18n 文案里混入 JS 模板字面量语法。
 *
 * 现象:`cancelConfirm` 写成 `'确定取消《${movie}》...'`,而 vue-i18n 的插值语法是
 * `{name}`,不是 `${name}`。多余的 `$` 会被原样渲染,弹窗显示成 **《$流浪地球2》**。
 *
 * 本文件从两个角度锁死:
 * ① 静态扫描:两份 locale 源码里不允许再出现 `${`
 * ② 行为验证:真正跑一遍 i18n,断言插值结果里没有 `$` 残留
 */
const LOCALE_FILES = [
  resolve(__dirname, '../../src/i18n/locales/zh-CN.ts'),
  resolve(__dirname, '../../src/i18n/locales/en-US.ts'),
]

describe('P3-4 locale 源码不含 JS 模板语法 ${}', () => {
  it.each(LOCALE_FILES.map((p) => [p.split(/[\\/]/).pop() ?? p, p]))(
    '%s 不含 ${}',
    (_name, file) => {
      const src = readFileSync(file, 'utf8')
      // 逐行报告,便于定位。注意要先剥掉注释:解释这个 bug 的注释里本来就会写出
      // `${movie}` 作为反例,那是文档不是文案,不该让测试变红。
      const offenders = src
        .split(/\r?\n/)
        .map((line) => line.replace(/\/\/.*$/, ''))
        .map((text, i) => ({ line: i + 1, text }))
        .filter(({ text }) => text.includes('${'))
      expect(
        offenders.map((o) => `L${o.line}: ${o.text.trim()}`),
      ).toEqual([])
    },
  )
})

describe('P3-4 插值行为正确(无 $ 残留)', () => {
  const zh = createI18n({
    legacy: false,
    locale: 'zh-CN',
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const en = createI18n({
    legacy: false,
    locale: 'en-US',
    fallbackLocale: 'en-US',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })

  it('zh-CN order.cancelConfirm 正确插值 movie / seats', () => {
    const out = zh.global.t('order.cancelConfirm', { movie: '流浪地球2', seats: '1排8座、1排9座' })
    expect(out).toContain('流浪地球2')
    expect(out).toContain('1排8座、1排9座')
    // 关键回归:不能有 $ 或未替换的 {xxx}
    expect(out).not.toContain('$')
    expect(out).not.toMatch(/\{(movie|seats)\}/)
  })

  it('en-US order.cancelConfirm 正确插值', () => {
    const out = en.global.t('order.cancelConfirm', { movie: 'Dune', seats: 'R1 S8' })
    expect(out).toContain('Dune')
    expect(out).toContain('R1 S8')
    expect(out).not.toContain('$')
    expect(out).not.toMatch(/\{(movie|seats)\}/)
  })

  it('app.adminDenied 两种语言都有(P3-1 新增 key)', () => {
    expect(zh.global.t('app.adminDenied')).toBe('该页面仅限管理员访问')
    expect(en.global.t('app.adminDenied')).toBe('This page is for administrators only')
  })
})
