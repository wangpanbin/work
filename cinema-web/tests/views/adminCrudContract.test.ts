import { describe, it, expect } from 'vitest'
import { code, read } from '../helpers/source'

/**
 * 管理端三张 CRUD 表单的**交互契约**(2026-10-25)。
 *
 * <p>本仓 vitest 跑在 environment: 'node', 没装 jsdom / @vue/test-utils,
 * 挂不了 SFC —— 涉及视图层的判断只有两条路:
 *   ① 把判断逻辑下沉成纯函数测(影厅往返见 `tests/utils/hallForm.test.ts`);
 *   ② 对 .vue 源码做静态契约断言(本文件)。
 *
 * <p>本文件锁的是「界面看起来正常, 功能却是死的」那一类:
 * 保存按钮没有并发闸门(连点两下建出两条)· 加载失败显示成「暂无数据」·
 * 校验文案是 async-validator 的英文默认模板 · 后端错误只闪一条 toast ·
 * 下拉选项挂了还能提交 `movieId = 0` 的脏数据。
 *
 * <p>脆性写在文件头: 断言针对源码文本, 重构时需同步更新。
 */

/** 三张 CRUD 表单的公共契约(逐页断言)。 */
const PAGES = [
  {
    file: 'MovieManage.vue',
    loadFailedKey: 'admin.moviesLoadFailed',
    addKey: 'admin.moviesAdd',
    /** 保存按钮 disabled 的表达式 */
    saveDisabled: 'submitting',
    requiredKeys: ['admin.movieTitleRequired', 'admin.movieDurationRequired'],
  },
  {
    file: 'HallManage.vue',
    loadFailedKey: 'admin.hallsLoadFailed',
    addKey: 'admin.hallsAdd',
    saveDisabled: 'submitting',
    requiredKeys: ['admin.hallNameRequired', 'admin.hallRowsRequired', 'admin.hallColsRequired'],
  },
  {
    file: 'SessionManage.vue',
    loadFailedKey: 'admin.sessionsLoadFailed',
    addKey: 'admin.sessionsAdd',
    /** 场次页多一条: 下拉选项没加载出来时也不许提交 */
    saveDisabled: 'submitting || optionsError',
    requiredKeys: [
      'admin.sessionMovieRequired',
      'admin.sessionHallRequired',
      'admin.sessionStartTimeRequired',
      'admin.sessionPriceRequired',
    ],
  },
] as const

const src = (file: string) => code(`../../src/views/admin/${file}`)

