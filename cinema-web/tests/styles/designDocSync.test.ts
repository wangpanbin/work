import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * `DESIGN.md` ↔ `main.css` 色板同步闸门。
 *
 * <p><b>为什么需要它</b>: 2026-10-12 换肤时, `DESIGN.md` 的 front-matter 抄了一份
 * 完整色板, 但<b>没有任何测试校验它与 `main.css` 同步</b> —— 事实上的第二份真相。
 * 换色时只要漏改一处, 两份文档就静默分叉, 后来人读文档改代码会改错方向。
 *
 * <p>本文件锁四件事:
 * <ol>
 *   <li>`colors.light` 段每个色值与 `main.css` 的 <b>亮色作用域</b>(`:root`)同名 token 逐值一致;</li>
 *   <li>`colors.dark` 段每个色值与 `main.css` 的 <b>暗色作用域</b>
 *       (`html[data-theme='dark']`)同名 token 逐值一致;</li>
 *   <li>`colors.shared` 段是<b>模式无关</b>的 token(暗场家族 / 字体 / 圆角),
 *       只在 `:root` 声明一次, 暗色作用域不得重复声明;</li>
 *   <li>`DESIGN.md` 正文出现的每个 hex 都是<b>真实 token 的值</b>(不存在的颜色不许被写进文档)。</li>
 * </ol>
 *
 * <p><b>2026-10-20 双主题改造</b>: 原来只有一份 `colors:` 段, 现在拆成
 * `light` / `dark` / `shared` 三段 —— 这三段与 `main.css` 的真实结构一一对应:
 * 暗色作用域只覆盖「会变」的 token, 模式无关的只在 `:root` 声明一次。
 *
 * <p>另加一条:`spacing:` 段标注为 doc-only, 且 `main.css` 确实没有间距 token ——
 * 避免"文档说一套、CSS 另一套"长期并存。
 *
 * <p><b>脆性声明</b>: 与同目录 `themeTokens.test.ts` 同属静态源码扫描。
 * 本仓 vitest 是 `environment: 'node'`, 挂不上 SFC; CSS 级联 / 真实渲染结果
 * 仍<b>必须</b>用浏览器验证, 本文件不能替代。
 */

/** cinema-web 根目录 */
const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

const MAIN_CSS = readFileSync(resolve(WEB_ROOT, 'src/styles/main.css'), 'utf8')
const DESIGN_MD = readFileSync(resolve(WEB_ROOT, 'DESIGN.md'), 'utf8')
/** 座位色声明在 SeatSelect.vue 的 `.seat-select` 块里, 不在 main.css */
const SEAT_SELECT = readFileSync(resolve(WEB_ROOT, 'src/views/SeatSelect.vue'), 'utf8')

/** 取 front-matter 原文 */
function frontMatter(md: string): string {
  const m = md.match(/^---\r?\n([\s\S]*?)\r?\n---/)
  return m ? m[1] : ''
}

/**
 * 取 front-matter 里某个二级块的 `key -> value`。
 * 只认 **两空格** 缩进的条目, 四空格的嵌套子项(typography 的 fontFamily 等)不收。
 */
function yamlBlock(fm: string, block: string): Map<string, string> {
  const out = new Map<string, string>()
  let inside = false
  for (const line of fm.split(/\r?\n/)) {
    if (/^[A-Za-z]/.test(line)) {
      inside = line.startsWith(block + ':')
      continue
    }
    if (!inside) continue
    const m = line.match(/^ {2}([A-Za-z0-9_-]+):\s*(.*)$/)
    if (!m) continue
    let v = m[2].trim()
    if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1)
    out.set(m[1], v)
  }
  return out
}

/**
 * 取 front-matter 里某个**嵌套块**的 `key -> value`。
 * 结构形如:
 * ```yaml
 * colors:
 *   light:
 *     paper: "#eef1f6"
 * ```
 * 即: 父块在第 0 列、子块在第 2 列、条目在第 4 列。
 */
