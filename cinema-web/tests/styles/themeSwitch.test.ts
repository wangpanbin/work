import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

import {
  isThemePref,
  nextPref,
  readStoredPref,
  resolveTheme,
  writeStoredPref,
  THEME_PREFS,
  THEME_STORAGE_KEY,
} from '../../src/theme/resolve'
import { applyFavicon, applyTheme, FAVICON_DARK, FAVICON_LIGHT, FAVICON_LINK_ID } from '../../src/theme/apply'

/**
 * themeSwitch.test.ts — 主题切换的纯函数契约
 *
 * <p><b>为什么必须有它</b>: 主题是「首帧就生效」的东西 —— 用户在首屏看到的是哪套色板,
 * 由 `index.html` 的内联脚本决定, 那段逻辑不在任何组件里、也不被任何组件测试覆盖。
 * 一旦它坏了, 表现是「首帧白闪 / 深色模式进不去」, 属于很难从截图里一眼看出的问题。
 *
 * <p>本文件锁三件事:
 * <ol>
 *   <li>偏好 → 实际主题的解析(三态 + 系统偏好 + 非法值兜底);</li>
 *   <li>偏好持久化(storage 不可用时必须降级而不是抛错);</li>
 *   <li>{@code applyTheme} 落到 DOM 上的三件事: data-theme 属性 / dark 类名 / favicon。</li>
 * </ol>
 *
 * <p>另加一条静态断言: `index.html` 里的防白闪内联脚本不能被删掉。
 *
 * <p><b>脆性声明</b>: 本仓 vitest 是 `environment: 'node'`, 没有 jsdom,
 * 所以 DOM 侧用**手写的假 document** 注入, 验的是「我们往 DOM 上写了什么」,
 * 不是浏览器真实渲染。
 */

const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const INDEX_HTML = readFileSync(resolve(WEB_ROOT, 'index.html'), 'utf8')

describe('主题解析 · 偏好 → 实际主题', () => {
  it('三态偏好都存在', () => {
    expect([...THEME_PREFS]).toEqual(['auto', 'light', 'dark'])
  })

  it.each([
    ['auto', false, 'light'],
    ['auto', true, 'dark'],
    ['light', false, 'light'],
    ['light', true, 'light'],
    ['dark', false, 'dark'],
    ['dark', true, 'dark'],
  ] as const)('resolveTheme(%s, 系统深色=%s) → %s', (pref, sysDark, expected) => {
    expect(resolveTheme(pref, sysDark)).toBe(expected)
  })

  it('isThemePref 只认三个合法值', () => {
    for (const p of THEME_PREFS) expect(isThemePref(p)).toBe(true)
    for (const bad of ['', 'Dark', 'DARK', 'auto ', null, undefined, 1, {}]) {
      expect(isThemePref(bad), `${JSON.stringify(bad)} 不该被当成合法偏好`).toBe(false)
    }
  })

  it('三态循环: 跟随系统 → 亮 → 暗 → 跟随系统', () => {
    expect(nextPref('auto')).toBe('light')
    expect(nextPref('light')).toBe('dark')
    expect(nextPref('dark')).toBe('auto')
  })
})

describe('主题偏好持久化', () => {
  /** 极简 storage 替身 */
  function memStorage(initial: Record<string, string> = {}) {
    const store = { ...initial }
    return {
      getItem: (k: string) => (k in store ? store[k] : null),
      setItem: (k: string, v: string) => {
        store[k] = v
      },
      _store: store,
    }
  }

  it('读到合法值就用它', () => {
    const s = memStorage({ [THEME_STORAGE_KEY]: 'dark' })
    expect(readStoredPref(s)).toBe('dark')
  })

  it('没存过 / 存了非法值 → 回落到 auto', () => {
    expect(readStoredPref(memStorage())).toBe('auto')
    expect(readStoredPref(memStorage({ [THEME_STORAGE_KEY]: 'purple' }))).toBe('auto')
  })

  it('storage 读失败(隐私模式)→ 回落 auto, 不抛错', () => {
    const broken = {
      getItem: () => {
        throw new Error('denied')
      },
    }
    expect(readStoredPref(broken)).toBe('auto')
  })

  it('写入后可读回', () => {
    const s = memStorage()
    writeStoredPref('light', s)
    expect(readStoredPref(s)).toBe('light')
  })

  it('storage 写失败(配额满)→ 静默降级, 不抛错', () => {
    const broken = {
      setItem: () => {
        throw new Error('quota exceeded')
      },
    }
    expect(() => writeStoredPref('dark', broken)).not.toThrow()
  })
})

