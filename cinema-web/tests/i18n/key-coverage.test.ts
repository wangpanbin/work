import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import zhCN from '../../src/i18n/locales/zh-CN'
import enUS from '../../src/i18n/locales/en-US'

/**
 * key-coverage.test.ts
 *
 * 第五性原则 (TDD seam) — 防止 2026-09-23 那一类 bug 复发:
 * - App.vue 用了 `app.chipLocaleAria`,但 zh-CN / en-US 字典都没这个 key
 * - 运行时 intlify 触发 warn 链,fallback 链全部失败才静默
 * - SameShape 类型只检查 en-US 字段 shape 不漏 key,但 zh-CN + en-US 都缺同
 *   一个 key 时 SameShape 抓不住 —— **这种"两边都漏"是 SameShape 的盲点**
 *
 * 测试方法:
 * 1. 递归扫描 .vue 文件中的 `$t('xxx')` 和 `t('xxx')` 调用
 * 2. 对每个引用的 key,断言它存在于 zh-CN 与 en-US 字典(递归走)
 *
 * 注意:本测试只覆盖 `<script setup>` 模板的 i18n 调用;不替代 SameShape 类
 * 型检查(那个只跑 `pnpm type-check`)。
 */

const SRC_ROOT = join(__dirname, '..', '..', 'src')

function listVueFiles(dir: string): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) {
      out.push(...listVueFiles(full))
    } else if (name.endsWith('.vue') || name.endsWith('.ts')) {
      out.push(full)
    }
  }
  return out
}

/** 匹配 `$t('foo.bar')` / `t('foo.bar')` / `$t("foo.bar")` / `t("foo.bar")` 的 key 段 */
const KEY_RE = /(?:\$|\b)t\(\s*['"]([a-z][\w]+(?:\.[\w]+)+)['"]/g

function extractReferencedKeys(): Set<string> {
  const keys = new Set<string>()
  for (const file of listVueFiles(SRC_ROOT)) {
    // 跳过 i18n 自身,避免回环
    if (file.includes(`${path.sep}i18n${path.sep}`)) continue
    const text = readFileSync(file, 'utf8')
    let m: RegExpExecArray | null
    KEY_RE.lastIndex = 0
    while ((m = KEY_RE.exec(text)) !== null) {
      keys.add(m[1])
    }
  }
  return keys
}

/** 递归收集 locale 字典里所有可访问的 dot-key */
function collectDotKeys(obj: Record<string, unknown>, prefix = ''): Set<string> {
  const out = new Set<string>()
  for (const [k, v] of Object.entries(obj)) {
    const next = prefix ? `${prefix}.${k}` : k
    out.add(next)
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      for (const sub of collectDotKeys(v as Record<string, unknown>, next)) {
        out.add(sub)
      }
    }
  }
  return out
}

// 用 require 引入 node:path(简化,避免顶部污染)
import * as path from 'node:path'

describe('i18n key 覆盖率 — 模板/Script 引用的 key 必须存在于 zh-CN + en-US', () => {
  const referenced = extractReferencedKeys()
  const zhKeys = collectDotKeys(zhCN as Record<string, unknown>)
  const enKeys = collectDotKeys(enUS as Record<string, unknown>)

  it('至少扫描出 1 个引用(防止 regex 失效导致假绿)', () => {
    expect(referenced.size).toBeGreaterThan(0)
  })

  it('每个被引用的 key 都存在于 zh-CN 字典', () => {
    const missing = [...referenced].filter((k) => !zhKeys.has(k))
    expect(missing, `zh-CN 缺失 key: ${missing.join(', ')}`).toEqual([])
  })

  it('每个被引用的 key 都存在于 en-US 字典', () => {
    const missing = [...referenced].filter((k) => !enKeys.has(k))
    expect(missing, `en-US 缺失 key: ${missing.join(', ')}`).toEqual([])
  })
})