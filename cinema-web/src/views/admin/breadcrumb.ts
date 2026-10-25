/**
 * breadcrumb.ts — 管理端面包屑的「路径 → 标题」映射(纯函数)。
 *
 * <p>**为什么独立成文件**:面包屑要回答的是「我在哪一级」, 这是纯数据映射, 与渲染无关。
 * 本仓库 vitest 跑在 environment: 'node'、没装 jsdom / @vue/test-utils, 挂不上 SFC ——
 * 逻辑埋在模板里就只能静态扫源码做契约断言(脆)。下沉成纯函数后, 本模块的行为可以被
 * `tests/views/adminShellContract.test.ts` 直接调用验证。范式与 `views/admin/trendChart.ts` 一致。
 *
 * <p>**i18n 约束**:各级标题复用**已有的页面标题键**, 不新增 i18n 键;
 * 本模块只产出键, 不调用翻译函数(见 `trendChart.ts` 的同款约束)。
 */

import { ROUTE_PATHS } from '../../constants/routes'

/** 面包屑的一级 */
export interface AdminCrumb {
  /** 跳转目标(仅祖先级可点) */
  path: string
  /** i18n 键 */
  labelKey: string
}

/** 根级: 管理端 —— 面包屑的固定起点 */
export const ADMIN_CRUMB_ROOT: AdminCrumb = {
  path: ROUTE_PATHS.adminHome,
  labelKey: 'admin.asideBrand',
}

/**
 * 当前页标题键 —— 复用已有的页面标题键。
 *
 * <p>未知路径不回落成裸路径: 宁可只显示根级, 也不显示一串没人看懂的 URL。
 */
const CURRENT_TITLE_KEY: Record<string, string> = {
  [ROUTE_PATHS.adminHome]: 'admin.homeSidebar.overview',
  [ROUTE_PATHS.adminDashboard]: 'admin.dashboardTitle',
  [ROUTE_PATHS.adminLive]: 'admin.liveTitle',
  [ROUTE_PATHS.adminMovies]: 'admin.moviesTitle',
  [ROUTE_PATHS.adminHalls]: 'admin.hallsTitle',
  [ROUTE_PATHS.adminSessions]: 'admin.sessionsTitle',
  [ROUTE_PATHS.adminChatStats]: 'admin.chatStats.title',
}

/**
 * 由当前路径算出面包屑的各级。
 *
 * @param path 当前路由路径(如 `/admin/dashboard`)
 * @returns 至少一级(根级); 已知路径再追加一级当前页标题
 */
export function adminCrumbs(path: string): AdminCrumb[] {
  const titleKey = CURRENT_TITLE_KEY[path]
  if (!titleKey) return [ADMIN_CRUMB_ROOT]
  return [ADMIN_CRUMB_ROOT, { path, labelKey: titleKey }]
}
