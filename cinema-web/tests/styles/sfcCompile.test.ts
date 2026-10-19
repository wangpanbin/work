import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join, relative } from 'node:path'
import { parse, compileScript } from 'vue/compiler-sfc'

/**
 * SFC 编译闸门 —— `src` 下每个 `.vue` 都必须真的编译得过。
 *
 * <p><b>为什么需要它</b>: 2026-10-20 并行会话做 git hunk 拆分时, 逆操作吞掉了两个函数的
 * 收尾 `}`(`notifyReleasedWatchHits` 与 `onConfirm` 之后各一个, `</script>` 还被并到了
 * `}` 那行)。那一版提交快照的 {@code pnpm test} 是 <b>403/403 全绿</b> —— 因为本仓 vitest 跑在
 * {@code environment: 'node'}、没装 jsdom / @vue/test-utils, <b>根本不编译 .vue</b>。
 * 真正的暴露点是 {@code pnpm build}(`[vite:vue] Unexpected token`), 而 build 不在测试闸门里。
 *
 * <p>本文件把那条编译检查搬进测试套件: 用 {@code vue/compiler-sfc} 的
 * {@code compileScript} 真正解析每个 {@code <script setup>} 块的 JS 语法。
 * 注意 {@code parse()} 抓不到 script 块的语法错(它只切 SFC 结构), 必须走
 * {@code compileScript()} —— 后者内部做 babel 解析, 少一个 {@code }} 就会抛。
 *
 * <p><b>边界</b>: 本闸门只覆盖 JS/TS 语法与 SFC 结构, <b>不覆盖</b> CSS 级联、
 * 模板表达式求值、真实渲染结果 —— 那些仍然只能靠 {@code pnpm build} 与真浏览器验证。
 */

/** 源码根 */
const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')

/** 列出 src 下所有 .vue */
function listVue(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) listVue(full, acc)
    else if (entry.name.endsWith('.vue')) acc.push(full)
  }
  return acc
}

const VUE_FILES = listVue(SRC)

describe('SFC 编译闸门 —— 每个 .vue 都要编译得过', () => {
  it('src 下扫到了 .vue 文件(防扫描逻辑失效导致假绿)', () => {
    expect(VUE_FILES.length).toBeGreaterThan(5)
  })

  for (const file of VUE_FILES) {
    const rel = relative(SRC, file).replace(/\\/g, '/')
    it(`${rel} 的 SFC 结构与 script 语法都正确`, () => {
      const source = readFileSync(file, 'utf8')
      const { descriptor, errors } = parse(source, { filename: rel })

      // ① SFC 结构层: 标签配对 / 块划分
      const structural = errors.map((e) => (e as Error).message ?? String(e))
      expect(structural, `${rel} 的 SFC 结构解析失败`).toEqual([])

      // 没有 script 块的纯模板组件: 结构过了就算过
      if (!descriptor.script && !descriptor.scriptSetup) return

      // ② JS/TS 语法层: 少一个 } / 少一个引号 / 括号不配对, 都在这里抛
      let thrown: unknown = null
      try {
        compileScript(descriptor, { id: rel })
      } catch (e) {
        thrown = e
      }
      expect(
        thrown,
        `${rel} 的 script 块有语法错误: ${thrown instanceof Error ? thrown.message : String(thrown)}`,
      ).toBeNull()
    })
  }
})
