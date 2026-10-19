import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 视图层<b>模板/样式接线契约</b>测试。
 *
 * <p>为什么需要它: 本仓库 vitest 是 {@code environment: 'node'} 且没装 jsdom /
 * @vue/test-utils, 无法挂载 SFC 做行为断言。挂不上组件, 但「降级逻辑」和「逻辑有没有
 * 接到 DOM 上」是<b>两件独立的事</b> —— 只测前者会漏掉「重构模板时把 @error 删了」,
 * 那是真实发生过的回归路径(占位块还在, 但永远不会触发)。
 *
 * <p><b>脆性声明</b>: 这里用源码正则做结构断言, 把 {@code <img>} 的写法绑死了。
 * 将来若把海报抽成独立组件、或改用 {@code v-bind} 合并属性, 本文件会假红 ——
 * 届时应当<b>改成测新组件</b>, 而不是把断言放宽到失去意义。
 * 这与 {@code tests/i18n/key-coverage.test.ts} 的静态扫描是同一类取舍,
 * 与后端 {@code ChatToolsStructureTest} 的反射白名单同理。
 *
 * <p>本文件只做接线断言, <b>不</b>替代浏览器验证:
 * 「pre-wrap 真的让换行显示出来」「@error 真的在浏览器里触发」都属于渲染行为,
 * vitest 在 node 环境下测不到, 需 Playwright 才能覆盖。
 */
const SRC_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')

function readSrc(rel: string): string {
  return readFileSync(resolve(SRC_DIR, rel), 'utf8')
}

/**
 * 取出「{@code :src="<posterExpr>"} 的那个 {@code <img>} 整段标签文本。
 * 单行写法和多行换行写法都能匹配, 且只在匹配到的 img 标签内部找属性,
 * 因此不受 {@code @error} 写在 {@code :src} 前还是后影响。
 */
function posterImgTag(src: string, posterExpr: string): string {
  const re = new RegExp(`<img\\b[^>]*:src="${posterExpr.replace(/\./g, '\\.')}"[^>]*>`)
  return src.match(re)?.[0] ?? ''
}

describe('接线契约 · 海报 @error 兜底', () => {
  it('Home.vue 的海报 img 绑定了 @error', () => {
    const tag = posterImgTag(readSrc('views/Home.vue'), 'm.poster')

    expect(tag).not.toBe('')
    expect(tag).toMatch(/@error=/)
  })

  it('MovieDetail.vue 的海报 img 绑定了 @error', () => {
    const tag = posterImgTag(readSrc('views/MovieDetail.vue'), 'movie.poster')

    expect(tag).not.toBe('')
    expect(tag).toMatch(/@error=/)
  })

  it('两处 @error 都走同一个降级 composable, 不各自造轮子', () => {
    const home = readSrc('views/Home.vue')
    const detail = readSrc('views/MovieDetail.vue')

    expect(home).toContain('usePosterFallback')
    expect(detail).toContain('usePosterFallback')
  })
})

describe('接线契约 · 影片描述保留换行', () => {
  it('MovieDetail.vue 的 .desc 规则含 white-space: pre-wrap', () => {
    const src = readSrc('views/MovieDetail.vue')
    // .desc 规则块内无嵌套花括号, [^}]* 可以完整截出该块
    const block = src.match(/\.desc\s*\{[^}]*\}/)?.[0] ?? ''

    expect(block).not.toBe('')
    expect(block).toMatch(/white-space\s*:\s*pre-wrap/)
  })
})