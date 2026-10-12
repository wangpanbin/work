import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

/**
 * 主题 token 契约测试.
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
 * 去掉 `/* ... *\/` 与 `<!-- ... -->` 注释.
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
  it('--accent 是皇家蓝 #2563eb', () => {
    expect(MAIN_CSS).toMatch(/--accent:\s*#2563eb\s*;/)
  })

  it('主色的 hover / press / wash / stamp / rgb 分量齐全', () => {
    expect(MAIN_CSS).toMatch(/--accent-hover:\s*#1d4ed8\s*;/)
    expect(MAIN_CSS).toMatch(/--accent-press:\s*#1e40af\s*;/)
    expect(MAIN_CSS).toMatch(/--accent-wash:\s*rgba\(37,\s*99,\s*235,\s*0\.08\)\s*;/)
    expect(MAIN_CSS).toMatch(/--accent-stamp:\s*rgba\(37,\s*99,\s*235,\s*0\.14\)\s*;/)
    expect(MAIN_CSS).toMatch(/--accent-rgb:\s*37\s+99\s+235\s*;/)
  })

  it('Element Plus primary 六阶 + dark-2 全部就位', () => {
    const ramp: [string, string][] = [
      ['--el-color-primary', '#2563eb'],
      ['--el-color-primary-light-3', '#6692f1'],
      ['--el-color-primary-light-5', '#92b1f5'],
      ['--el-color-primary-light-7', '#bed0f9'],
      ['--el-color-primary-light-8', '#d3e0fb'],
      ['--el-color-primary-light-9', '#e9effd'],
      ['--el-color-primary-dark-2', '#1e4fbc'],
    ]
    for (const [token, hex] of ramp) {
      expect(MAIN_CSS, `${token} 应为 ${hex}`).toMatch(
        new RegExp(`${token}:\\s*${hex}\\s*;`),
      )
    }
  })

  it('全站只有一个强调色 —— 侧边栏深靛蓝是结构底色, 不许再加第二个印色', () => {
    // :root 里只允许一个 --accent, 不得出现第二套品牌色
    const rootBlock = MAIN_CSS.split(/\.app-main\.is-admin/)[0]
    const accentDecls = rootBlock.match(/^\s*--accent[a-z-]*\s*:/gm) ?? []
    // --accent / hover / press / wash / stamp / rgb 共 6 条, 多一条就是加了新品牌色
    expect(accentDecls.length).toBe(6)
  })

  it('管理端侧边栏是深靛蓝底, 且不再复用浅色纸面', () => {
    const admin = readSrc('views/admin/AdminHome.vue')
    const asideBlock = admin.match(/\.aside\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(asideBlock, '.aside 块没匹配到').not.toBe('')
    expect(asideBlock).toContain('--aside-bg: #0f172a')
    expect(asideBlock).toContain('background: var(--aside-bg)')
    expect(asideBlock).not.toContain('var(--paper-raised)')
  })

  it('侧边栏激活态用 tint + 左亮条, 不是深底上糊成一团的实心块', () => {
    const admin = readSrc('views/admin/AdminHome.vue')
    const active = admin.match(/\.aside\s*:deep\(\.el-menu-item\.is-active\)\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(active).not.toBe('')
    expect(active).toContain('rgb(var(--accent-rgb) / 0.16)')
    expect(active).toContain('inset 3px 0 0 var(--accent)')
  })

  it('管理端纸面覆盖已同步换冷调, 不留旧暖灰', () => {
    const block = MAIN_CSS.match(/\.app-main\.is-admin\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(block).not.toBe('')
    for (const stale of ['#eef0f1', '#f7f8f9', '#e4e8ea', '#d0d5d8', '#b3bcc2']) {
      expect(block, `管理端覆盖块残留旧值 ${stale}`).not.toContain(stale)
    }
    expect(block).toContain('--paper: #e9edf3')
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
  function seatGrayscaleLevels(): Record<string, number> {
    const tokens = parseRootTokens(MAIN_CSS)
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

  it('5 态都能解析出合法 hex（token 引用链没断）', () => {
    for (const [k, v] of Object.entries(seatGrayscaleLevels())) {
      expect(Number.isFinite(v), `${k} 灰度值不是数字: ${v}`).toBe(true)
    }
  })

  it('相邻灰度差 ≥ 2, 5 态去色后仍可分辨', () => {
    const levels = seatGrayscaleLevels()
    const sorted = Object.entries(levels).sort((a, b) => a[1] - b[1])
    const gaps = sorted.slice(1).map(([, v], i) => v - sorted[i][1])

    const report = sorted.map(([k, v]) => `${k}:${v}`).join(' < ')
    expect(
      Math.min(...gaps),
      `相邻灰度间隔过小 —— 去色后会糊: ${report}`,
    ).toBeGreaterThanOrEqual(2)
  })

  it('我锁座位保持墨绿: 动这个值会把间隔压回 1 级', () => {
    const levels = seatGrayscaleLevels()
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

