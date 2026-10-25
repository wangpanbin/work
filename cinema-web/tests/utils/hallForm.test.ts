import { describe, it, expect } from 'vitest'
import { hallToForm, formToPayload, emptyHallForm, vipInfoKnown } from '../../src/utils/hallForm'
import type { Hall } from '../../src/types/admin-types'

/**
 * 影厅表单往返的规格。
 *
 * <p>影厅管理的后端 `update` 会**删光全部座位再按 `vipFromRow` 重插** ——
 * 所以「编辑一个影厅会不会丢 VIP 区」这件事, 全部压在前端能不能把
 * `vipFromRow` 原样带回去上。页面上手写 `Object.assign` 抄字段时漏掉一次,
 * 整厅 VIP 座位就静默降级成普通座位, 而且没有任何报错。
 *
 * <p>这层纯函数就是那道 seam: 往返的保真性由本文件锁住, 不靠人眼。
 */

/** 造一个影厅; 只覆盖关心的字段。 */
const makeHall = (over: Partial<Hall> = {}): Hall => ({
  id: '1900000000000000001',
  cinemaId: 1,
  name: '1 号厅',
  seatRows: 10,
  seatCols: 14,
  seatCount: 140,
  ...over,
})

describe('影厅表单 · VIP 起始行的往返', () => {
  it('带 VIP 区的影厅往返一圈后, VIP 起始行仍是原来那一行', () => {
    const hall = makeHall({ vipFromRow: 3 })
    const payload = formToPayload(hallToForm(hall))
    expect(payload.vipFromRow).toBe(3)
  })

  it('本来就没有 VIP 区的影厅往返后仍然是「没有」, 不会变成 0', () => {
    const hall = makeHall({ vipFromRow: null })
    const payload = formToPayload(hallToForm(hall))
    expect(payload.vipFromRow).toBeNull()
  })

  it('后端没带 vipFromRow 字段时, 表单记的是「不知道」(undefined)而不是「无 VIP」', () => {
    const hall = makeHall({ vipFromRow: undefined })
    const form = hallToForm(hall)
    // 「不知道」与「没有」是两件事 —— 归一成 null 就把未知说成了没有
    // (2026-10-25 评审: 老后端没这一列时, 表单显示空 = 管理员以为这厅没 VIP)
    expect(form.vipFromRow).toBeUndefined()
    expect(vipInfoKnown(hall)).toBe(false)
    // 但提交载荷仍然是 null —— 后端 update 的语义是「null = 保持原样」,
    // 所以这不会清空已有的 VIP 区
    expect(formToPayload(form).vipFromRow).toBeNull()
  })

  it('「确实没有 VIP 区」与「不知道」在表单里分得开', () => {
    const noVip = makeHall({ vipFromRow: null })
    const unknown = makeHall({ vipFromRow: undefined })
    expect(vipInfoKnown(noVip)).toBe(true)
    expect(vipInfoKnown(unknown)).toBe(false)
    expect(hallToForm(noVip).vipFromRow).toBeNull()
    expect(hallToForm(unknown).vipFromRow).toBeUndefined()
  })

  it('不合法的 VIP 起始行(0 / 负数)归一成「无 VIP」, 不会当成合法行号发出去', () => {
    expect(formToPayload(hallToForm(makeHall({ vipFromRow: 0 }))).vipFromRow).toBeNull()
    expect(formToPayload(hallToForm(makeHall({ vipFromRow: -2 }))).vipFromRow).toBeNull()
  })
})

describe('影厅表单 · 其余字段的往返', () => {
  it('影厅 id 往返后仍指向同一个厅', () => {
    const hall = makeHall({ id: '1900000000000000009' })
    expect(hallToForm(hall).id).toBe('1900000000000000009')
  })

  it('影院 / 名称 / 行列数原样带进提交载荷', () => {
    const hall = makeHall({ cinemaId: 3, name: 'IMAX 厅', seatRows: 12, seatCols: 20 })
    const payload = formToPayload(hallToForm(hall))
    expect(payload.cinemaId).toBe(3)
    expect(payload.name).toBe('IMAX 厅')
    expect(payload.seatRows).toBe(12)
    expect(payload.seatCols).toBe(20)
  })

  it('提交载荷里不夹带 id —— 后端按路径参数定位影厅, 多带字段只会变成噪音', () => {
    const payload = formToPayload(hallToForm(makeHall()))
    expect(Object.keys(payload)).not.toContain('id')
  })

  it('新建表单是空名称 + 默认行列, 且没有落库 id', () => {
    const form = emptyHallForm()
    expect(form.id).toBeNull()
    expect(form.name).toBe('')
    expect(form.seatRows).toBeGreaterThan(0)
    expect(form.seatCols).toBeGreaterThan(0)
  })
})
