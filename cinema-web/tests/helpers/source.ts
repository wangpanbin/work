import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 源码文本读取的公共 helper(2026-10-25 交互审查后从 6 个测试文件里抽出来)。
 *
 * <p>**为什么要抽**: 静态契约断言要「先剥注释再断言」—— 本仓源码注释里大量
 * 写着「原先是怎么写的(为什么这是个坑)」, 拿原文去断言会永远命中, 断言就成了
 * 恒真假绿。而这段剥注释的实现在 6 个文件里逐字复制, 改一处漏五处 = 闸门失灵。
 *
 * <p>⚠️ **相对路径的深度约定**: 本文件位于 `tests/helpers/`, 与 `tests/views/`
 * 同深 —— 所以调用方写 `'../../src/views/Xxx.vue'` 时, 无论从本文件还是从
 * 测试文件解析, 指向的都是 `cinema-web/src/views/Xxx.vue`。**不要**把本文件
 * 挪到别的深度, 那会让所有调用方的相对路径一起失效。
 */
export const read = (rel: string) =>
  readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf-8')

/**
 * 去掉注释(块注释 / HTML 注释 / 行注释)后的源码文本。
 *
 * <p>行注释用 `[^:]` 前瞻避开 `http://` / `ws://` 这类协议里的双斜杠。
 */
export const code = (rel: string) =>
  read(rel)
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1')

/**
 * 取出一个**顶层声明**的函数体 —— 从声明处到下一个顶层声明为止。
 *
 * <p>⚠️ **为什么不能用「固定 N 个字符」的窗口**: 窗口会串到隔壁函数去 ——
 * 比如 `fn(src, 'function goSeat')` 取 800 字符, 后面的 `goOrders()` 也被吃进来,
 * 而 `goOrders()` 里恰好也有一行 `clearRedirectTimer()` ——
 * 于是「删掉 goSeat 里那行」测试照样绿, 断言根本没锁住它声称的东西。
 *
 * <p>边界取「行首的声明」(函数体内的声明都是缩进的, 不会误伤)。
 *
 * @param src 源码文本(通常是 `code()` 的结果)
 * @param signature 声明的开头, 如 `'function goSeat'` / `'async function load()'`
 */
export function fnBody(src: string, signature: string): string {
  const at = src.indexOf(signature)
  if (at < 0) return ''
  const rest = src.slice(at)
  // 下一个「行首的顶层声明」就是边界。
  // ⚠️ 必须认 `export` —— `api/admin.ts` 里顶层全是 `export function/const`,
  //    边界正则漏了它就会一路取到文件末尾, 后面的断言等于在查同一段文本(假绿)。
  const next = rest.search(
    /\n(?=(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function|const|let|var|interface|type|class|enum)\s)/,
  )
  return next < 0 ? rest : rest.slice(0, next)
}
