import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { toIdString } from '../../src/types'

/**
 * ID 契约闸门。
 *
 * <p>**为什么需要它**: 后端 `JacksonConfig` 只在 `|value| > 2^53-1` 时把 Long
 * 序列化成字符串, 所以 ID 在 wire 上是**多态**的(种子数据是 number, 雪花是 string)。
 * 一旦有人写下 `Number(movie.id)` 或 `id + 1`, 精度就在那一步丢掉了 ——
 * 而且丢得很安静: URL 里的 ID 只是查不到, 不会报错。
 *
 * <p>所以规则是「ID 永远当不透明值, 不做数值运算」, 这条测试把它锁住。
 */

const SRC = fileURLToPath(new URL('../../src', import.meta.url))

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      walk(p, out)
    } else if (/\.(ts|vue)$/.test(name)) {
      out.push(p)
    }
  }
  return out
}

describe('toIdString — ID 归一', () => {
  it('number → 十进制字符串(不丢精度, 因为它本来就在 2^53 以内)', () => {
    expect(toIdString(1001)).toBe('1001')
    expect(toIdString(0)).toBe('0')
  })

  it('string → 原样返回', () => {
    expect(toIdString('1985000000000000001')).toBe('1985000000000000001')
  })
})

describe('ID 契约 — 静态扫描', () => {
  const files = walk(SRC)

  it('src 下没有把 ID 转成数字的写法', () => {
    const offenders: string[] = []
    // 只扫明显以 id 结尾的表达式: Number(x.id) / parseInt(x.id) / x.id + 1
    const patterns = [
      /Number\([A-Za-z_$][\w$]*\.id\b/g,
      /parseInt\([A-Za-z_$][\w$]*\.id\b/g,
      /[A-Za-z_$][\w$]*\.id\s*[+\-]\s*\d/g,
    ]
    for (const file of files) {
      const src = readFileSync(file, 'utf-8')
      for (const re of patterns) {
        const hits = src.match(re)
        if (hits) offenders.push(`${file.replace(SRC, 'src')} -> ${hits.join(', ')}`)
      }
    }
    expect(offenders, `ID 被当成数字用了:\n${offenders.join('\n')}`).toEqual([])
  })

  it('api 层不再用 number | string 手写 ID 联合类型', () => {
    const apiDir = join(SRC, 'api')
    const offenders: string[] = []
    for (const file of walk(apiDir)) {
      const src = readFileSync(file, 'utf-8')
      if (/:\s*number\s*\|\s*string/.test(src) || /:\s*string\s*\|\s*number/.test(src)) {
        offenders.push(file.replace(SRC, 'src'))
      }
    }
    expect(offenders, `ID 联合类型应统一用 Id:\n${offenders.join('\n')}`).toEqual([])
  })

  it('api 层拼 URL 时统一走 toIdString', () => {
    const apiDir = join(SRC, 'api')
    const offenders: string[] = []
    for (const file of walk(apiDir)) {
      const src = readFileSync(file, 'utf-8')
      // ${xxxId} 直接插值 —— 没有经 toIdString 归一
      const hits = src.match(/\$\{[A-Za-z_$][\w$]*(?:Id|id)\}/g)
      if (hits) offenders.push(`${file.replace(SRC, 'src')} -> ${hits.join(', ')}`)
    }
    expect(offenders, `ID 进 URL 未走 toIdString:\n${offenders.join('\n')}`).toEqual([])
  })
})
