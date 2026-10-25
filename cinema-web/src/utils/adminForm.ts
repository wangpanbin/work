import type { FormInstance } from 'element-plus'

/**
 * 管理端表单的两个共用小工具(2026-10-25 交互审查后从三个 CRUD 页里抽出来)。
 *
 * <p>三个页面(影片/影厅/场次)需要的是**同一件事**: 提交前校验表单、
 * 失败时把后端原文留下来。这两件事此前在三个文件里各抄一份 —— 抄漏一处
 * 就是一个未处理的 rejection, 或一条永远不显示的错误。
 */

/**
 * 表单校验 —— validate() 校验失败时是 **reject**, 必须包在 try 里。
 *
 * <p>直接 await 会产生一条未处理的 Promise rejection(与 Login.vue 的 P3-3 同一个坑)。
 *
 * @param formRef 表单实例引用(传 `formRef.value`)
 * @returns 校验通过 true; 失败 false
 */
export async function validateForm(formRef: FormInstance | undefined): Promise<boolean> {
  try {
    // ⚠️ 表单还没挂上时 `validate()` 返回 undefined —— 此时**视为通过**,
    // 与改动前的行为一致(表单尚未就绪时不该把用户挡在门外)。
    const ok = await formRef?.validate()
    return ok !== false
  } catch {
    return false
  }
}

/**
 * 从后端错误里取出可读文案。
 *
 * <p>后端 40001 只回一条扁平 msg、不带字段名, 所以这行字就是管理员能拿到的
 * 全部信息 —— 要把它写进弹窗(el-alert)留在上下文里, 而不是只闪一条 toast。
 */
export function errMsg(e: unknown): string {
  const m = (e as Error | undefined)?.message
  return typeof m === 'string' ? m : ''
}
