import type { Id } from '../types'
import type { Hall, HallPayload } from '../types/admin-types'

/**
 * 影厅表单模型 —— 影厅管理的「座位布局 ↔ 表单」往返 seam。
 *
 * <p>**为什么要有这一层**: 影厅编辑要带 `vipFromRow` 往返, 而这个字段以前
 * 是散在 `HallManage.vue` 里的 `Object.assign` 手写代码 —— 一旦哪次漏抄,
 * 后端就会把整厅 VIP 座位重建成普通座位(后端 `update` 删光座位再按
 * `dto.getVipFromRow()` 重插)。把它收进纯函数后,
 * 往返的保真性可以被单测锁住, 不再靠人眼。
 */

/** 影厅表单模型(页面上可编辑的那份)。 */
export interface HallForm {
  /** 影厅 id; null = 尚未落库(新建中) */
  id: Id | null
  cinemaId: Id
  name: string
  seatRows: number
  seatCols: number
  /**
   * VIP 起始行(含)。
   *
   * <p>三个值是三件事, 不能混:
   * <ul>
   *   <li>`number` —— 确实有 VIP 区, 从第 N 行起;</li>
   *   <li>`null` —— 确实**没有** VIP 区;</li>
   *   <li>`undefined` —— **后端没返回这个字段**(老后端 / 老 Hall 实体没有这一列)，
   *       也就是「不知道」—— 与「没有」是两回事, 展示成空会误导管理员。</li>
   * </ul>
   */
  vipFromRow: number | null | undefined
}

/**
 * 提交给后端的影厅载荷 —— 类型在 `types/admin-types.ts` 的 `HallPayload`
 * (载荷只有一份定义, 不在两个文件里各声明一遍)。
 */
export type { HallPayload }

/**
 * VIP 起始行归一。
 *
 * <p>后端按 `vipFromRow != null` 判定座位是不是 VIP, 行号从 1 起算 ——
 * 所以 null / undefined / 0 / 负数 一律归一成「无 VIP 区」,
 * 绝不能让 0 混进去(0 会被当成「第 0 行起是 VIP」, 等于整厅 VIP)。
 */
function normalizeVipFromRow(v: number | null | undefined): number | null {
  if (v === null || v === undefined) return null
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return null
  return Math.trunc(n)
}

/**
 * 这个影厅的 VIP 区信息**是否已知**。
 *
 * <p>`false` = 后端没返回 `vipFromRow` 字段(老后端) → 表单里的空值是「不知道」,
 * 不是「没有 VIP 区」。这时要在界面上说清楚, 否则管理员会以为这一厅没有 VIP。
 *
 * <p>⚠️ 注意:**未知**不等于**危险** —— 后端 update 的语义是「null = 保持原样」,
 * 所以即使这里显示为空, 保存也不会清空已有的 VIP 区(2026-10-25 交互审查 P2-1)。
 */
export function vipInfoKnown(hall: Hall): boolean {
  return hall.vipFromRow !== undefined
}

/** 新建影厅的表单初值。 */
export function emptyHallForm(): HallForm {
  return { id: null, cinemaId: 1, name: '', seatRows: 8, seatCols: 12, vipFromRow: 7 }
}

/** 影厅 → 表单。 */
export function hallToForm(hall: Hall): HallForm {
  return {
    id: hall.id,
    cinemaId: hall.cinemaId,
    name: hall.name,
    seatRows: hall.seatRows,
    seatCols: hall.seatCols,
    // undefined(后端没给这个字段)要**原样保留** —— 归一成 null 就把
    // 「不知道」说成了「没有 VIP 区」。
    vipFromRow:
      hall.vipFromRow === undefined ? undefined : normalizeVipFromRow(hall.vipFromRow),
  }
}

/**
 * 表单 → 提交载荷。
 *
 * <p>「不知道」(undefined)也发 null —— 后端 update 的语义是「null = 保持原样」,
 * 所以这不会清空已有的 VIP 区(它只是让后端沿用当前值)。
 */
export function formToPayload(form: HallForm): HallPayload {
  return {
    cinemaId: form.cinemaId,
    name: form.name,
    seatRows: Number(form.seatRows),
    seatCols: Number(form.seatCols),
    vipFromRow: normalizeVipFromRow(form.vipFromRow),
  }
}