function yamlNestedBlock(fm: string, parent: string, child: string): Map<string, string> {
  const out = new Map<string, string>()
  let inParent = false
  let inChild = false
  for (const line of fm.split(/\r?\n/)) {
    if (/^[A-Za-z]/.test(line)) {
      inParent = line.startsWith(parent + ':')
      inChild = false
      continue
    }
    if (!inParent) continue
    if (/^ {2}[A-Za-z0-9_-]+:\s*$/.test(line)) {
      inChild = line.trim() === child + ':'
      continue
    }
    if (!inChild) continue
    const m = line.match(/^ {4}([A-Za-z0-9_-]+):\s*(.*)$/)
    if (!m) continue
    let v = m[2].trim()
    if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1)
    out.set(m[1], v)
  }
  return out
}

/** 亮色作用域: `:root { }` 块里的 token */
function scopeTokens(css: string, scope: 'light' | 'dark'): Map<string, string> {
  const map = new Map<string, string>()
  const re =
    scope === 'dark'
      ? /html\[data-theme=['"]dark['"]\]\s*\{([^}]*)\}/g
      : /:root\s*\{([^}]*)\}/g
  for (const m of css.matchAll(re)) {
    for (const d of m[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      map.set(d[1], d[2].trim())
    }
  }
  return map
}

const LIGHT = scopeTokens(MAIN_CSS, 'light')
const DARK = scopeTokens(MAIN_CSS, 'dark')

/** 全部已登记的 token 值(两个作用域 + 管理端覆盖块 + SeatSelect 的 --seat-*) */
function knownTokenValues(): Set<string> {
  const known = new Set<string>()
  for (const v of LIGHT.values()) known.add(v.toLowerCase())
  for (const v of DARK.values()) known.add(v.toLowerCase())
  // 管理端纸面覆盖块(亮/暗两套) —— 它们的值同样是「真实 token 值」
  for (const m of MAIN_CSS.matchAll(/\.app-main\.is-admin\s*\{([^}]*)\}/g)) {
    for (const d of m[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      known.add(d[2].trim().toLowerCase())
    }
  }
  for (const m of SEAT_SELECT.matchAll(/\.seat-select\s*\{([^}]*)\}/g)) {
    for (const d of m[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      known.add(d[2].trim().toLowerCase())
    }
  }
  // 支付二维码的三个字面量(扫码硬要求, 见 themeTokens.test.ts 的 QR_WHITELIST)
  known.add('#111827')
  known.add('#ffffff')
  known.add('#fff')
  return known
}

/**
 * 必须在 light / dark 两段都登记的 token —— 即「会随模式变」的那批。
 */
const REQUIRED_COLORFUL = [
  'paper', 'paper-raised', 'paper-sunk', 'paper-overlay', 'rule', 'rule-strong',
  'ink', 'ink-2', 'ink-3', 'ink-inverse',
  'accent', 'accent-hover', 'accent-press', 'accent-wash', 'accent-stamp', 'accent-disabled', 'accent-rgb',
  'scene-browse', 'scene-browse-ink', 'scene-browse-rgb',
  'scene-seat', 'scene-seat-ink', 'scene-seat-rgb',
  'scene-order', 'scene-order-ink', 'scene-order-rgb',
  'scene-payment', 'scene-payment-ink', 'scene-payment-rgb',
  'ok', 'warn', 'danger', 'danger-hover', 'warn-hover', 'info',
  'ok-rgb', 'warn-rgb', 'danger-rgb',
]

/**
 * 必须在 shared 段登记的 token —— 模式无关, 只在 :root 声明一次。
 */
const REQUIRED_SHARED = [
  'hall', 'hall-raised', 'hall-rule', 'hall-ink', 'hall-ink-2', 'hall-deep',
  'hall-mine-bar', 'hall-scene', 'hall-glow',
]

describe('DESIGN.md ↔ main.css 同步', () => {
  const fm = frontMatter(DESIGN_MD)
  const lightColors = yamlNestedBlock(fm, 'colors', 'light')
  const darkColors = yamlNestedBlock(fm, 'colors', 'dark')
  const sharedColors = yamlNestedBlock(fm, 'colors', 'shared')

  it('DESIGN.md 的 front-matter 解析得出来', () => {
    expect(fm, 'DESIGN.md 没有 YAML front-matter').not.toBe('')
  })

  it.each([
    ['light', lightColors, LIGHT],
    ['dark', darkColors, DARK],
  ] as const)('colors.%s 段的每个色值与 main.css 同名 token 逐值一致', (mode, block, scope) => {
    expect(block.size, `DESIGN.md colors.${mode} 段一条都没解析出来`).toBeGreaterThan(0)
    for (const [key, value] of block) {
      const token = `--${key}`
      expect(scope.has(token), `main.css 的${mode}作用域里没有 ${token}(DESIGN.md colors.${mode} 段登记了)`).toBe(true)
      expect(scope.get(token), `${token} 在 DESIGN.md 与 main.css 里不一致`).toBe(value)
    }
  })

  it('colors.shared 段的每个色值与 main.css 的 :root 一致, 且暗色作用域不重复声明', () => {
    expect(sharedColors.size, 'DESIGN.md colors.shared 段一条都没解析出来').toBeGreaterThan(0)
    for (const [key, value] of sharedColors) {
      const token = `--${key}`
      expect(LIGHT.has(token), `main.css :root 里没有 ${token}(DESIGN.md colors.shared 段登记了)`).toBe(true)
      expect(LIGHT.get(token), `${token} 在 DESIGN.md 与 main.css 里不一致`).toBe(value)
      expect(DARK.has(token), `${token} 是模式无关 token, 不该在暗色作用域重复声明`).toBe(false)
    }
  })

  it('colors.light / colors.dark 覆盖了全部「会随模式变」的 token', () => {
    for (const key of REQUIRED_COLORFUL) {
      expect(lightColors.has(key), `DESIGN.md colors.light 段漏登记: ${key}`).toBe(true)
      expect(darkColors.has(key), `DESIGN.md colors.dark 段漏登记: ${key}`).toBe(true)
    }
  })

  it('colors.shared 覆盖了全部模式无关的暗场 token', () => {
    const missing = REQUIRED_SHARED.filter((k) => !sharedColors.has(k))
    expect(missing, `DESIGN.md colors.shared 段漏登记: ${missing.join(', ')}`).toEqual([])
  })

  it('rounded 段与 main.css 的 --radius-* 逐值一致', () => {
    const rounded = yamlBlock(fm, 'rounded')
    expect(rounded.size, 'DESIGN.md rounded 段没解析出条目').toBeGreaterThan(0)
    for (const [key, value] of rounded) {
      const token = `--radius-${key}`
      expect(LIGHT.has(token), `main.css 里没有 ${token}`).toBe(true)
      expect(LIGHT.get(token), `${token} 在 DESIGN.md 与 main.css 里不一致`).toBe(value)
    }
  })

  it('DESIGN.md 正文出现的每个 hex 都是真实 token 的值', () => {
    const known = knownTokenValues()
    const hexes = new Set(
      [...DESIGN_MD.matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((m) => m[0].toLowerCase()),
    )
    expect(hexes.size, 'DESIGN.md 一个 hex 都没扫到 —— 正则失效会导致假绿').toBeGreaterThan(0)
    const unknown = [...hexes].filter((h) => !known.has(h))
    expect(unknown, `DESIGN.md 写了 main.css 里不存在的颜色: ${unknown.join(', ')}`).toEqual([])
  })

  it('spacing 段标注为 doc-only, 且 main.css 确实没有间距 token', () => {
    const spacing = yamlBlock(fm, 'spacing')
    expect(spacing.get('docOnly'), 'DESIGN.md spacing 段缺少 docOnly: true 标注').toBe('true')
    const spaceTokens = [...LIGHT.keys()].filter((k) => k.startsWith('--space') || k.startsWith('--gap'))
    expect(spaceTokens, `main.css 出现了未在 DESIGN.md 登记的间距 token: ${spaceTokens.join(', ')}`).toEqual([])
  })
})