describe('主题偏好持久化 · 默认走全局 localStorage', () => {
  it('不传 storage 时读写的就是 globalThis.localStorage', () => {
    // 回归: 早先的实现是 `storage?.getItem(...)`。省略参数时 `storage` 是 undefined,
    // 于是永远读到 undefined —— 偏好恒为 'auto', 用户明明选了暗色却不生效。
    // 当时的单元测试只测了「显式传 storage」的路径, 没覆盖默认路径, 所以没抓到。
    const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    const store: Record<string, string> = {}
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: {
        getItem: (k: string) => (k in store ? store[k] : null),
        setItem: (k: string, v: string) => {
          store[k] = v
        },
      },
    })
    try {
      writeStoredPref('dark')
      expect(store[THEME_STORAGE_KEY], '不传 storage 时没写进全局 localStorage').toBe('dark')
      expect(readStoredPref(), '不传 storage 时没读到全局 localStorage').toBe('dark')
    } finally {
      if (original) Object.defineProperty(globalThis, 'localStorage', original)
      else delete (globalThis as { localStorage?: unknown }).localStorage
    }
  })
})

/**
 * 手写的假 document —— 本仓 vitest 是 node 环境, 没有 jsdom。
 * 只验「我们往 DOM 上写了什么」, 不验真实渲染。
 */
function fakeDoc() {
  const attrs: Record<string, string> = {}
  const classes = new Set<string>()
  const links: Record<string, { href: string }> = {}
  return {
    documentElement: {
      setAttribute: (k: string, v: string) => {
        attrs[k] = v
      },
      classList: {
        toggle: (c: string, on: boolean) => {
          if (on) classes.add(c)
          else classes.delete(c)
        },
      },
    },
    getElementById: (id: string) => links[id] ?? null,
    _attrs: attrs,
    _classes: classes,
    _links: links,
  }
}

describe('主题落到 DOM', () => {
  it('暗色: data-theme=dark + dark 类名 + 暗色 favicon', () => {
    const doc = fakeDoc()
    doc._links[FAVICON_LINK_ID] = { href: FAVICON_LIGHT }
    applyTheme('dark', doc as unknown as Document)
    expect(doc._attrs['data-theme']).toBe('dark')
    expect(doc._classes.has('dark')).toBe(true)
    expect(doc._links[FAVICON_LINK_ID].href).toBe(FAVICON_DARK)
  })

  it('亮色: data-theme=light + 摘掉 dark 类名 + 浅色 favicon', () => {
    const doc = fakeDoc()
    doc._links[FAVICON_LINK_ID] = { href: FAVICON_DARK }
    applyTheme('light', doc as unknown as Document)
    expect(doc._attrs['data-theme']).toBe('light')
    expect(doc._classes.has('dark')).toBe(false)
    expect(doc._links[FAVICON_LINK_ID].href).toBe(FAVICON_LIGHT)
  })

  it('两份 favicon 是不同的 data-URI', () => {
    expect(FAVICON_LIGHT).not.toBe(FAVICON_DARK)
    expect(FAVICON_LIGHT.startsWith('data:image/svg+xml,')).toBe(true)
    expect(FAVICON_DARK.startsWith('data:image/svg+xml,')).toBe(true)
  })

  it('找不到 favicon 链接时不抛错(favicon 不是关键路径)', () => {
    const doc = fakeDoc()
    expect(() => applyFavicon('dark', doc as unknown as Document)).not.toThrow()
  })
})

describe('首屏防白闪', () => {
  it('index.html 里有同步内联脚本: 读 localStorage + matchMedia, 设 data-theme', () => {
    // 这段脚本在 Vue 挂载前跑, 决定用户看到的首帧是哪套色板。
    // 它不在任何组件里, 删掉不会让任何组件测试变红 —— 所以必须静态锁住。
    expect(INDEX_HTML).toContain(THEME_STORAGE_KEY)
    expect(INDEX_HTML).toContain('prefers-color-scheme: dark')
    expect(INDEX_HTML).toContain("setAttribute('data-theme'")
    expect(INDEX_HTML).toContain("classList.add('dark')")
  })

  it('内联脚本在 <head> 里, 且在 #app 之前 —— 首帧之前就得定好主题', () => {
    const headEnd = INDEX_HTML.indexOf('</head>')
    const scriptAt = INDEX_HTML.indexOf("setAttribute('data-theme'")
    const appAt = INDEX_HTML.indexOf('id="app"')
    expect(headEnd).toBeGreaterThan(0)
    expect(scriptAt).toBeGreaterThan(0)
    expect(scriptAt).toBeLessThan(headEnd)
    expect(appAt).toBeGreaterThan(scriptAt)
  })
})
