import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

/**
 * 主题 token 契约测试。
 *
 * <p><b>为什么需要它</b>: 2026-10-12 换肤(暖米纸+朱红 → 冷靛蓝+皇家蓝)之前,
 * 全站 177 个用例<b>没有任何一条</b>断言颜色或读 {@code styles/main.css} ——
 * 主题完全没有自动化闸门, 换错了也只能靠人眼看截图才发现。
 *
 * <p><b>本文件锁什么</b>:
 * <ol>
 *   <li>旧暖色值不得复活(main.css 扫黑名单);</li>
 *   <li>硬编码颜色只能出现在「自定义属性声明」与「支付二维码白名单」两处;</li>
 *   <li>主色与侧边栏深靛蓝契约;</li>
 *   <li><b>座位图 5 态转灰度后仍可分辨</b> —— 这条原本是
 *       {@code SeatSelect.vue} 里靠人工截图比对的验收硬要求, 现固化为可执行断言。</li>
 * </ol>
 *
 * <p><b>脆性声明</b>: 与 {@code tests/views/posterWiring.test.ts} 同属静态源码扫描。
 * 本仓库 vitest 是 {@code environment: 'node'} 且没装 jsdom / @vue/test-utils,
 * 挂不上 SFC —— 这是本仓既定的取舍。CSS 级联 / 真实渲染结果仍<b>必须</b>用浏览器验证,
 * 本文件不能替代。
 */

/** 源码根 */
const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')

const readSrc = (rel: string) => readFileSync(resolve(SRC, rel), 'utf8')
const MAIN_CSS = readSrc('styles/main.css')

/**
 * 去掉 `/* ... *\/` 与 `<!-- ... -->` 注释。
 * 注释里的色值是文档不是生效样式, 不该被「无硬编码色」判红;
 * 但反过来, 注释被去掉后依然能检出真硬编码, 所以这个顺序是安全的。
 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, '')
}

/** 列出 src 下所有 .vue / .ts */
function listSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) listSourceFiles(full, acc)
    else if (/\.(vue|ts)$/.test(entry.name)) acc.push(full)
  }
  return acc
}

// =====================================================================
// WCAG 相对亮度 —— 用于座位 5 态的灰度可分辨性断言
// =====================================================================
function srgbToLinear(c: number): number {
  const s = c / 255
  return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
}

/** WCAG 2.x 相对亮度 */
function relativeLuminance(hex: string): number {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  const r = parseInt(full.slice(0, 2), 16)
  const g = parseInt(full.slice(2, 4), 16)
  const b = parseInt(full.slice(4, 6), 16)
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)
}

/** 转灰度后的 0-255 亮度值, 即「截图去色后」人眼看到的明度 */
function grayscaleLevel(hex: string): number {
  return Math.round(relativeLuminance(hex) * 255)
}

/** 解析 main.css 的 :root 自定义属性, 返回 name -> 原始值 */
function parseRootTokens(css: string): Map<string, string> {
  const map = new Map<string, string>()
  // 逐个 :root {} 块
  for (const m of css.matchAll(/:root\s*\{([^}]*)\}/g)) {
    for (const d of m[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      map.set(d[1], d[2].trim())
    }
  }
  return map
}

/**
 * 按**作用域**分别取 token(双主题闸门的核心)。
 *
 * <p>两套作用域:
 * <ul>
 *   <li>{@code light} → {@code :root { }} 块 —— 亮色真源</li>
 *   <li>{@code dark} → {@code html[data-theme='dark'] { }} 块 —— 暗色覆盖</li>
 * </ul>
 *
 * <p><b>为什么必须按作用域拆开</b>: 旧的 {@code parseRootTokens} 只匹配 {@code :root},
 * 暗色值若也写进 {@code :root} 会被合并 —— 断言只测到最后一套, 闸门静默失效。
 * 现在暗色作用域是独立选择器, 两套值互不污染, 断言才能真的分别验两套。
 */
