import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import zhCN from '../../../src/i18n/locales/zh-CN'
import enUS from '../../../src/i18n/locales/en-US'
import {
  SESSION_STATUS,
  SESSION_STATUS_CODES,
  SESSION_STATUS_LABEL_KEY,
  SESSION_STATUS_UNKNOWN_KEY,
  resolveSessionStatusLabelKey,
  resolveSessionStatusTagType,
} from '../../../src/views/session/constants'

/**
 * 场次状态文案契约 —— 2026-10-12「场次管理状态列整列显示未知」的回归锁。
 *
 * <p><b>缺陷</b>: 映射逻辑原先内联在 `SessionManage.vue`, 数组把下标 1(在售)误写成
 * 空串 `''`, 而模板用「取到真值才翻译, 否则回落 sessionStatusUnknown」的判定 ——
 * 空串是 falsy, 于是演示数据里 <b>status=1 的全部行</b> 都回落成「未知」, 只有一条
 * status=2 的行正常显示「已开场」。
 *
 * <p><b>本文件测的是行为而非写法</b>: 映射已下沉到 `views/session/constants.ts` 纯函数,
 * 因此这里直接调用 resolver 断言输出, 而不是正则扫源码。上一版(修复前)只能静态扫
 * —— 因为那时逻辑还埋在 SFC 里, 而本仓库 vitest 是 `environment: 'node'`、没装
 * jsdom / @vue/test-utils, 挂不上组件(见 AGENTS.md)。
 *
 * <p><b>为什么还要保留 SessionManage.vue 的接线断言</b>: 「映射对不对」和
 * 「模板有没有真的用它」是两件独立的事。只测前者, 将来重构模板时改回内联数组就会漏。
 * 这与 `tests/views/posterWiring.test.ts` 是同一类取舍。
 *
 * <p><b>脆性声明</b>: 接线断言绑死了 import 路径与函数名。若改名/搬家, 本文件会假红 ——
 * 届时应改断言指向新位置, 而不是把断言放宽到失去意义。
 */

const SRC_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../../../src')

function readSrc(rel: string): string {
  return readFileSync(resolve(SRC_DIR, rel), 'utf8')
}

/**
 * 后端 `Session.status` / `SessionDTO.status` 的语义, 见 cinema-server
 * `modules/session/entity/Session.java` 与 `dto/SessionDTO.java` 的注释:
 * 0待开售 1在售 2已开场 3已结束。这是跨端 wire-format, 前端不得自造。
 */
const BACKEND_STATUS_CODES = [0, 1, 2, 3] as const

const EXPECTED_LABEL_KEYS = {
  0: 'admin.sessionStatusPending',
  1: 'admin.sessionStatusSelling',
  2: 'admin.sessionStatusStarted',
  3: 'admin.sessionStatusEnded',
} as const

/**
 * 按 `a.b.c` 逐层取字典叶子值。
 *
 * <p>不能直接 `dict.admin as Record<string, string>` —— `admin` 下还有 `homeSidebar`
 * 这种嵌套对象, 强转会被 vue-tsc 判成 TS2352(这也是本文件最初 type-check 报红的原因)。
 * 这里按路径走, 顺带保证取值是 string 而不是对象。
 */
function dictLeaf(dict: unknown, fullKey: string): string | undefined {
  let cur: unknown = dict
  for (const seg of fullKey.split('.')) {
    if (typeof cur !== 'object' || cur === null) return undefined
    cur = (cur as Record<string, unknown>)[seg]
  }
  return typeof cur === 'string' ? cur : undefined
}

describe('场次状态映射 — 覆盖后端 4 态', () => {
  it('SESSION_STATUS 与后端语义对齐', () => {
    expect(SESSION_STATUS).toEqual({ PENDING: 0, SELLING: 1, STARTED: 2, ENDED: 3 })
    expect([...SESSION_STATUS_CODES]).toEqual([0, 1, 2, 3])
  })

  it('每个合法 code 在映射表里都有非空 i18n key(空串正是本次缺陷)', () => {
    for (const code of BACKEND_STATUS_CODES) {
      const key = SESSION_STATUS_LABEL_KEY[code]

      expect(typeof key, `status=${code} 映射缺失或非字符串`).toBe('string')
      expect(key, `status=${code} 映射为空串 —— 页面会显示「未知」`).not.toBe('')
      expect(key.trim(), `status=${code} 映射是纯空白`).not.toBe('')
      expect(key, `status=${code} 未命中 admin.sessionStatus* 命名空间`).toMatch(
        /^admin\.sessionStatus/,
      )
    }
  })

  it.each(BACKEND_STATUS_CODES)('status=%i 解析出预期文案 key', (code) => {
    expect(resolveSessionStatusLabelKey(code)).toBe(EXPECTED_LABEL_KEYS[code])
  })

  it('status=1 是「在售」—— 演示数据(sql/05_refresh_sessions_for_demo.sql)全是 1', () => {
    expect(resolveSessionStatusLabelKey(SESSION_STATUS.SELLING)).toBe('admin.sessionStatusSelling')
    expect(resolveSessionStatusLabelKey(1)).not.toBe(SESSION_STATUS_UNKNOWN_KEY)
  })

  it('映射表里没有任何空串值(直接扫表, 防止新增状态码时留占位符)', () => {
    const empty = Object.entries(SESSION_STATUS_LABEL_KEY).filter(([, v]) => !v || !v.trim())

    expect(empty).toEqual([])
  })
})

