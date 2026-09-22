/** T5 导出对话框 — 区间预设定义. 单一来源驱动 radio + computedRange. */

export type ExportPreset = 'today' | '7d' | '30d' | 'month' | 'custom'

export interface ExportPresetDef {
  /** i18n key — radio button 显示文本 (模板里 t(labelKey)) */
  labelKey: string
  /** 预设区间计算 (from, to 含端点). null 表示由用户在 UI 中再选 (custom) */
  compute: ((today: Date) => { from: Date; to: Date }) | null
}

/** 单一来源驱动 radio 渲染 + computedRange. 添加新预设只需在此注册, 不需改 Dashboard.vue. */
export const EXPORT_PRESETS: Record<ExportPreset, ExportPresetDef> = {
  today: {
    labelKey: 'admin.exportToday',
    compute: (today) => ({ from: today, to: today }),
  },
  '7d': {
    labelKey: 'admin.export7d',
    compute: (today) => {
      const from = new Date(today)
      from.setDate(from.getDate() - 6)
      return { from, to: today }
    },
  },
  '30d': {
    labelKey: 'admin.export30d',
    compute: (today) => {
      const from = new Date(today)
      from.setDate(from.getDate() - 29)
      return { from, to: today }
    },
  },
  month: {
    labelKey: 'admin.exportMonth',
    compute: (today) => ({
      from: new Date(today.getFullYear(), today.getMonth(), 1),
      to: today,
    }),
  },
  custom: {
    labelKey: 'admin.exportCustom',
    compute: null,
  },
}

export const DEFAULT_EXPORT_PRESET: ExportPreset = '7d'