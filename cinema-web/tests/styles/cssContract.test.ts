import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

/**
 * CSS 源码结构契约 —— 两条「静默失效」的坑。
 *
 * <p>两条都不会让任何构建或类型检查报错, 症状都是「效果莫名其妙没了」,
 * 所以只能靠静态断言守住。
 *
 * <p><b>坑一: 嵌套注释</b>。CSS 注释**不嵌套** —— 注释里的第一个「星号 + 斜杠」就结束整段注释。
 * 于是一段「外层注释里又开了个内层注释」的写法会在内层结束, 后面的文本被当成 CSS 解析,
 * 解析器一路跳到下一个右花括号 —— **整条规则被静默吞掉, 控制台一声不吭**。
 * 2026-10-20 就这么把 `.card-reveal` 整条规则吃掉了(它被内层注释截断后,
 * 解析器跳到该块的右花括号就把规则丢了), 表现为「悬停抬升完全不起作用」。
 *
 * <p>(注: 本文件自己的注释里也不能出现那个「星号 + 斜杠」序列 —— 它会提前结束
 * TypeScript 的块注释, 同样把后面的文本变成代码。这不是假设, 写这个文件时真踩了一次。)
 *
 * <p><b>坑二: 动画填充值压过悬停位移</b>。层叠里 **animation 来源排在普通作者声明之前**
 * (CSS Cascading: animation > normal author), 所以一条
 * `animation: fadeInUp ... both` 的规则在动画结束后**仍然持有**关键帧的最终值
 * (`transform: translateY(0)`), 悬停规则里的 `transform: translateY(-2px)` 永远轮不到。
 * 拖尾进场用 `backwards` 就够: 延迟期间保持起始态, 结束后把 transform 还给元素。
 */

/** cinema-web/src */
const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')

/** 列出 src 下的 .vue / .ts / .css */
function listSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) listSourceFiles(full, acc)
    else if (/\.(vue|ts|css)$/.test(entry.name)) acc.push(full)
  }
  return acc
}

/** 取 .vue 的 <style> 块内容(取不到就是空串) */
function styleBlocksOf(vueSrc: string): string[] {
  const out: string[] = []
  for (const m of vueSrc.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)) {
    out.push(m[1])
  }
  return out
}

/**
 * 找出「嵌套注释」—— 注释还没结束就又出现了一个 `/*`。
 * @returns 形如 `文件:行号` 的清单
 */
function findNestedComments(text: string, label: string): string[] {
  const offenders: string[] = []
  let inComment = false
  let line = 1
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (c === '\n') line++
    if (c === '/' && text[i + 1] === '*') {
      if (inComment) offenders.push(`${label}:${line}`)
      inComment = true
      i++
      continue
    }
    if (c === '*' && text[i + 1] === '/') {
      inComment = false
      i++
    }
  }
  return offenders
}

/** 粗粒度地把 CSS 拆成 (选择器, 声明体) —— 够用即可, 不追求 CSS 完整解析 */
function cssRules(css: string): { selector: string; body: string }[] {
  const out: { selector: string; body: string }[] = []
  // 去掉注释, 免得注释里的花括号把规则切碎
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '')
  for (const m of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    out.push({ selector: m[1].trim(), body: m[2] })
  }
  return out
}

/** 选择器里出现的类名 */
function classesIn(selector: string): string[] {
  return [...selector.matchAll(/\.([A-Za-z_][\w-]*)/g)].map((m) => m[1])
}

describe('CSS 源码契约 · 嵌套注释', () => {
  it('main.css 与各 .vue 的 style 块里都没有嵌套注释', () => {
    const offenders: string[] = []
    for (const file of listSourceFiles(SRC)) {
      const rel = file.slice(SRC.length + 1).replace(/\\/g, '/')
      const text = readFileSync(file, 'utf8')
      if (rel.endsWith('.css')) {
        offenders.push(...findNestedComments(text, rel))
        continue
      }
      if (!rel.endsWith('.vue')) continue
      styleBlocksOf(text).forEach((css, i) => {
        offenders.push(...findNestedComments(css, `${rel}#style${i + 1}`))
      })
    }
    expect(
      offenders,
      `这些位置在注释里又开了个 /* —— CSS 注释不嵌套, 内层 */ 会提前结束外层注释,\n`
        + `后面的文本被当成 CSS 解析, 整条规则会被静默吞掉:\n${offenders.join('\n')}`,
    ).toEqual([])
  })
})

describe('CSS 源码契约 · 悬停位移不被动画填充值吃掉', () => {
  it('没有元素同时「悬停要位移」又「用 both/forwards 填充实态」', () => {
    const offenders: string[] = []
    for (const file of listSourceFiles(SRC)) {
      const rel = file.slice(SRC.length + 1).replace(/\\/g, '/')
      const text = readFileSync(file, 'utf8')
      const blocks = rel.endsWith('.css') ? [text] : rel.endsWith('.vue') ? styleBlocksOf(text) : []
      for (const css of blocks) {
        const rules = cssRules(css)
        // 悬停里要位移的类
        const hoverMoving = new Set<string>()
        for (const r of rules) {
          if (!r.selector.includes(':hover')) continue
          if (!/transform\s*:/.test(r.body)) continue
          for (const c of classesIn(r.selector)) hoverMoving.add(c)
        }
        if (hoverMoving.size === 0) continue
        // 用 both/forwards 填充实态的类(动画结束后 transform 仍被动画来源占着)
        const filled = new Set<string>()
        for (const r of rules) {
          const m = r.body.match(/animation\s*:[^;]*\b(both|forwards)\b/)
          if (!m) continue
          for (const c of classesIn(r.selector)) filled.add(c)
        }
        for (const c of [...hoverMoving].filter((x) => filled.has(x)).sort()) {
          offenders.push(`${rel}  .${c}`)
        }
      }
    }
    expect(
      offenders,
      `这些元素的悬停位移会被入场动画的填充值吃掉 —— 层叠里 animation 来源排在普通声明之前,\n`
        + `「animation: ... both」在动画结束后仍持有 transform, 悬停的位移永远轮不到。\n`
        + `拖尾进场请改用 backwards(延迟期间保持起始态, 结束后把 transform 还给元素):\n`
        + offenders.join('\n'),
    ).toEqual([])
  })
})
