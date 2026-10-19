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

  it('Element Plus primary 六阶 + dark-2 全部就位, 且都从 --accent 派生', () => {
    // EP 色阶已不再手写 hex, 而是从 --accent 派生: 主色直接引用 token,
    // light-N = 混入 (N*10)% 白, dark-2 = 混入 20% 黑。
    // 这样换印色只需改 --accent 一处, 6 档自动跟随(见 main.css :root 注释)。
    // 基准色由上面「--accent 是皇家蓝 #2563eb」那条锁定, 这里只锁"派生关系还在"。
    expect(MAIN_CSS, '--el-color-primary 应引用 --accent').toMatch(
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
      expect(MAIN_CSS, `${token} 应为 ${pct}% 白 + --accent`).toMatch(
        new RegExp(`${token}:\\s*color-mix\\(in srgb,\\s*#fff\\s+${pct}%,\\s*var\\(--accent\\)\\)\\s*;`),
      )
    }
    expect(MAIN_CSS, '--el-color-primary-dark-2 应为 20% 黑 + --accent').toMatch(
      /--el-color-primary-dark-2:\s*color-mix\(in srgb,\s*#000\s+20%,\s*var\(--accent\)\)\s*;/,
    )
  })

  it('全站只有一个强调色 —— 侧边栏深靛蓝是结构底色, 不许再加第二个印色', () => {
    // :root 里只允许一套 --accent 家族, 不得出现第二套品牌色
    const rootBlock = MAIN_CSS.split(/\.app-main\.is-admin/)[0]
    const accentDecls = rootBlock.match(/^\s*--accent[a-z-]*\s*:/gm) ?? []
    // 家族成员 7 条: --accent / hover / press / wash / stamp / rgb / disabled。
    // --accent-disabled(#a7bfe8) 是主按钮 disabled 态的同族浅色 tint, 2026-10-12 认定
    // 为「同一印色家族」而非新品牌色, 故计入。再多一条即说明真加了第二套印色, 需人工裁决。
    expect(accentDecls.length).toBe(7)
    expect(accentDecls.some((d) => d.includes('--accent-disabled'))).toBe(true)
  })

  it('管理端侧边栏是深靛蓝底, 且不再复用浅色纸面', () => {
    const admin = readSrc('views/admin/AdminHome.vue')
    const asideBlock = admin.match(/\.aside\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(asideBlock, '.aside 块没匹配到').not.toBe('')
    // 2026-10-12 收口: 侧栏暗面改为引用 --hall 家族 —— 锁「引用」而非字面量, 换肤才跟得动
    expect(asideBlock).toContain('--aside-bg: var(--hall)')
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


// =====================================================================
// describe 追加于 2026-10-12「UI 视觉质感优化」。
// 只新增, 不修改/不删除上面任何一条既有断言。
// 背景: 实测 --ink-3 (#7c8ba1) 在纸面上只有 3.06:1, 低于 WCAG AA 的 4.5:1;
//      它却是全站用得最广的三级文字 token。本组把「对比度达标」固化成闸门,
//      防止后续换肤/调色又把它调回不可读。
// =====================================================================
describe('主题契约 · 文字对比度达 WCAG AA', () => {
  const tokens = parseRootTokens(MAIN_CSS)

  /** WCAG 2.x 对比度 = (L1+0.05)/(L2+0.05) */
  function contrastRatio(fg: string, bg: string): number {
    const a = relativeLuminance(fg)
    const b = relativeLuminance(bg)
    const [hi, lo] = a > b ? [a, b] : [b, a]
    return (hi + 0.05) / (lo + 0.05)
  }

  function hexToken(name: string): string {
    return resolveToken(name, tokens)
  }

  /** 管理端纸面覆盖块里的 token(与 :root 同名但不同值) */
  function adminToken(name: string): string {
    const block = MAIN_CSS.match(/\.app-main\.is-admin\s*\{([^}]*)\}/)?.[1] ?? ''
    const m = block.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{3,8})`))
    if (!m) throw new Error(`.app-main.is-admin 里找不到 ${name}`)
    return m[1]
  }

  it('--ink-3 在三种纸面上均达 4.5:1 (AA 正文线)', () => {
    const ink3 = hexToken('--ink-3')
    const surfaces: [string, string][] = [
      ['--paper', hexToken('--paper')],
      ['--paper-raised', hexToken('--paper-raised')],
      ['--paper-sunk', hexToken('--paper-sunk')],
    ]
    for (const [name, bg] of surfaces) {
      const r = contrastRatio(ink3, bg)
      expect(r, `--ink-3 (${ink3}) on ${name} (${bg}) 只有 ${r.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('--ink-3 在管理端纸面上同样达 4.5:1', () => {
    const ink3 = hexToken('--ink-3')
    for (const name of ['--paper', '--paper-raised', '--paper-sunk']) {
      const r = contrastRatio(ink3, adminToken(name))
      expect(r, `--ink-3 (${ink3}) on admin ${name} 只有 ${r.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('--ink-2 / --ink / --accent 不低于各自基线 (防回归式变暗)', () => {
    // 基线取 2026-10-12 换肤后的实测值; 允许变亮, 不允许明显变暗。
    // ⚠️ 基线一律向下取整到 2 位: 直接抄打印出的 15.77 会因为浮点尾数
    //    (真值 15.7683) 把当前值自己判为不达标 —— 断言必须能被现状满足。
    const baselines: [string, number][] = [
      ['--ink-2', 6.6],
      ['--ink', 15.7],
      ['--accent', 4.5],
    ]
    for (const [name, floor] of baselines) {
      const r = contrastRatio(hexToken(name), hexToken('--paper'))
      expect(r, `${name} on --paper 降到 ${r.toFixed(2)}:1, 低于基线 ${floor}`).toBeGreaterThanOrEqual(floor)
    }
  })

  it('卡片描边可见, 且 --rule 仍浅于 --rule-strong (层级不倒置)', () => {
    const rule = hexToken('--rule')
    const strong = hexToken('--rule-strong')
    expect(contrastRatio(rule, hexToken('--paper')), '描边太淡, 卡片边界会糊').toBeGreaterThan(1.4)
    // 相对亮度越高 = 颜色越浅; --rule 必须比 --rule-strong 浅, 强线才强得过弱线
    expect(
      relativeLuminance(rule),
      `--rule (${rule}) 应浅于 --rule-strong (${strong}), 线层级倒置了`,
    ).toBeGreaterThan(relativeLuminance(strong))
  })

  it('管理端 --rule 与 :root 同步 (防残留旧描边)', () => {
    expect(adminToken('--rule')).toBe(hexToken('--rule'))
  })
})

// =====================================================================
describe('主题契约 · favicon 跟随主题', () => {
  /**
   * 2026-10-12 换肤回归: index.html 的 data-URI 一直写着旧暖米纸 + 朱红,
   * 换肤时漏改, 标签页至今是旧图标。本组把它锁住。
   */
  // SRC = cinema-web/src, 故 index.html 在其同级上一层的 cinema-web/ 下
  const HTML = readFileSync(resolve(SRC, '../index.html'), 'utf8')

  it('favicon 不含已下线的暖色值', () => {
    for (const retired of ['%23f2ece1', '%23b8352a']) {
      expect(HTML, `index.html favicon 仍含旧色 ${retired}`).not.toContain(retired)
    }
  })

  it('favicon 使用当前主题的 --paper / --accent', () => {
    const tokens = parseRootTokens(MAIN_CSS)
    const paper = resolveToken('--paper', tokens).replace('#', '%23').toLowerCase()
    const accent = resolveToken('--accent', tokens).replace('#', '%23').toLowerCase()
    expect(HTML).toContain(paper)
    expect(HTML).toContain(accent)
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
const SCENE_FAMILIES = ['browse', 'seat'] as const

/** 每个身份色允许出现的文件(域隔离) */
const SCENE_DOMAINS: Record<string, string[]> = {
  browse: ['views/Home.vue', 'views/MovieDetail.vue'],
  seat: ['views/SeatSelect.vue', 'components/SeatItem.vue'],
}

/**
 * 每个域内实际会出现的语义色(实施时 grep 固化)。
 * browse 域全文未引用任何 --ok/--warn/--danger, 所以是空数组;
 * seat 域的 WS 状态条三色齐用。
 */
const SCENE_SEMANTIC_PEERS: Record<string, string[]> = {
  browse: [],
  seat: ['--ok', '--warn', '--danger'],
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
  const tokens = parseRootTokens(MAIN_CSS)

  it('只有两族(browse / seat), 每族恰好 base / ink / rgb 三个成员', () => {
    const sceneDecls = [...tokens.keys()].filter((k) => k.startsWith('--scene-')).sort()
    expect(sceneDecls).toEqual([
      '--scene-browse',
      '--scene-browse-ink',
      '--scene-browse-rgb',
      '--scene-seat',
      '--scene-seat-ink',
      '--scene-seat-rgb',
    ])
  })

  it('每族的 base / ink / rgb 都存在且值合法', () => {
    for (const family of SCENE_FAMILIES) {
      expect(resolveToken(`--scene-${family}`, tokens), `${family} base 缺失`).toMatch(/^#[0-9a-fA-F]{6}$/)
      expect(resolveToken(`--scene-${family}-ink`, tokens), `${family} ink 缺失`).toMatch(/^#[0-9a-fA-F]{6}$/)
      expect(tokens.get(`--scene-${family}-rgb`), `${family} rgb 缺失`).toMatch(/^\d+\s+\d+\s+\d+$/)
    }
  })

  it('ink 在三种纸面 + 管理端纸面上均达 4.5:1 (AA 正文线)', () => {
    const adminBlock = MAIN_CSS.match(/\.app-main\.is-admin\s*\{([^}]*)\}/)?.[1] ?? ''
    const adminPaper = adminBlock.match(/--paper:\s*(#[0-9a-fA-F]{6})/)?.[1]
    expect(adminPaper, '管理端 --paper 取不到').toBeTruthy()
    const surfaces: [string, string][] = [
      ['--paper', resolveToken('--paper', tokens)],
      ['--paper-raised', resolveToken('--paper-raised', tokens)],
      ['--paper-sunk', resolveToken('--paper-sunk', tokens)],
      ['admin --paper', adminPaper as string],
    ]
    for (const family of SCENE_FAMILIES) {
      const ink = resolveToken(`--scene-${family}-ink`, tokens)
      for (const [name, bg] of surfaces) {
        const r = contrastRatioOf(ink, bg)
        expect(r, `${family} ink (${ink}) on ${name} (${bg}) 只有 ${r.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('base 与全局强调色 --accent 的色相差 ≥ 25° (同屏必须可分辨)', () => {
    const accent = resolveToken('--accent', tokens)
    for (const family of SCENE_FAMILIES) {
      const base = resolveToken(`--scene-${family}`, tokens)
      const d = hueDistance(base, accent)
      expect(
        d,
        `${family} base (${base}) 与 --accent (${accent}) 色相只差 ${d.toFixed(1)}° —— 同屏会读混`,
      ).toBeGreaterThanOrEqual(25)
    }
  })

  it('同域语义色与身份色 base 靠「明度差 ≥25 或 饱和度差 ≥0.25」区分', () => {
    for (const family of SCENE_FAMILIES) {
      const base = resolveToken(`--scene-${family}`, tokens)
      for (const peer of SCENE_SEMANTIC_PEERS[family]) {
        const peerHex = resolveToken(peer, tokens)
        const grayGap = Math.abs(grayscaleLevel(base) - grayscaleLevel(peerHex))
        const satGap = satDistance(base, peerHex)
        expect(
          grayGap >= 25 || satGap >= 0.25,
          `${family} base (${base}) 与 ${peer} (${peerHex}) 分不开: 灰阶差 ${grayGap}, 饱和度差 ${satGap.toFixed(3)}`,
        ).toBe(true)
      }
    }
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
    { label: '.section-title::before 章节墨条', match: (s) => s.trim() === '.section-title::before' },
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
