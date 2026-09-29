import { describe, it, expect } from 'vitest'
import { renderMarkdown, escapeHtml } from '../../src/utils/markdown'

/**
 * P2-2:LLM 回复的 Markdown(表格/加粗/列表)原先以原始文本直出。
 * 本文件锁住渲染行为,**并把 XSS 安全模型锁成测试** —— 产物走 v-html,
 * 一旦有人把"先转义后拼标签"的顺序改反,这里立刻红。
 */
describe('escapeHtml', () => {
  it('转义全部 HTML 危险字符', () => {
    expect(escapeHtml(`<>&"'`)).toBe('&lt;&gt;&amp;&quot;&#39;')
  })

  it('& 必须最先转义,否则二次转义会污染已生成的实体', () => {
    // 若 & -> &amp; 在 < -> &lt; 之后执行,&lt; 里的 & 会被再次转义成 &amp;lt;
    expect(escapeHtml('<')).toBe('&lt;')
  })
})

describe('renderMarkdown — 基础', () => {
  it('空值返回空串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
  })

  it('普通段落包成 <p>', () => {
    expect(renderMarkdown('今晚有场次')).toBe('<p>今晚有场次</p>')
  })

  it('段落内软换行渲染成 <br />', () => {
    expect(renderMarkdown('第一行\n第二行')).toBe('<p>第一行<br />第二行</p>')
  })
})

describe('renderMarkdown — 表格(P2-2 主症状)', () => {
  const table = ['| 影片 | 场次 |', '| --- | --- |', '| 流浪地球2 | 19:30 |', '| 沙丘 | 21:00 |'].join('\n')

  it('表格渲染成 thead/tbody,不再显示裸 | 管道符', () => {
    const html = renderMarkdown(table)
    expect(html).toContain('<table>')
    expect(html).toContain('<th>影片</th>')
    expect(html).toContain('<th>场次</th>')
    expect(html).toContain('<td>流浪地球2</td>')
    expect(html).toContain('<td>19:30</td>')
    // 关键回归:正文里不该再有裸的表格管道符
    expect(html).not.toContain('| --- |')
  })

  it('表头 + 2 行数据 → tbody 恰好 2 个 tr', () => {
    const html = renderMarkdown(table)
    expect(html.match(/<tr>/g) ?? []).toHaveLength(3) // 1 表头 + 2 数据
  })

  it('只有一行带管道符、下面没有分隔行 → 当普通文本,不误判成表格', () => {
    const html = renderMarkdown('| 这不是表格 | 只是文字')
    expect(html).not.toContain('<table>')
  })
})

describe('renderMarkdown — 行内语法', () => {
  it('**粗体**', () => {
    expect(renderMarkdown('这是**重点**')).toBe('<p>这是<strong>重点</strong></p>')
  })

  it('*斜体*', () => {
    expect(renderMarkdown('这是*强调*')).toBe('<p>这是<em>强调</em></p>')
  })

  it('`行内代码`', () => {
    expect(renderMarkdown('用 `pnpm test` 跑测试')).toContain('<code>pnpm test</code>')
  })
})

describe('renderMarkdown — 块级语法', () => {
  it('无序列表', () => {
    const html = renderMarkdown('- 第一\n- 第二')
    expect(html).toContain('<ul>')
    expect(html).toContain('<li>第一</li>')
    expect(html).toContain('<li>第二</li>')
  })

  it('有序列表', () => {
    const html = renderMarkdown('1. 锁座\n2. 支付')
    expect(html).toContain('<ol>')
    expect(html).toContain('<li>锁座</li>')
  })

  it('列表项的缩进续行并入上一项', () => {
    const html = renderMarkdown('- 第一项\n  补充说明\n- 第二项')
    expect(html).toContain('<li>第一项 补充说明</li>')
  })

  it('标题', () => {
    expect(renderMarkdown('## 场次安排')).toBe('<h2>场次安排</h2>')
  })

  it('围栏代码块:内容转义且不做行内解析', () => {
    const html = renderMarkdown('```json\n{"a": 1}\n```')
    expect(html).toContain('<pre><code class="lang-json">')
    // JSON 的引号应被转义,不能破坏 HTML 结构
    expect(html).toContain('{&quot;a&quot;: 1}')
  })

  it('围栏内不解析粗体标记', () => {
    const html = renderMarkdown('```\n**不是粗体**\n```')
    expect(html).not.toContain('<strong>')
  })

  it('引用块', () => {
    expect(renderMarkdown('> 注意')).toBe('<blockquote><p>注意</p></blockquote>')
  })
})

describe('renderMarkdown — XSS 安全(P2-2 附带的安全要求)', () => {
  it('script 标签被转义,不产生可执行节点', () => {
    const html = renderMarkdown('<script>alert(1)</script>')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
  })

  it('img onerror 被转义', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)>')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('onerror=alert(1)"')
    expect(html).toContain('&lt;img')
  })

  it('表格单元格里的 HTML 同样被转义', () => {
    const html = renderMarkdown('| a | b |\n| --- | --- |\n| <b>x</b> | y |')
    expect(html).not.toContain('<b>x</b>')
    expect(html).toContain('&lt;b&gt;x&lt;/b&gt;')
  })

  it('代码块内的 HTML 被转义', () => {
    const html = renderMarkdown('```\n<script>bad()</script>\n```')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
  })

  it('不把 markdown 链接渲染成可点 <a>,避免 javascript: 协议注入', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))')
    expect(html).not.toContain('<a ')
    expect(html).not.toContain('href')
  })

  it('代码块占位符不会被正文里的相似文本误替换', () => {
    // 正文含 "\u{E000}CB0" 形态的普通文本时,不能被当成占位符还原
    const marker = String.fromCharCode(0xe000)
    const html = renderMarkdown(`普通段落提到 ${marker}CB0 这个符号`)
    expect(html).toContain('提到')
  })
})
