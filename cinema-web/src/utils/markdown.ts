/**
 * renderMarkdown — 极简 Markdown 渲染器(不引第三方库).
 *
 * <p>**为什么自己写**(E2E 2026-09-29 P2-2):LLM 回复里的表格/加粗原本以原始
 * `|影片|场次|` 文本直出,观感很差。项目约束不允许随意改 `package.json` 增依赖,
 * 而聊天内容体量很小(几行到几十行),用不到完整 Markdown 引擎。
 *
 * <p>**XSS 安全模型**(这是本文件存在的头号理由 —— 产物走 `v-html`):
 * <ol>
 *   <li><b>先转义,后生成标签</b>:源文本先经 {@link escapeHtml} 把 `& < > " '`
 *       全部转成实体,之后才拼我们自己的 HTML 标签。这样 LLM 即便返回
 *       `<img src=x onerror=alert(1)>` 或 `<script>`,落到 DOM 里也只是可见文本。</li>
 *   <li>不做链接自动补全:<code>[text](javascript:alert(1))</code> 不会被渲染成可点链接,
 *       从根上避免 <code>javascript:</code> 协议注入。</li>
 *   <li>代码块内容同样先转义,不做任何行内语法解析。</li>
 * </ol>
 *
 * <p>支持:围栏代码块 · 表格(对齐行) · 标题 1~6 · 有序/无序列表(含续行) ·
 * 引用 · 分隔线 · 段落 · 行内 `code` / **粗体** / *斜体*。
 */

const ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/** 转义所有 HTML 危险字符。渲染流程的第一步,必须最先执行。 */
export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ESCAPE_MAP[c] ?? c)
}

/** 代码块占位符的定界符:CB_MARK = U+E000(Unicode 私用区),正文几乎不可能出现 */
const CB_MARK = String.fromCharCode(0xe000)

/** 占位符本体:用 Unicode 私用区字符,正文几乎不可能出现 */
const CB_OPEN = CB_MARK + 'CB'
const CB_CLOSE = CB_MARK

const RE_FENCE = /^\s*```(\w*)\s*$/
const RE_FENCE_CLOSE = /^\s*```\s*$/
const RE_HEADING = /^(#{1,6})\s+(.*)$/
const RE_UL = /^\s*[-*+]\s+(.*)$/
const RE_OL = /^\s*\d+[.)]\s+(.*)$/
const RE_QUOTE = /^\s*>\s?(.*)$/
const RE_HR = /^\s*([-*_])\s*(?:\1\s*){2,}$/
const RE_TABLE_SEP = /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/

/** 行内语法(入参必须已转义)。链接语法刻意不支持,见文件头 XSS 说明。 */
function renderInline(escaped: string): string {
  return escaped
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
}

function isTableRow(line: string): boolean {
  return line.includes('|') && line.trim().startsWith('|')
}

function splitRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim())
}

function renderTable(header: string[], rows: string[][]): string {
  const th = header.map((c) => `<th>${renderInline(escapeHtml(c))}</th>`).join('')
  const body = rows
    .map((r) => `<tr>${r.map((c) => `<td>${renderInline(escapeHtml(c))}</td>`).join('')}</tr>`)
    .join('')
  return `<table><thead><tr>${th}</tr></thead><tbody>${body}</tbody></table>`
}

/** 段落聚合时遇到这些块级起始就断开,避免把表格/列表吞进段落 */
function isBlockStart(line: string): boolean {
  return (
    RE_HEADING.test(line) ||
    RE_FENCE.test(line) ||
    RE_UL.test(line) ||
    RE_OL.test(line) ||
    RE_QUOTE.test(line) ||
    RE_HR.test(line) ||
    isTableRow(line)
  )
}

/**
 * 把 LLM 回复的 Markdown 渲染为安全 HTML 字符串,可直接喂给 `v-html`。
 *
 * @param src 原始 Markdown 文本(null/undefined 视为空串)
 */
export function renderMarkdown(src: string | null | undefined): string {
  if (!src) return ''

  const lines = src.replace(/\r\n/g, '\n').split('\n')
  const out: string[] = []
  const codeBlocks: string[] = []
  const open = new RegExp(CB_OPEN + '(\\d+)' + CB_CLOSE, 'g')
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    // ---- 围栏代码块:内容原样转义,不做行内解析 ----
    const fence = RE_FENCE.exec(line)
    if (fence) {
      const lang = fence[1] ?? ''
      const buf: string[] = []
      i++
      while (i < lines.length && !RE_FENCE_CLOSE.test(lines[i])) {
        buf.push(lines[i])
        i++
      }
      i++ // 跳过收尾围栏(即便缺失也不卡死)
      const cls = lang ? ` class="lang-${escapeHtml(lang)}"` : ''
      codeBlocks.push(`<pre><code${cls}>${escapeHtml(buf.join('\n'))}</code></pre>`)
      out.push(`${CB_OPEN}${codeBlocks.length - 1}${CB_CLOSE}`)
      continue
    }

    // ---- 表格:表头行 + 对齐分隔行 ----
    if (
      isTableRow(line) &&
      i + 1 < lines.length &&
      lines[i + 1].includes('-') &&
      RE_TABLE_SEP.test(lines[i + 1])
    ) {
      const header = splitRow(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && isTableRow(lines[i])) {
        rows.push(splitRow(lines[i]))
        i++
      }
      out.push(renderTable(header, rows))
      continue
    }

    // ---- 标题 ----
    const heading = RE_HEADING.exec(line)
    if (heading) {
      const level = heading[1].length
      out.push(`<h${level}>${renderInline(escapeHtml(heading[2].trim()))}</h${level}>`)
      i++
      continue
    }

    // ---- 引用(可嵌套,递归渲染) ----
    const quote = RE_QUOTE.exec(line)
    if (quote) {
      const buf: string[] = []
      while (i < lines.length) {
        const m = RE_QUOTE.exec(lines[i])
        if (!m) break
        buf.push(m[1])
        i++
      }
      out.push(`<blockquote>${renderMarkdown(buf.join('\n'))}</blockquote>`)
      continue
    }

    // ---- 分隔线 ----
    if (RE_HR.test(line)) {
      out.push('<hr />')
      i++
      continue
    }

    // ---- 列表(含缩进续行) ----
    const ordered = !RE_UL.test(line) && RE_OL.test(line)
    if (RE_UL.test(line) || ordered) {
      const re = ordered ? RE_OL : RE_UL
      const items: string[] = []
      while (i < lines.length) {
        const m = re.exec(lines[i])
        if (m) {
          items.push(m[1])
          i++
        } else if (items.length && /^\s{2,}\S/.test(lines[i])) {
          items[items.length - 1] += ' ' + lines[i].trim()
          i++
        } else {
          break
        }
      }
      const tag = ordered ? 'ol' : 'ul'
      const lis = items.map((it) => `<li>${renderInline(escapeHtml(it.trim()))}</li>`).join('')
      out.push(`<${tag}>${lis}</${tag}>`)
      continue
    }

    // ---- 空行 ----
    if (!line.trim()) {
      i++
      continue
    }

    // ---- 段落(连续非块级行合并,软换行渲染成 <br />) ----
    const buf: string[] = []
    while (i < lines.length && lines[i].trim() && !isBlockStart(lines[i])) {
      buf.push(lines[i])
      i++
    }
    if (buf.length) {
      out.push(`<p>${renderInline(escapeHtml(buf.join('\n'))).replace(/\n/g, '<br />')}</p>`)
    } else {
      i++
    }
  }

  return out.join('\n').replace(open, (_, n: string) => codeBlocks[Number(n)] ?? '')
}