/** 取 `<template #empty>` 插槽的内容。 */
const emptySlot = (source: string) => source.match(/<template #empty>([\s\S]*?)<\/template>/)?.[1] ?? ''

describe('管理端 CRUD · 保存不能重复提交', () => {
  it.each(PAGES)('$file: 保存按钮在提交期间 loading + disabled', ({ file, saveDisabled }) => {
    const s = src(file)
    expect(s, `${file} 没有 submitting 状态`).toMatch(/const submitting = ref\(false\)/)
    expect(s, `${file} 保存按钮没有 :loading="submitting"`).toContain(':loading="submitting"')
    expect(s, `${file} 保存按钮没有 :disabled="${saveDisabled}"`).toContain(`:disabled="${saveDisabled}"`)
  })

  it.each(PAGES)('$file: onSubmit 开头就挡住第二次提交', ({ file }) => {
    const s = src(file)
    const idx = s.indexOf('async function onSubmit')
    expect(idx, `${file} 找不到 onSubmit`).toBeGreaterThan(-1)
    const body = s.slice(idx, idx + 400)
    expect(body, `${file} 的 onSubmit 没有并发闸门`).toMatch(/if \(submitting\.value\) return/)
  })

  it.each(PAGES)('$file: 删除请求未回前删除按钮不可再点', ({ file }) => {
    const s = src(file)
    expect(s, `${file} 没有删除在途状态`).toMatch(/const deletingId = ref/)
    expect(s, `${file} 删除按钮没有 disabled`).toContain(':disabled="deletingId !== null"')
  })

  it('SessionManage: 恢复位图按钮在请求未回前不可再点', () => {
    const s = src('SessionManage.vue')
    expect(s).toMatch(/const recoveringId = ref/)
    expect(s).toContain(':disabled="recoveringId !== null"')
  })
})

describe('管理端 CRUD · 影厅编辑不丢 VIP 区', () => {
  it('HallManage: 用 hallForm 的纯函数往返, 不再手写 Object.assign 抄字段', () => {
    const s = src('HallManage.vue')
    expect(s).toContain('hallToForm')
    expect(s).toContain('formToPayload')
    expect(s).toContain("from '../../utils/hallForm'")
  })

  it('HallManage: 不再把 vipFromRow 置成 undefined(那会清空整厅 VIP)', () => {
    const s = src('HallManage.vue')
    expect(s).not.toContain('vipFromRow: undefined')
  })

  it('Hall 类型带上了 vipFromRow —— 前端没有它就回填不了', () => {
    const t = read('../../src/types/admin-types.ts')
    const hall = t.match(/export interface Hall \{([\s\S]*?)\n\}/)?.[1] ?? ''
    expect(hall, 'Hall 类型里没有 vipFromRow').toContain('vipFromRow')
  })
})

describe('管理端 CRUD · 加载失败不再是「暂无数据」', () => {
  it.each(PAGES)('$file: 有独立的错误态与重试入口', ({ file, loadFailedKey }) => {
    const s = src(file)
    expect(s, `${file} 没有 loadError 状态`).toMatch(/const loadError = ref/)
    expect(s, `${file} 没有用 ${loadFailedKey}`).toContain(loadFailedKey)
    expect(s, `${file} 错误态没有重试按钮`).toContain("t('common.retry')")
    expect(s, `${file} 错误态没有 role="alert"`).toContain('role="alert"')
  })

  it.each(PAGES)('$file: 错误态与空状态互斥', ({ file }) => {
    const s = src(file)
    const slot = emptySlot(s)
    expect(slot, `${file} 没有 #empty 插槽`).not.toBe('')
    expect(slot, `${file} 的空状态没有在错误态下让位`).toContain('v-if="!loadError"')
  })
})

describe('管理端 CRUD · 影片列表能翻页', () => {
  it('MovieManage: 接了分页, 不再硬编码前 20 条', () => {
    const s = src('MovieManage.vue')
    expect(s, 'MovieManage 没有分页组件').toContain('<el-pagination')
    expect(s).toMatch(/const total = ref\(0\)/)
    expect(s).toMatch(/const pageNum = ref\(1\)/)
    expect(s).toMatch(/const pageSize = ref\(20\)/)
    expect(s).toContain('@current-change="onPageChange"')
    expect(s).toContain('@size-change="onSizeChange"')
    expect(s, 'MovieManage 仍然硬编码 page: 1, size: 20').not.toMatch(/page:\s*1,\s*size:\s*20/)
  })

  it('MovieManage: 分页把 total 接上了(否则翻页器永远只有一页)', () => {
    const s = src('MovieManage.vue')
    expect(s).toMatch(/total\.value = Number\(data\.total/)
  })
})

describe('管理端 CRUD · 必填提示是本地化文案', () => {
  it.each(PAGES)('$file: 表单接了 rules, 校验失败会拦住提交', ({ file }) => {
    const s = src(file)
    expect(s, `${file} 没有 :rules`).toContain(':rules="rules"')
    expect(s, `${file} 没有 formRef`).toContain('formRef')
    // 校验走公共 helper(2026-10-25 评审后从三个页面抽出来):
    // 视图调 validateForm(formRef.value), helper 内部才调 validate()。
    // 断言要锁**整条链**, 而不是某一行 —— 否则抽 helper 就会把断言打空。
    const idx = s.indexOf('validateForm(formRef.value)')
    expect(idx, `${file} 没有调用 validateForm(formRef.value)`).toBeGreaterThan(-1)
    expect(
      s,
      `${file} 的校验结果没有拦住提交`,
    ).toMatch(/if\s*\(!\(await validateForm\(formRef\.value\)\)\)\s*return/)
    // helper 侧: validate() 必须被 try/catch 包住, 否则校验失败会产生未处理的 rejection
    const helper = code('../../src/utils/adminForm.ts')
    const vIdx = helper.indexOf('.validate()')
    expect(vIdx, 'adminForm.ts 没有调用 validate()').toBeGreaterThan(-1)
    expect(
      helper.slice(vIdx, vIdx + 300),
      'adminForm.ts 的 validate() 没有被 try/catch 包住 —— 校验失败会产生未处理的 rejection',
    ).toMatch(/catch/)
  })

  it.each(PAGES)('$file: 每个必填项都有 i18n 文案, 不吃英文默认模板', ({ file, requiredKeys }) => {
    const s = src(file)
    for (const key of requiredKeys) {
      expect(s, `${file} 没用 ${key}`).toContain(key)
    }
    expect(s, `${file} 还在用 el-form-item 的 required 属性合成英文提示`).not.toMatch(
      /<el-form-item[^>]*\brequired\b/,
    )
  })
})

describe('管理端 CRUD · 后端错误留在弹窗里', () => {
  it.each(PAGES)('$file: 提交失败把后端 msg 写进弹窗顶部的 alert', ({ file }) => {
    const s = src(file)
    expect(s, `${file} 没有 submitError 状态`).toMatch(/const submitError = ref/)
    expect(s, `${file} 没用 admin.formSubmitFailed`).toContain('admin.formSubmitFailed')
    expect(s, `${file} 弹窗里没有 el-alert`).toMatch(/<el-alert[^>]*v-if="submitError"/)
  })

  it.each(PAGES)('$file: 错误写进 alert, 不是再弹一条 toast', ({ file }) => {
    const s = src(file)
    expect(s, `${file} 提交失败又弹了一条 toast`).not.toMatch(/catch[\s\S]{0,400}?ElMessage\.error/)
  })
})

describe('管理端 CRUD · 下拉选项挂了不许提交脏数据', () => {
  it('SessionManage: 选项加载失败时禁用保存并给出提示', () => {
    const s = src('SessionManage.vue')
    expect(s, 'SessionManage 没有 optionsError 状态').toMatch(/const optionsError = ref/)
    expect(s, 'SessionManage 没用 admin.optionsLoadFailed').toContain('admin.optionsLoadFailed')
    expect(s, 'SessionManage 保存按钮没有因选项失败而禁用').toContain(':disabled="submitting || optionsError"')
  })

  it('SessionManage: 开场时间把空格写法归一成后端要的 T 分隔', () => {
    const s = src('SessionManage.vue')
    expect(s, 'SessionManage 没有归一开场时间').toContain('normalizeStartTime')
    expect(s, 'SessionManage 没有把日期与时间之间的空格换成 T').toContain("replace(' ', 'T')")
  })

  it('SessionManage: 下拉 id 不按「是不是数字」判 —— 雪花 ID 是字符串', () => {
    const s = src('SessionManage.vue')
    expect(s, 'movieId 没有自定义 validator').toMatch(/movieId: \[\{[\s\S]{0,400}?validator:/)
    expect(s, 'hallId 没有自定义 validator').toMatch(/hallId: \[\{[\s\S]{0,400}?validator:/)
    expect(
      s,
      '下拉 id 用了 type: number —— 雪花 ID 是字符串, 会被误判成非法而存不进去',
    ).not.toMatch(/(movieId|hallId): \[\{[^}]*type: 'number'/)
  })
})

describe('管理端 CRUD · 空状态有引导', () => {
  it.each(PAGES)('$file: 空表有提示与「去新建」入口', ({ file, addKey }) => {
    const s = src(file)
    const slot = emptySlot(s)
    expect(slot, `${file} 的空状态没有 admin.emptyHint`).toContain('admin.emptyHint')
    expect(slot, `${file} 的空状态没有「去新建」按钮`).toContain(addKey)
  })
})