function parseScopedTokens(css: string, scope: 'light' | 'dark'): Map<string, string> {
  const map = new Map<string, string>()
  const selector =
    scope === 'dark'
      ? /html\[data-theme=['"]dark['"]\]\s*\{([^}]*)\}/g
      : /:root\s*\{([^}]*)\}/g
  for (const m of css.matchAll(selector)) {
    for (const d of m[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      map.set(d[1], d[2].trim())
    }
  }
  return map
}

/** 两套作用域各自的 token 表 */
const LIGHT = parseScopedTokens(MAIN_CSS, 'light')
const DARK = parseScopedTokens(MAIN_CSS, 'dark')

/**
 * 取某个作用域的**原始 CSS 文本**(断言「派生关系还在」这类需要看原文的场合)。
 */
function scopeText(css: string, scope: 'light' | 'dark'): string {
  if (scope === 'dark') {
    const m = css.match(/html\[data-theme=['"]dark['"]\]\s*\{[^}]*\}/)
    return m ? m[0] : ''
  }
  return [...css.matchAll(/:root\s*\{[^}]*\}/g)].map((m) => m[0]).join('\n')
}

/**
 * 取某个**独立选择器块**里的自定义属性(如管理端的纸面覆盖块)。
 * 这类块不在 :root / 暗色作用域里, 得单独抓。
 */
function blockDecls(css: string, selector: RegExp): Map<string, string> {
  const map = new Map<string, string>()
  const m = css.match(selector)
  if (m) {
    for (const d of m[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      map.set(d[1], d[2].trim())
    }
  }
  return map
}

/** 管理端纸面覆盖块(亮色) */
const ADMIN_LIGHT = blockDecls(MAIN_CSS, /^\.app-main\.is-admin\s*\{([^}]*)\}/m)
/** 管理端纸面覆盖块(暗色) */
const ADMIN_DARK = blockDecls(
  MAIN_CSS,
  /^html\[data-theme='dark'\]\s*\.app-main\.is-admin\s*\{([^}]*)\}/m,
)

/**
 * 必须在暗色作用域里**换值**的 token 清单。
 *
 * <p>这些 token 留着亮色值到暗底上必然出问题(墨色读不清 / 身份色撞色 /
 * 印色对比度不足)。漏配一个 → 下面那条断言立刻红。
 */
const MUST_SWITCH: string[] = [
  '--paper', '--paper-raised', '--paper-sunk', '--paper-overlay', '--rule', '--rule-strong',
  '--ink', '--ink-2', '--ink-3', '--ink-inverse',
  '--accent', '--accent-hover', '--accent-press', '--accent-wash', '--accent-stamp', '--accent-disabled', '--accent-rgb',
  '--scene-browse', '--scene-browse-ink', '--scene-browse-rgb',
  '--scene-seat', '--scene-seat-ink', '--scene-seat-rgb',
  '--scene-order', '--scene-order-ink', '--scene-order-rgb',
  '--scene-payment', '--scene-payment-ink', '--scene-payment-rgb',
  '--ok', '--warn', '--danger', '--danger-hover', '--warn-hover', '--info',
  '--ok-rgb', '--warn-rgb', '--danger-rgb',
  '--shadow-paper', '--shadow-paper-hover', '--shadow-modal', '--shadow-hall', '--shadow-tooltip', '--overlay-poster',
]

/**
 * 模式无关的 token —— 两模式同值, 因此**只**在 {@code :root} 声明一次。
 * 暗色作用域不再重复声明它们(重复声明同值 = 复制粘贴的温床)。
 */
const MODE_INDEPENDENT: string[] = [
  // 暗场家族: 座位面板在两种模式下是同一块暗色票面(见 DESIGN.md §The Two-Mode Rule)
  '--hall', '--hall-raised', '--hall-rule', '--hall-ink', '--hall-ink-2', '--hall-deep', '--hall-ink-rgb',
  '--hall-wash', '--hall-wash-soft', '--hall-stripe', '--hall-mine-bar', '--hall-scene', '--hall-glow',
  // 字体 / 圆角 / 过渡
  '--font-display', '--font-body', '--font-mono',
  '--radius-sm', '--radius-md', '--radius-lg', '--radius-xl',
  '--transition-fast', '--transition-normal',
  // Element Plus 的基础圆角与字体(与主题无关, 两种模式同值)
  '--el-border-radius-base', '--el-font-family',
]

/**
 * 某模式下的**有效** token 集 = 亮色基线 + 该模式的覆盖(暗色优先)。
 *
 * <p>为什么需要合并: 暗场家族(座位面板底 --hall 等)是模式无关的, 只在 :root 声明一次;
 * 解析暗底某个 token 的计算值时, 必须能在暗色作用域找不到时回落到亮色基线。
 * 这与浏览器真实的层叠行为一致(暗色块只覆盖它声明的那几个 token)。
 */
function effectiveTokens(mode: 'light' | 'dark'): Map<string, string> {
  if (mode === 'light') return LIGHT
  const merged = new Map(LIGHT)
  for (const [k, v] of DARK) merged.set(k, v)
  return merged
}

const EFFECTIVE = {
  light: effectiveTokens('light'),
  dark: effectiveTokens('dark'),
}

/** 递归解析 var(--x) 引用, 直到拿到字面 hex */
function resolveToken(name: string, tokens: Map<string, string>, depth = 0): string {
  if (depth > 8) throw new Error(`token 引用过深, 可能成环: ${name}`)
  const raw = tokens.get(name)
  if (!raw) throw new Error(`main.css :root 里找不到 ${name}`)
  const ref = raw.match(/^var\(\s*(--[\w-]+)\s*\)$/)
  return ref ? resolveToken(ref[1], tokens, depth + 1) : raw
}

// =====================================================================
describe('主题契约 · 旧暖色值不得复活', () => {
  // 2026-10-12 换肤前的暖米纸 + 朱红印色全套
  const RETIRED = [
    '#b8352a', '#a52c22', '#8e241b', // 朱红印色三阶
    '#f2ece1', '#faf7f0', '#e7ddcb', '#d6c9b3', '#b9a88c', // 暖米纸五阶
    '#1f1b16', '#574f43', '#8a8071', // 暖墨三阶
    '#171410', '#221e18', '#3a342b', '#e8e0d2', '#9c9284', // 暖黑暗场五阶
    '#d8b0ab', // 暖色禁用态按钮
    'rgba(184, 53, 42', 'rgba(31, 27, 22', 'rgba(161, 39, 28',
    'rgba(47, 107, 79', 'rgba(138, 109, 31',
  ]

  it.each(RETIRED)('main.css 不含已下线的暖色值 %s', (needle) => {
    expect(MAIN_CSS.toLowerCase()).not.toContain(needle.toLowerCase())
  })
})

// =====================================================================
describe('主题契约 · 硬编码色收敛', () => {
  /** 支付二维码白名单 —— 扫码硬要求, 换肤时刻意保留 */
  const QR_WHITELIST = ['#111827', '#ffffff', '#fff']

  /** 自定义属性声明行: `--foo: #123456` / `--foo: rgba(...)`, 这是 token 定义本身, 合法 */
  const IS_TOKEN_DECL = /^\s*--[\w-]+\s*:/

  it('.vue / .ts 里不存在白名单外的硬编码色', () => {
    const offenders: string[] = []
    for (const file of listSourceFiles(SRC)) {
      const rel = file.slice(SRC.length + 1).replace(/\\/g, '/')
      const lines = stripComments(readFileSync(file, 'utf8')).split('\n')
      lines.forEach((line, idx) => {
        if (IS_TOKEN_DECL.test(line)) return // token 定义本身
        const hits = line.match(/#[0-9a-fA-F]{3,8}\b|rgba?\(\s*[0-9]/g)
        if (!hits) return
        for (const h of hits) {
          if (!QR_WHITELIST.includes(h.toLowerCase())) {
            offenders.push(`${rel}:${idx + 1}  ${h}  |  ${line.trim().slice(0, 70)}`)
          }
        }
      })
    }
    expect(offenders, `出现 ${offenders.length} 处白名单外硬编码色:\n${offenders.join('\n')}`).toEqual([])
  })

  it('唯一的硬编码色就是支付二维码, 不是别处漏进来的', () => {
    // 反向锁: 若将来有人删掉二维码白名单, 本条会先红, 提醒同步上面那条
    const pay = stripComments(readSrc('views/Payment.vue'))
    expect(pay).toMatch(/background:\s*#fff\b/)
    expect(pay).toMatch(/dark:\s*'#111827',\s*light:\s*'#ffffff'/)
  })
})

// =====================================================================
describe('主题契约 · 主色与侧边栏', () => {
  /**
   * 两套印色各自的期望值。
   * 暗色是同色相的明度变体(#2563eb 221° → #3b82f6 217°), 仍然是一支色 ——
   * 不是第二支全局色, 所以不破 The One Global Ink Rule。
   */
  const ACCENT_BY_MODE = {
    light: {
      base: '#2563eb',
      hover: '#1d4ed8',
      press: '#1e40af',
      wash: 'rgba(37, 99, 235, 0.08)',
      stamp: 'rgba(37, 99, 235, 0.14)',
      disabled: '#a7bfe8',
      rgb: '37 99 235',
    },
    dark: {
      base: '#3b82f6',
      hover: '#60a5fa',
      press: '#2563eb',
      wash: 'rgba(59, 130, 246, 0.14)',
      stamp: 'rgba(59, 130, 246, 0.22)',
      disabled: '#2d4468',
      rgb: '59 130 246',
    },
  } as const

  it.each(['light', 'dark'] as const)('%s 模式的印色七件套齐全', (mode) => {
    const a = ACCENT_BY_MODE[mode]
    const tokens = EFFECTIVE[mode]
    expect(resolveToken('--accent', tokens), `${mode} --accent`).toBe(a.base)
    expect(tokens.get('--accent-hover'), `${mode} --accent-hover`).toBe(a.hover)
    expect(tokens.get('--accent-press'), `${mode} --accent-press`).toBe(a.press)
    expect(tokens.get('--accent-wash'), `${mode} --accent-wash`).toBe(a.wash)
    expect(tokens.get('--accent-stamp'), `${mode} --accent-stamp`).toBe(a.stamp)
    expect(tokens.get('--accent-disabled'), `${mode} --accent-disabled`).toBe(a.disabled)
    expect(tokens.get('--accent-rgb'), `${mode} --accent-rgb`).toBe(a.rgb)
  })

  it('暗色印色是亮色印色的同色相明度变体(不是第二支全局色)', () => {
    const lightHue = hexToHsl(resolveToken('--accent', LIGHT)).h
    const darkHue = hexToHsl(resolveToken('--accent', DARK)).h
    const raw = Math.abs(lightHue - darkHue) % 360
    const gap = raw > 180 ? 360 - raw : raw
    expect(gap, `亮 ${lightHue.toFixed(1)}° vs 暗 ${darkHue.toFixed(1)}°`).toBeLessThanOrEqual(10)
    // 暗底上印色必须提亮, 否则主 CTA 上的字读不清
    expect(grayscaleLevel(resolveToken('--accent', DARK))).toBeGreaterThan(
      grayscaleLevel(resolveToken('--accent', LIGHT)),
    )
  })

  it.each(['light', 'dark'] as const)(
    '%s 模式: Element Plus primary 六阶 + dark-2 全部就位, 且都从 --accent 派生',
    (mode) => {
      // EP 色阶已不再手写 hex, 而是从 --accent 派生: 主色直接引用 token,
      // light-N = 混入 (N*10)% 白, dark-2 = 混入 20% 黑。
      // 这样换印色只需改 --accent 一处, 6 档自动跟随(见 main.css :root 注释)。
      // 基准色由上面「印色七件套」那条按模式锁定, 这里只锁"派生关系还在"。
      const css = scopeText(MAIN_CSS, mode)
      expect(css, `${mode} --el-color-primary 应引用 --accent`).toMatch(
        /--el-color-primary:\s*var\(--accent\)\s*;/,
      )
      const mixRamp: [string, number][] = [
        ['--el-color-primary-light-3', 30],
        ['--el-color-primary-light-5', 50],
        ['--el-color-primary-light-7', 70],
        ['--el-color-primary-light-8', 80],
        ['--el-color-primary-light-9', 90],
      ]
      for (const [token, pct] of mixRamp) {
        expect(css, `${mode} ${token} 应为 ${pct}% 白 + --accent`).toMatch(
          new RegExp(`${token}:\\s*color-mix\\(in srgb,\\s*#fff\\s+${pct}%,\\s*var\\(--accent\\)\\)\\s*;`),
        )
      }
      expect(css, `${mode} --el-color-primary-dark-2 应为 20% 黑 + --accent`).toMatch(
        /--el-color-primary-dark-2:\s*color-mix\(in srgb,\s*#000\s+20%,\s*var\(--accent\)\)\s*;/,
      )
    },
  )

  it.each(['light', 'dark'] as const)(
    '%s 模式恰好 7 个 --accent* token —— 不许加第二套全局印色',
    (mode) => {
      // 家族成员 7 条: --accent / hover / press / wash / stamp / rgb / disabled。
      // --accent-disabled 是主按钮 disabled 态的同族浅色 tint, 认定为「同一印色家族」
      // 而非新品牌色, 故计入。再多一条即说明真加了第二套印色, 需人工裁决。
      // ⚠️ 双主题后按**作用域**统计 —— 旧实现是对整份文件做裸文本扫描,
      //    暗色块一进来两套数就混在一起, 断言既测不准也不表达任何契约。
      const tokens = EFFECTIVE[mode]
      const accentTokens = [...tokens.keys()].filter((k) => k.startsWith('--accent')).sort()
      expect(accentTokens).toEqual([
        '--accent',
        '--accent-disabled',
        '--accent-hover',
        '--accent-press',
        '--accent-rgb',
        '--accent-stamp',
        '--accent-wash',
      ])
    },
  )

  it('管理端侧边栏引用暗场家族(亮色 --hall / 暗色 --hall-deep), 不复用纸面', () => {
    const admin = readSrc('views/admin/AdminHome.vue')
    const asideBlock = admin.match(/\.aside\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(asideBlock, '.aside 块没匹配到').not.toBe('')
    // 2026-10-12 收口: 侧栏暗面改为引用 --hall 家族 —— 锁「引用」而非字面量, 换肤才跟得动
    expect(asideBlock).toContain('--aside-bg: var(--hall)')
    expect(asideBlock).toContain('background: var(--aside-bg)')
    expect(asideBlock).not.toContain('var(--paper-raised)')
    // 2026-10-20 双主题: 暗色下侧栏下沉一档到 --hall-deep, 保住「午夜侧栏」身份。
    // 锁的是「侧栏属于暗场家族」这条语义, 不是某一个变量名。
    const darkAside =
      MAIN_CSS.match(/^html\[data-theme='dark'\]\s*\.aside\s*\{([^}]*)\}/m)?.[1] ?? ''
    expect(darkAside, 'main.css 里没有暗色侧栏覆盖块').toContain('--aside-bg: var(--hall-deep)')
    // 两个模式都不许拿纸面变量当侧栏底色
    for (const [where, css] of [['AdminHome.vue .aside', asideBlock], ['main.css 暗色 .aside', darkAside]]) {
      expect(css, `${where} 引用了纸面变量`).not.toMatch(/var\(--paper/)
    }
  })

  it('侧边栏激活态用 tint + 左亮条, 不是深底上糊成一团的实心块', () => {
    const admin = readSrc('views/admin/AdminHome.vue')
    const active = admin.match(/\.aside\s*:deep\(\.el-menu-item\.is-active\)\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(active).not.toBe('')
    expect(active).toContain('rgb(var(--accent-rgb) / 0.16)')
    expect(active).toContain('inset 3px 0 0 var(--accent)')
  })

  it('管理端纸面覆盖: 亮色不留旧暖灰, 暗色是暗场家族三档', () => {
    // 亮色: 冷调报表变体
    expect(ADMIN_LIGHT.size, '管理端亮色覆盖块没解析到').toBeGreaterThan(0)
    for (const stale of ['#eef0f1', '#f7f8f9', '#e4e8ea', '#d0d5d8', '#b3bcc2']) {
      expect(MAIN_CSS.match(/^\.app-main\.is-admin\s*\{([^}]*)\}/m)?.[1] ?? '', `管理端覆盖块残留旧值 ${stale}`).not.toContain(stale)
    }
    expect(ADMIN_LIGHT.get('--paper')).toBe('#e9edf3')
    expect(ADMIN_LIGHT.get('--rule')).toBe(LIGHT.get('--rule'))

    // 暗色: 侧栏最暗(--hall-deep) < 页面(--hall) < 卡片(--hall-raised)
    expect(ADMIN_DARK.size, '管理端暗色覆盖块没解析到').toBeGreaterThan(0)
    expect(ADMIN_DARK.get('--paper')).toBe('#0f172a')
    expect(ADMIN_DARK.get('--paper-raised')).toBe('#16203a')
    expect(ADMIN_DARK.get('--paper-sunk')).toBe('#0b1220')
    expect(ADMIN_DARK.get('--rule')).toBe(DARK.get('--rule'))
    expect(ADMIN_DARK.get('--rule-strong')).toBe(DARK.get('--rule-strong'))
  })

  it('--hall-deep 是暗场家族最深一档, 模式无关(只声明在 :root)', () => {
    expect(LIGHT.get('--hall-deep')).toBe('#0a0f1a')
    // 模式无关 token 不该在暗色作用域里重复声明 —— 重复声明同值是复制粘贴的温床
    expect(DARK.has('--hall-deep'), '--hall-deep 是模式无关 token, 不该在暗色作用域重复声明').toBe(false)
  })
})

// =====================================================================
describe('主题契约 · 座位图 5 态转灰度仍可分辨', () => {
  /**
   * 验收硬要求(来自 SeatSelect.vue 原注释): 截图转灰度后 5 态仍必须可分辨。
   * 非颜色通道(描边粗细 / 虚线 / 45°斜纹 / 左侧亮条)是第一道防线,
   * 这里锁的是第二道 —— 纯明度也不能全挤在一起。
   *
   * 实测: 换肤前的暖色方案最小相邻间隔仅 1 级, 换肤后为 2 级。
   */
  function seatGrayscaleLevels(mode: 'light' | 'dark' = 'light'): Record<string, number> {
    const tokens = parseRootTokens(MAIN_CSS)
    // --seat-sel-bg: var(--accent) —— 选中座的底色跟着**当前模式**的印色走,
    // 所以 5 态的可分辨性必须按模式各算一遍。
    tokens.set('--accent', resolveToken('--accent', EFFECTIVE[mode]))
    const seatSrc = readSrc('views/SeatSelect.vue')

    // ⚠️ 文件里有**两个** `.seat-select {}` 块: 第一个是布局容器(display:flex...),
    //    第二个才是 --seat-* 变量块。不能只取第一个 match, 必须收集全部块再合并声明。
    let declared = 0
    for (const m of seatSrc.matchAll(/\.seat-select\s*\{([^}]*)\}/g)) {
      for (const d of m[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
        tokens.set(d[1], d[2].trim())
        declared++
      }
    }
    if (declared === 0) throw new Error('没在 SeatSelect.vue 里解析到任何 --seat-* 声明')

    // locked 态 background: transparent, 实际呈现的是暗场底色 --hall
    const five: Record<string, string> = {
      locked: resolveToken('--hall', tokens),
      sold: resolveToken('--seat-sold-bg', tokens),
      available: resolveToken('--seat-avail-bg', tokens),
      mine: resolveToken('--seat-mine-bg', tokens),
      selected: resolveToken('--seat-sel-bg', tokens),
    }

    const out: Record<string, number> = {}
    for (const [k, v] of Object.entries(five)) out[k] = grayscaleLevel(v)
    return out
  }

  it.each(['light', 'dark'] as const)('%s 模式: 5 态都能解析出合法 hex（token 引用链没断）', (mode) => {
    for (const [k, v] of Object.entries(seatGrayscaleLevels(mode))) {
      expect(Number.isFinite(v), `${mode} ${k} 灰度值不是数字: ${v}`).toBe(true)
    }
  })

  it.each(['light', 'dark'] as const)('%s 模式: 相邻灰度差 ≥ 2, 5 态去色后仍可分辨', (mode) => {
    const levels = seatGrayscaleLevels(mode)
    const sorted = Object.entries(levels).sort((a, b) => a[1] - b[1])
    const gaps = sorted.slice(1).map(([, v], i) => v - sorted[i][1])

    const report = sorted.map(([k, v]) => `${k}:${v}`).join(' < ')
    expect(
      Math.min(...gaps),
      `${mode} 相邻灰度间隔过小 —— 去色后会糊: ${report}`,
    ).toBeGreaterThanOrEqual(2)
  })

  it.each(['light', 'dark'] as const)('%s 模式: 我锁座位保持墨绿, 动这个值会把间隔压回 1 级', (mode) => {
    const levels = seatGrayscaleLevels(mode)
    // mine 必须在 available 之上、selected 之下, 否则 3 个"实心"态会挤在一起
    expect(levels.mine).toBeGreaterThan(levels.available)
    expect(levels.selected).toBeGreaterThan(levels.mine)
  })
})

// =====================================================================
describe('主题契约 · RGB 分量 token 的 alpha 拼装语法', () => {
  /**
   * <b>这条来自一次真实的静默 bug</b>（2026-10-12 换肤当天）:
   * 写 {@code rgba(var(--accent-rgb), 0.16)} 是<b>非法 CSS</b> ——
   * {@code rgba()} 的逗号形式要求 R/G/B 也用逗号分隔, 而 token 里存的是空格分隔。
   * 浏览器不会报错, 只是把整条声明判为 invalid-at-computed-value-time 并丢弃,
   * 结果底色静默变成 transparent。
   *
   * <p>静态扫描类测试平时抓不到「渲染结果不对」, 但<b>能</b>抓到这个语法本身。
   * 正确写法是斜杠语法 {@code rgb(var(--accent-rgb) / 0.16)}。
   */
  it('不存在 rgba(var(--x), a) 这种非法写法', () => {
    const offenders: string[] = []
    const scan = (rel: string) => {
      const clean = stripComments(readSrc(rel))
      clean.split('\n').forEach((line, idx) => {
        if (/rgba\(\s*var\(/.test(line)) offenders.push(`${rel}:${idx + 1}  ${line.trim()}`)
      })
    }
    scan('styles/main.css')
    for (const f of listSourceFiles(SRC)) {
      if (/\.ts$/.test(f)) continue // .ts 是逻辑, 不会写 CSS
      scan(f.slice(SRC.length + 1).replace(/\\/g, '/'))
    }
    expect(
      offenders,
      `发现非法 rgba(var()) 写法, 浏览器会静默丢弃该声明:\n${offenders.join('\n')}\n改用 rgb(var(--x) / a)`,
    ).toEqual([])
  })

  it('语义色与主色都提供了 RGB 分量 token', () => {
    for (const t of ['--accent-rgb', '--ok-rgb', '--warn-rgb', '--danger-rgb']) {
      expect(MAIN_CSS, `缺少 ${t}`).toMatch(new RegExp(`${t}:\\s*\\d+\\s+\\d+\\s+\\d+\\s*;`))
    }
  })
})

// =====================================================================
describe('主题契约 · 票根签名装置与表格修复未被误删', () => {
  it('齿孔撕口 / 竖向撕口装置仍在', () => {
    expect(MAIN_CSS).toContain('.stub-perf')
    expect(MAIN_CSS).toContain('.stub-perf-x')
  })

  it('字体三档仍在（衬线标题 + 等宽票号）', () => {
    expect(MAIN_CSS).toMatch(/--font-display:/)
    expect(MAIN_CSS).toMatch(/--font-mono:/)
    expect(MAIN_CSS).toMatch(/--font-body:/)
  })

  it('表格单行修复仍在 —— 这是功能性修复, 不是样式', () => {
    const tableBlock = MAIN_CSS.match(/\.el-table \.cell\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(tableBlock).toContain('white-space: nowrap')
  })
})


// =====================================================================
// describe 追加于 2026-10-12「UI 视觉质感优化」。
// 只新增, 不修改/不删除上面任何一条既有断言。
// 背景: 实测 --ink-3 (#7c8ba1) 在纸面上只有 3.06:1, 低于 WCAG AA 的 4.5:1;
//      它却是全站用得最广的三级文字 token。本组把「对比度达标」固化成闸门,
//      防止后续换肤/调色又把它调回不可读。
// =====================================================================
describe('主题契约 · 文字对比度达 WCAG AA', () => {
  /** WCAG 2.x 对比度 = (L1+0.05)/(L2+0.05) */
  function contrastRatio(fg: string, bg: string): number {
    const a = relativeLuminance(fg)
    const b = relativeLuminance(bg)
    const [hi, lo] = a > b ? [a, b] : [b, a]
    return (hi + 0.05) / (lo + 0.05)
  }

  /**
   * 每个模式的 token 表 / 管理端纸面表。
   * 双主题后对比度必须按模式各算一遍 —— 亮底的 4.5:1 结论在暗底上不成立。
   */
  const BY_MODE = {
    light: { tokens: EFFECTIVE.light, admin: ADMIN_LIGHT },
    dark: { tokens: EFFECTIVE.dark, admin: ADMIN_DARK },
  } as const

  /**
   * 各模式的对比度基线(防回归式变暗)。
   * ⚠️ 基线一律向下取整到 2 位: 直接抄打印出的 15.77 会因为浮点尾数
   *    (真值 15.7683) 把当前值自己判为不达标 —— 断言必须能被现状满足。
   */
  const BASELINES: Record<'light' | 'dark', [string, number][]> = {
    light: [['--ink-2', 6.6], ['--ink', 15.7], ['--accent', 4.5]],
    dark: [['--ink-2', 6.6], ['--ink', 15.0], ['--accent', 4.5]],
  }

  it.each(['light', 'dark'] as const)(
    '%s 模式: --ink-3 在三种纸面 + 管理端纸面上均达 4.5:1 (AA 正文线)',
    (mode) => {
      const { tokens, admin } = BY_MODE[mode]
      const hexToken = (name: string) => resolveToken(name, tokens)
      const ink3 = hexToken('--ink-3')
      const surfaces: [string, string][] = [
        ['--paper', hexToken('--paper')],
        ['--paper-raised', hexToken('--paper-raised')],
        ['--paper-sunk', hexToken('--paper-sunk')],
        ['admin --paper', admin.get('--paper') as string],
        ['admin --paper-raised', admin.get('--paper-raised') as string],
        ['admin --paper-sunk', admin.get('--paper-sunk') as string],
      ]
      for (const [name, bg] of surfaces) {
        const r = contrastRatio(ink3, bg)
        expect(r, `${mode} --ink-3 (${ink3}) on ${name} (${bg}) 只有 ${r.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
      }
    },
  )

  it.each(['light', 'dark'] as const)(
    '%s 模式: --ink-2 / --ink / --accent 不低于各自基线 (防回归式变暗)',
    (mode) => {
      const { tokens } = BY_MODE[mode]
      for (const [name, floor] of BASELINES[mode]) {
        const r = contrastRatio(resolveToken(name, tokens), resolveToken('--paper', tokens))
        expect(r, `${mode} ${name} on --paper 降到 ${r.toFixed(2)}:1, 低于基线 ${floor}`).toBeGreaterThanOrEqual(floor)
      }
    },
  )

  it.each(['light', 'dark'] as const)(
    '%s 模式: 卡片描边可见, 且 --rule 与 --rule-strong 的层级不倒置',
    (mode) => {
      const { tokens } = BY_MODE[mode]
      const rule = resolveToken('--rule', tokens)
      const strong = resolveToken('--rule-strong', tokens)
      expect(contrastRatio(rule, resolveToken('--paper', tokens)), `${mode} 描边太淡, 卡片边界会糊`).toBeGreaterThan(1.4)
      // 相对亮度越高 = 颜色越浅。
      // 亮底: --rule 必须比 --rule-strong 浅(强线才强得过弱线);
      // 暗底: 方向相反 —— 越亮越显眼, 所以 --rule-strong 必须更亮。
      const ruleL = relativeLuminance(rule)
      const strongL = relativeLuminance(strong)
      if (mode === 'light') {
        expect(ruleL, `亮色 --rule (${rule}) 应浅于 --rule-strong (${strong})`).toBeGreaterThan(strongL)
      } else {
        expect(strongL, `暗色 --rule-strong (${strong}) 应亮于 --rule (${rule})`).toBeGreaterThan(ruleL)
      }
    },
  )

  it.each(['light', 'dark'] as const)('%s 模式: 管理端 --rule 与全局同步 (防残留旧描边)', (mode) => {
    const { tokens, admin } = BY_MODE[mode]
    expect(admin.get('--rule')).toBe(resolveToken('--rule', tokens))
  })

  it.each(['light', 'dark'] as const)(
    '%s 模式: 管理端图表墨色 --accent 在管理端纸面与卡面上均 ≥3:1 (WCAG 非文本对比度)',
    (mode) => {
      // 折线 / 面积 / 排行条 / 占比条是图形元素, 走 WCAG 的 3:1 而非 4.5:1。
      // 图表墨色就是 --accent, 所以它必须在自己的画布和卡面上都读得出来。
      const { tokens, admin } = BY_MODE[mode]
      const accent = resolveToken('--accent', tokens)
      const surfaces: [string, string][] = [
        ['admin --paper', admin.get('--paper') as string],
        ['admin --paper-raised', admin.get('--paper-raised') as string],
      ]
      for (const [name, bg] of surfaces) {
        const r = contrastRatio(accent, bg)
        expect(r, `${mode} 图表墨色 ${accent} on ${name} (${bg}) 只有 ${r.toFixed(2)}:1`).toBeGreaterThanOrEqual(3)
      }
    },
  )
})

// =====================================================================
describe('主题契约 · favicon 跟随主题', () => {
  /**
   * 2026-10-12 换肤回归: index.html 的 data-URI 一直写着旧暖米纸 + 朱红,
   * 换肤时漏改, 标签页至今是旧图标。本组把它锁住。
   */
  // SRC = cinema-web/src, 故 index.html 在其同级上一层的 cinema-web/ 下
  const HTML = readFileSync(resolve(SRC, '../index.html'), 'utf8')
  /** 主题模块持有两份 favicon data-URI(浅/暗), 由 applyTheme() 切换 href */
  const APPLY = readFileSync(resolve(SRC, 'theme/favicon.ts'), 'utf8')

  it('favicon 不含已下线的暖色值', () => {
    for (const retired of ['%23f2ece1', '%23b8352a']) {
      expect(HTML, `index.html favicon 仍含旧色 ${retired}`).not.toContain(retired)
    }
  })

  it('浅色 favicon 用浅色模式的 --paper / --accent', () => {
    const paper = resolveToken('--paper', LIGHT).replace('#', '%23').toLowerCase()
    const accent = resolveToken('--accent', LIGHT).replace('#', '%23').toLowerCase()
    expect(HTML, 'index.html 的静态 favicon 不是浅色模式的值').toContain(paper)
    expect(HTML).toContain(accent)
    expect(APPLY, '主题模块的浅色 favicon 与 index.html 不一致').toContain(paper)
    expect(APPLY).toContain(accent)
  })

  it('暗色 favicon 用暗色模式的 --paper / --accent', () => {
    const paper = resolveToken('--paper', DARK).replace('#', '%23').toLowerCase()
    const accent = resolveToken('--accent', DARK).replace('#', '%23').toLowerCase()
    expect(APPLY, '主题模块缺暗色 favicon').toContain(paper)
    expect(APPLY).toContain(accent)
  })
})
// =====================================================================
// 座位关注(Seat Watch) · 关注标识不得引入第 6 种座位色
// =====================================================================
/**
 * 座位图有「5 态去色后仍可分辨」的既有硬契约(见上方 describe), 已被固化为可执行断言。
 * 关注态如果靠**改座位背景色 / 描边色**来表达, 就等于引入第 6 种座位色, 会与既有 5 态
 * 争抢明度层级 —— 实测把相邻间隔从 2 级压回 1 级, 去色后 5 态糊成一片。
 *
 * 因此关注标识只能用<b>角标字形</b>这类非颜色通道表达。这条断言就是防止后续有人
 * 「顺手」给它加个背景色。
 *
 * 注意只检查 `.seat.watched { }` 规则本身; `.seat.watched::after` 是角标,
 * 它的 background 是角标自己的颜色, 不属于「座位色」。
 */
describe('主题契约 · 座位关注标识不是纯颜色通道', () => {
  const seatSrc = readSrc('views/SeatSelect.vue')

  it('关注标识用角标字形表达(::after), 而不是改座位本身的颜色', () => {
    expect(seatSrc).toContain('.seat.watched::after')
  })

  it('.seat.watched 规则不得设置 background / background-color / border-color', () => {
    const own = seatSrc.match(/\.seat\.watched\s*\{([^}]*)\}/)
    // 没有独立的 .seat.watched 规则 = 完全靠 ::after 角标, 天然合规
    if (!own) return
    expect(
      own[1],
      '关注态改了座位本身的背景/描边色 —— 会引入第 6 种座位色, 撞 5 态灰度契约',
    ).not.toMatch(/(?:^|[\s;{])(background|background-color|border-color)\s*:/)
  })

  it('未新增任何 --seat-* 色变量(5 态的色板保持原样)', () => {
    const declared = [...seatSrc.matchAll(/(--seat-[\w-]+)\s*:/g)].map((m) => m[1])
    const uniq = [...new Set(declared)].sort()
    // 既有的 5 组: avail / sel / mine / locked / sold, 各含 bg/border/ink
    expect(uniq).toEqual([
      '--seat-avail-bg',
      '--seat-avail-border',
      '--seat-avail-ink',
      '--seat-locked-border',
      '--seat-locked-ink',
      '--seat-mine-bg',
      '--seat-mine-border',
      '--seat-mine-ink',
      '--seat-sel-bg',
      '--seat-sel-border',
      '--seat-sold-bg',
      '--seat-sold-border',
      '--seat-sold-ink',
    ])
  })
})

// =====================================================================
// 场景身份色 (Scene Ink) —— 2026-10-19 UI 视觉重构新增
// =====================================================================
/**
 * 背景: 2026-10-19 的视觉重构把「全站一支皇家蓝」改成
 * 「全局单强调色 + 每个业务域一支场景身份色」(见 {@code cinema-web/DESIGN.md}
 * 的 The Scene Ink Rule)。
 *
 * <p>本组<b>只新增</b>规则, 不修改也不删除上面任何一条既有断言。
 * 既有那条「全站只有一个强调色 —— 不许再加第二个印色」锁的是 {@code --accent*}
 * 家族(全局印色)的<b>唯一性</b>; 本组锁的是<b>域内</b>身份色的<b>边界</b>。
 * 两者不冲突: 全局印色仍然只有一支, 身份色只在自己的域里做局部表达。
 */

/** 场景身份色家族清单 —— 每族必须恰好 base / ink / rgb 三个成员 */
const SCENE_FAMILIES = ['browse', 'seat', 'order', 'payment'] as const

/** 每个身份色允许出现的文件(域隔离) */
const SCENE_DOMAINS: Record<string, string[]> = {
  browse: ['views/Home.vue', 'views/MovieDetail.vue'],
  seat: ['views/SeatSelect.vue', 'components/SeatItem.vue'],
  order: ['views/OrderList.vue'],
  payment: ['views/Payment.vue'],
}

/**
 * 域内**间接**用到的语义色 —— el-tag 的 type="success|warning|danger" 会走 EP 的
 * --el-color-success / --warning / --danger, 而 main.css 把它们分别指向
 * --ok / --warn / --danger(见 main.css 的 Element Plus 变量覆盖块)。
 *
 * <p>这层<b>无法</b>从域内文件自动推导: 状态 → tagType 是动态映射, 映射表在
 * views/order/constants.ts, 不在域文件里。所以显式登记 ——
 * 登记缺失的后果与漏判同向(闸门以为这个域没有语义色), 所以宁可多登记。
 */
const DOMAIN_TAG_PEERS: Record<string, string[]> = {
  browse: [],
  seat: [],
  order: ['--ok', '--warn'],
  payment: ['--ok', '--warn'],
}

/**
 * 域内**直接**引用的语义色 —— 从代码里扫出来, 不写死。
 * 扫域内文件里出现的 var(--ok) / var(--warn) / var(--danger) 及它们的派生写法
 * (--ok-rgb / --danger-hover 之类, 统一归并到基色)。
 */
function scanDirectSemanticPeers(files: string[]): Set<string> {
  const found = new Set<string>()
  for (const rel of files) {
    const text = stripComments(readSrc(rel))
    for (const m of text.matchAll(/var\(\s*(--(?:ok|warn|danger))(?:-[a-z]+)?\s*\)/g)) {
      found.add(m[1])
    }
  }
  return found
}

/** 某个域实际会出现的语义色集合 = 直接引用 ∪ el-tag 间接引用 */
function effectiveSemanticPeers(family: string): string[] {
  const set = scanDirectSemanticPeers(SCENE_DOMAINS[family] ?? [])
  for (const p of DOMAIN_TAG_PEERS[family] ?? []) set.add(p)
  return [...set].sort()
}

/** hex -> HSL(用于断言色相差与饱和度差) */
function hexToHsl(hex: string): { h: number; s: number; l: number } {
  const h0 = hex.replace('#', '')
  const full = h0.length === 3 ? h0.split('').map((c) => c + c).join('') : h0
  const r = parseInt(full.slice(0, 2), 16) / 255
  const g = parseInt(full.slice(2, 4), 16) / 255
  const b = parseInt(full.slice(4, 6), 16) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  const l = (max + min) / 2
  let h = 0
  if (d !== 0) {
    if (max === r) h = 60 * (((g - b) / d) % 6)
    else if (max === g) h = 60 * ((b - r) / d) + 120
    else h = 60 * ((r - g) / d) + 240
  }
  if (h < 0) h += 360
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
  return { h, s, l }
}

/** 两支色的色相差(度, 0~180) */
function hueDistance(a: string, b: string): number {
  const d = Math.abs(hexToHsl(a).h - hexToHsl(b).h) % 360
  return d > 180 ? 360 - d : d
}

/** 两支色的饱和度差 */
function satDistance(a: string, b: string): number {
  return Math.abs(hexToHsl(a).s - hexToHsl(b).s)
}

/** WCAG 对比度(模块级副本; 上面那个 describe 内部有一份同名的, 互不影响) */
function contrastRatioOf(fg: string, bg: string): number {
  const a = relativeLuminance(fg)
  const b = relativeLuminance(bg)
  const [hi, lo] = a > b ? [a, b] : [b, a]
  return (hi + 0.05) / (lo + 0.05)
}

describe('主题契约 · 场景身份色家族', () => {
  const BY_MODE = {
    light: { tokens: EFFECTIVE.light, admin: ADMIN_LIGHT },
    dark: { tokens: EFFECTIVE.dark, admin: ADMIN_DARK },
  } as const

  it.each(['light', 'dark'] as const)(
    '%s 模式: 只有四族(browse / seat / order / payment), 每族恰好 base / ink / rgb 三个成员',
    (mode) => {
      const tokens = BY_MODE[mode].tokens
      const sceneDecls = [...tokens.keys()].filter((k) => k.startsWith('--scene-')).sort()
      expect(sceneDecls).toEqual([
        '--scene-browse',
        '--scene-browse-ink',
        '--scene-browse-rgb',
        '--scene-order',
        '--scene-order-ink',
        '--scene-order-rgb',
        '--scene-payment',
        '--scene-payment-ink',
        '--scene-payment-rgb',
        '--scene-seat',
        '--scene-seat-ink',
        '--scene-seat-rgb',
      ])
    },
  )

  it.each(['light', 'dark'] as const)('%s 模式: 每族的 base / ink / rgb 都存在且值合法', (mode) => {
    const tokens = BY_MODE[mode].tokens
    for (const family of SCENE_FAMILIES) {
      expect(resolveToken(`--scene-${family}`, tokens), `${mode} ${family} base 缺失`).toMatch(/^#[0-9a-fA-F]{6}$/)
      expect(resolveToken(`--scene-${family}-ink`, tokens), `${mode} ${family} ink 缺失`).toMatch(/^#[0-9a-fA-F]{6}$/)
      expect(tokens.get(`--scene-${family}-rgb`), `${mode} ${family} rgb 缺失`).toMatch(/^\d+\s+\d+\s+\d+$/)
    }
  })

  it.each(['light', 'dark'] as const)(
    '%s 模式: ink 在三种纸面 + 管理端纸面上均达 4.5:1 (AA 正文线)',
    (mode) => {
      const { tokens, admin } = BY_MODE[mode]
      const surfaces: [string, string][] = [
        ['--paper', resolveToken('--paper', tokens)],
        ['--paper-raised', resolveToken('--paper-raised', tokens)],
        ['--paper-sunk', resolveToken('--paper-sunk', tokens)],
        ['admin --paper', admin.get('--paper') as string],
      ]
      // 暗底额外验「座位面板底」—— 暗色下身份色的 ink 是亮的那一支,
      // 落在暗色票面上仍然要读得清。亮色模式的 ink 是深色那一支,
      // 本来就不该出现在深色面板上(面板上只用亮 base), 故不在亮色模式里验。
      if (mode === 'dark') {
        surfaces.push(['座位面板底 --hall', resolveToken('--hall', tokens)])
      }
      for (const family of SCENE_FAMILIES) {
        const ink = resolveToken(`--scene-${family}-ink`, tokens)
        for (const [name, bg] of surfaces) {
          const r = contrastRatioOf(ink, bg)
          expect(r, `${mode} ${family} ink (${ink}) on ${name} (${bg}) 只有 ${r.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
        }
      }
    },
  )

  it.each(['light', 'dark'] as const)(
    '%s 模式: base 与全局强调色 --accent 的色相差 ≥ 25° (同屏必须可分辨)',
    (mode) => {
      const { tokens } = BY_MODE[mode]
      const accent = resolveToken('--accent', tokens)
      for (const family of SCENE_FAMILIES) {
        const base = resolveToken(`--scene-${family}`, tokens)
        const d = hueDistance(base, accent)
        expect(
          d,
          `${mode} ${family} base (${base}) 与 --accent (${accent}) 色相只差 ${d.toFixed(1)}° —— 同屏会读混`,
        ).toBeGreaterThanOrEqual(25)
      }
    },
  )

})

describe('主题契约 · 场景身份色与语义色的可分辨性', () => {
  it.each(['light', 'dark'] as const)(
    '%s 模式: 同域语义色与身份色 base 可分辨: 色相差 ≥25 或 灰阶差 ≥25 或 饱和度差 ≥0.25',
    (mode) => {
      const tokens = EFFECTIVE[mode]
      // 三个通道任一成立即可 —— 色相是最强的可分辨信号, 而低饱和深色的语义色
      // (墨绿 / 暗金 / 暗红) 与任何饱和中调色的灰阶差和饱和度差都 <25/<0.25,
      // 只认后两个通道的话, 品红/紫这类色永远过不了(实测: 灰阶差 5~20, 饱和度差 0.00~0.11)。
      for (const family of SCENE_FAMILIES) {
        const base = resolveToken(`--scene-${family}`, tokens)
        for (const peer of effectiveSemanticPeers(family)) {
          const peerHex = resolveToken(peer, tokens)
          const hueGap = hueDistance(base, peerHex)
          const grayGap = Math.abs(grayscaleLevel(base) - grayscaleLevel(peerHex))
          const satGap = satDistance(base, peerHex)
          expect(
            hueGap >= 25 || grayGap >= 25 || satGap >= 0.25,
            `${mode} ${family} base (${base}) 与 ${peer} (${peerHex}) 分不开: `
              + `色相差 ${hueGap.toFixed(1)}°, 灰阶差 ${grayGap}, 饱和度差 ${satGap.toFixed(3)}`,
          ).toBe(true)
        }
      }
    },
  )

  it.each(['light', 'dark'] as const)(
    '%s 模式: base 底上的文字可读: --ink 与 --ink-inverse 至少一个达 4.5:1',
    (mode) => {
      const tokens = EFFECTIVE[mode]
      // base 都是高饱和色, 反色字未必读得清 —— 所以规定「用对比度更高的那支墨色」,
      // 而不是固定用某一支。浅 base 走 --ink, 深 base 走 --ink-inverse。
      // 暗底下两支墨色的明暗关系是反的, 但「取更高对比度那支」的规则不变。
      for (const family of SCENE_FAMILIES) {
        const base = resolveToken(`--scene-${family}`, tokens)
        const onInk = contrastRatioOf(resolveToken('--ink', tokens), base)
        const onInverse = contrastRatioOf(resolveToken('--ink-inverse', tokens), base)
        expect(
          Math.max(onInk, onInverse),
          `${mode} ${family} base (${base}) 上两种墨色都读不清: `
            + `--ink ${onInk.toFixed(2)}:1 / --ink-inverse ${onInverse.toFixed(2)}:1`,
        ).toBeGreaterThanOrEqual(4.5)
      }
    },
  )

  it('域内语义色集合确实扫到了东西(防扫描逻辑失效导致假绿)', () => {
    // seat 域的 WS 状态条直接引用了 ok / warn / danger 三色
    expect(effectiveSemanticPeers('seat')).toEqual(['--danger', '--ok', '--warn'])
    // order 域: 状态脉冲点直接引用 --danger, 状态标签经 el-tag 间接用到 ok / warn
    expect(effectiveSemanticPeers('order')).toEqual(['--danger', '--ok', '--warn'])
    // payment 域: 错误提示与成功态直接引用 ok / danger, 状态标签经 el-tag 间接用到 warn
    expect(effectiveSemanticPeers('payment')).toEqual(['--danger', '--ok', '--warn'])
    // browse 域既不直接引用也不经 el-tag 间接引用
    expect(effectiveSemanticPeers('browse')).toEqual([])
  })
})

describe('主题契约 · 场景身份色域隔离', () => {
  it('每支身份色只出现在它自己的域里', () => {
    const offenders: string[] = []
    for (const file of listSourceFiles(SRC)) {
      const rel = file.slice(SRC.length + 1).replace(/\\/g, '/')
      const text = stripComments(readFileSync(file, 'utf8'))
      for (const family of SCENE_FAMILIES) {
        if (!text.includes(`var(--scene-${family}`)) continue
        const allowed = SCENE_DOMAINS[family]
        if (!allowed.includes(rel)) {
          offenders.push(`${rel} 用了 --scene-${family}  (只允许: ${allowed.join(', ')})`)
        }
      }
    }
    expect(offenders, `场景身份色越域:\n${offenders.join('\n')}`).toEqual([])
  })

  it('管理端不设页面身份色 —— 一个 --scene-* 都不许出现', () => {
    // 管理端是密集报表控制台, 不是用户端的载体页。它只借用户端的**方法论**
    // (颜色按角色分工), 不借票根符号, 也不设自己的身份色:
    // 色轮已被「全局印色 + 4 族身份色 + 3 支语义色」占满, 再加第五支必然撞色。
    // 这条断言让「管理端不碰 --scene-*」变成可执行的契约, 而不是口头约定。
    const adminFiles = [
      'views/admin/AdminHome.vue',
      'views/admin/Dashboard.vue',
      'views/admin/LiveDashboard.vue',
      'views/admin/MovieManage.vue',
      'views/admin/HallManage.vue',
      'views/admin/SessionManage.vue',
      'views/admin/constants.ts',
      'views/admin/trendChart.ts',
    ]
    const offenders: string[] = []
    for (const rel of adminFiles) {
      const text = stripComments(readSrc(rel))
      for (const m of text.matchAll(/var\(\s*(--scene-[\w-]+)/g)) {
        offenders.push(`${rel}  用了 ${m[1]}`)
      }
    }
    expect(offenders, `管理端不设页面身份色:\n${offenders.join('\n')}`).toEqual([])
  })

  it('每一族都真的被用上了 —— 不做投机性 token', () => {
    for (const family of SCENE_FAMILIES) {
      const used = listSourceFiles(SRC).some((file) =>
        stripComments(readFileSync(file, 'utf8')).includes(`var(--scene-${family}`),
      )
      expect(used, `--scene-${family} 定义了却没被任何 .vue/.ts 用上`).toBe(true)
    }
  })
})

describe('主题契约 · 场景身份色禁入全局强调面', () => {
  const SOURCES: [string, string][] = [
    ['styles/main.css', MAIN_CSS],
    ['App.vue', readSrc('App.vue')],
  ]

  /**
   * 全局强调面清单: 这些表面在所有页面都出现, 身份色一进来, 全局印色就不再是全局印色。
   * 主 CTA / 链接 / 焦点环 / 章节墨条 / 通用按钮 / 导航 chip / 选中态 / 品牌悬停 —— 一律走 --accent。
   */
  const FORBIDDEN: { label: string; match: (sel: string) => boolean }[] = [
    { label: '链接 a', match: (s) => s.trim() === 'a' },
    { label: ':focus-visible 焦点环', match: (s) => s.trim() === ':focus-visible' },
    { label: '::selection 选中态', match: (s) => s.trim() === '::selection' },
    { label: '.section-title::before 章节墨条(全局默认)', match: (s) => s.trim() === '.section-title::before' },
    { label: '.el-button--primary 主按钮', match: (s) => s.trim().startsWith('.el-button--primary') },
    { label: '.chip-primary 主 chip', match: (s) => s.includes('.chip-primary') },
    { label: '.chip-login 登录 chip', match: (s) => s.includes('.chip-login') },
    { label: '.btn-action 通用按钮', match: (s) => s.includes('.btn-action') },
    { label: '.brand:hover .brand-text 品牌悬停', match: (s) => s.includes('.brand:hover .brand-text') },
  ]

  it('全局强调面上一律走 --accent, 不得出现场景身份色', () => {
    const offenders: string[] = []
    for (const [file, css] of SOURCES) {
      const clean = stripComments(css)
      for (const m of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        const selector = m[1].trim()
        for (const f of FORBIDDEN) {
          if (!f.match(selector)) continue
          if (m[2].includes('var(--scene-')) {
            offenders.push(`${file}  [${f.label}]  ${selector}  →  ${m[2].trim().slice(0, 70)}`)
          }
        }
      }
    }
    expect(offenders, `场景身份色侵入了全局强调面:\n${offenders.join('\n')}`).toEqual([])
  })
})

// =====================================================================
// 章节墨条按所属域的身份色 (2026-10-20 批次二)
// =====================================================================
/**
 * R4 放宽后, 章节墨条不再守全局印色, 改守**所属域**的身份色。
 *
 * <p>理由: 章节墨条是「这一屏属于哪个业务域」的标记。满屏的蓝墨条正是
 * 「一片蓝」的来源之一 —— 蓝不再只是「主 CTA / 链接」的角色, 它还被拿来
 * 当结构色, 于是它在每一屏都出现, 也就等于没出现。
 *
 * <p>约束仍然很硬: **只能用自己域的身份色**。browse 域的页面不许用 order 的紫,
 * 管理端更不许有墨条(它是控制台, 层级靠结构)。
 */
describe('主题契约 · 章节墨条按所属域的身份色', () => {
  /** 期望的 (文件, 该域身份色) 组合 */
  const DOMAIN_SECTION_BAR: [string, string][] = [
    ['views/Home.vue', '--scene-browse-ink'],
    ['views/MovieDetail.vue', '--scene-browse-ink'],
    ['views/OrderList.vue', '--scene-order-ink'],
  ]

  it.each(DOMAIN_SECTION_BAR)('%s 的章节墨条用 %s', (rel, expected) => {
    const src = readSrc(rel)
    const block = src.match(/\.section-title::before\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(block, `${rel} 里找不到 .section-title::before 规则`).not.toBe('')
    expect(block, `${rel} 的章节墨条没有用 ${expected}`).toContain(`var(${expected})`)
    // 不许顺手用别的域的身份色
    for (const family of SCENE_FAMILIES) {
      const other = `--scene-${family}-ink`
      if (other === expected) continue
      expect(block, `${rel} 的章节墨条越域用了 ${other}`).not.toContain(`var(${other})`)
    }
  })

  it('全局默认(没有域身份的页面)仍然是 --accent', () => {
    // main.css 里那条是兜底: Login / NotFound 等不属于任何业务域的页面用它
    expect(MAIN_CSS).toMatch(/\.section-title::before\s*\{[^}]*background:\s*var\(--accent\)/)
  })

  it('管理端不用章节墨条 —— 控制台的层级靠结构', () => {
    for (const rel of [
      'views/admin/AdminHome.vue',
      'views/admin/Dashboard.vue',
      'views/admin/LiveDashboard.vue',
      'views/admin/MovieManage.vue',
      'views/admin/HallManage.vue',
      'views/admin/SessionManage.vue',
    ]) {
      expect(readSrc(rel), `${rel} 不该用 .section-title::before`).not.toContain('.section-title::before')
    }
  })
})

// =====================================================================
// 双主题 token 覆盖完整性 —— 暗底漏配即红
// =====================================================================
/**
 * 2026-10-20 双主题。
 *
 * <p><b>为什么不是「两套 token 名集合完全相同」</b>: 暗色作用域只声明「会变」的那些 token
 * (同名覆盖), 模式无关的 token(字体 / 圆角 / 暗场家族)只在 :root 声明一次。
 * 强行让两套名字集合相同 = 把 20 多个模式无关 token 复制进暗色块, 纯噪音, 而且
 * 复制出来的同值声明本来就是漂移的温床。
 *
 * <p>真正要防的是**漏配**: 某个该换值的 token 忘了在暗色作用域里覆盖, 它就留着
 * 亮色值落在暗底上 —— 墨色读不清、身份色撞色、印色对比度不足。所以改成四条
 * 更有针对性的断言:
 * <ol>
 *   <li>暗色作用域里的每个 token 名都必须在亮色作用域里存在(无孤儿);</li>
 *   <li>{@link MUST_SWITCH} 清单里的每个 token 必须两处都有、且取值**不同**;</li>
 *   <li>两处都声明了、且取值为字面量的 token, 取值必须不同(防止复制亮色值);</li>
 *   <li>只在 :root 声明一次的 token, 暗色作用域不得重复声明。</li>
 * </ol>
 */
describe('主题契约 · 双主题 token 覆盖完整性', () => {
  it('两套作用域的 token 名差异, 必须全部落在模式无关白名单里', () => {
    // 计划 §3.10 #3 的原话是「两套 token 名集合必须完全一致」。字面做到它, 得把
    // 字体/圆角/过渡/暗场家族这 21 个模式无关 token 复制进暗色块 —— 纯噪音, 而且
    // 与 §3.10 #14「--hall-deep 是模式无关 token, 不进暗色作用域」自相矛盾。
    //
    // 所以这里把契约写成可执行且不自相矛盾的版本:
    //   **差异集合必须恰好等于模式无关白名单** ——
    //   白名单外出现任何差异(漏配了某个该换值的 token, 或多出一个只有一边有的
    //   token), 都说明两套作用域对「谁负责哪些 token」的理解已经分叉。
    const lightNames = new Set(LIGHT.keys())
    const darkNames = new Set(DARK.keys())
    const onlyLight = [...lightNames].filter((n) => !darkNames.has(n)).sort()
    const onlyDark = [...darkNames].filter((n) => !lightNames.has(n)).sort()

    const unexpected = [
      ...onlyLight.filter((n) => !MODE_INDEPENDENT.includes(n)),
      ...onlyDark,
    ]
    expect(
      unexpected,
      `两套作用域的 token 名差异超出模式无关白名单(漏配或多配): ${unexpected.join(', ')}`,
    ).toEqual([])

    // 白名单也不能虚列: 列了却没真的只在亮色出现, 说明白名单该更新或 token 该删
    expect(onlyLight, '模式无关白名单与实际差异集合对不上').toEqual([...MODE_INDEPENDENT].sort())
  })

  it('必须换值的 token 清单: 两处都有, 且取值不同(漏配即红)', () => {
    const problems: string[] = []
    for (const name of MUST_SWITCH) {
      if (!LIGHT.has(name)) {
        problems.push(`${name} 亮色作用域缺失`)
        continue
      }
      if (!DARK.has(name)) {
        problems.push(`${name} 暗色作用域漏配 —— 它会留着亮色值落在暗底上`)
        continue
      }
      if (LIGHT.get(name) === DARK.get(name)) {
        problems.push(`${name} 两模式取值相同 —— 暗色块里没真正换值`)
      }
    }
    expect(problems, problems.join('\n')).toEqual([])
  })

  it('两处都声明的 token, 字面量取值必须不同(防止把亮色值复制进暗色块)', () => {
    // var() / color-mix() 的声明在两处字面相同, 但计算值不同(跟着 --accent 走),
    // 那不是复制粘贴, 是派生关系 —— 这类不参与本条。
    const isLiteral = (v: string) => !v.includes('var(') && !v.includes('color-mix')
    const same: string[] = []
    for (const [name, value] of DARK) {
      if (!LIGHT.has(name)) continue
      const lightValue = LIGHT.get(name) as string
      if (!isLiteral(value) || !isLiteral(lightValue)) continue
      if (lightValue === value) same.push(name)
    }
    expect(same, `这些 token 在两个作用域里字面量相同, 应删掉暗色里的重复声明: ${same.join(', ')}`).toEqual([])
  })

  it('只在 :root 声明一次的 token, 暗色作用域不得重复声明', () => {
    const duplicated = MODE_INDEPENDENT.filter((n) => DARK.has(n))
    expect(duplicated, `这些 token 不该在暗色作用域重复声明: ${duplicated.join(', ')}`).toEqual([])
    for (const n of MODE_INDEPENDENT) {
      expect(LIGHT.has(n), `${n} 应在 :root 声明`).toBe(true)
    }
  })

  it('两个作用域都非空(防选择器写错导致整套断言空转)', () => {
    expect(DARK.size, '暗色作用域没解析到任何 token —— 选择器写错了?').toBeGreaterThan(40)
    expect(LIGHT.size, '亮色作用域没解析到任何 token').toBeGreaterThan(40)
  })
})