describe('场次状态解析器 — 容错', () => {
  it('未收录/异常输入一律回落 unknown, 且不返回 undefined', () => {
    const bad: unknown[] = [null, undefined, NaN, 4, 99, -1, {}, [], true, '']

    for (const v of bad) {
      expect(resolveSessionStatusLabelKey(v), `输入 ${String(v)} 应回落 unknown`).toBe(
        SESSION_STATUS_UNKNOWN_KEY,
      )
    }
  })

  it('字符串数字等价于数字(后端若改配置序列化成 string 也不会变未知)', () => {
    for (const code of BACKEND_STATUS_CODES) {
      expect(resolveSessionStatusLabelKey(String(code))).toBe(EXPECTED_LABEL_KEYS[code])
    }
  })

  it('tagType 保持修复前视觉: 仅「在售」success, 其余 info', () => {
    expect(resolveSessionStatusTagType(1)).toBe('success')
    for (const v of [0, 2, 3, null, undefined, 'nope']) {
      expect(resolveSessionStatusTagType(v)).toBe('info')
    }
  })
})

describe('场次状态文案 — 两份字典都有值', () => {
  const allKeys = [...Object.values(SESSION_STATUS_LABEL_KEY), SESSION_STATUS_UNKNOWN_KEY]

  it('每个 key 在 zh-CN / en-US 里都存在且非空', () => {
    for (const fullKey of allKeys) {
      expect(dictLeaf(zhCN, fullKey), `zh-CN 缺 ${fullKey}`).toBeTruthy()
      expect(dictLeaf(enUS, fullKey), `en-US 缺 ${fullKey}`).toBeTruthy()
    }
  })

  it('status=1 在两种语言下分别译为「在售」/「On sale」', () => {
    const key = SESSION_STATUS_LABEL_KEY[1]

    expect(dictLeaf(zhCN, key)).toBe('在售')
    expect(dictLeaf(enUS, key)).toBe('On sale')
  })
})

describe('接线契约 · SessionManage.vue 用了抽取后的模块', () => {
  const src = readSrc('views/admin/SessionManage.vue')

  it('从 ../session/constants 导入解析器, 不再自带映射表', () => {
    expect(src).toContain("from '../session/constants'")
    expect(src).toContain('resolveSessionStatusLabelKey')
    expect(src).toContain('resolveSessionStatusTagType')
  })

  it('局部 sessionStatusLabelKey 数组已被删除(它就是空串缺陷的载体)', () => {
    expect(src).not.toMatch(/const\s+sessionStatusLabelKey\s*=/)
  })

  it('状态列模板走 resolver, 不再对原始数组做真值判定', () => {
    expect(src).toMatch(/t\(resolveSessionStatusLabelKey\(row\.status\)\)/)
    expect(src).not.toMatch(/t\('admin\.sessionStatusUnknown'\)/)
  })
})

describe('接线契约 · 与后端枚举一致', () => {
  it('后端 Session/SessionDTO 声明的 4 个状态码都在映射里', () => {
    const server = resolve(SRC_DIR, '../../cinema-server/src/main/java/com/cinema/modules/session')
    const entity = readFileSync(resolve(server, 'entity/Session.java'), 'utf8')
    const dto = readFileSync(resolve(server, 'dto/SessionDTO.java'), 'utf8')

    for (const s of [entity, dto]) {
      expect(
        s.match(/0待开售\s*1在售\s*2已开场\s*3已结束/),
        '后端状态语义注释被改动, 前端映射需同步复核',
      ).not.toBeNull()
    }

    for (const code of BACKEND_STATUS_CODES) {
      expect(SESSION_STATUS_LABEL_KEY[code], `后端 status=${code} 没有对应文案`).toBeTruthy()
    }
  })
})
