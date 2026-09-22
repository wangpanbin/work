# 中英双语切换 Spec — 影院抢票选座系统

> 日期:2026-09-22
> 决策:MATT 工作流走完整版(`grill-with-docs → to-spec → to-tickets → tdd → code-review → commit`),用户接受全部 4 个推荐选项
> 场景:**课程设计答辩 / 学校实训**(沿用既有 P0 spec / 对话助手 spec 的场景设定)
> 基线:W1-W5 + P0 + 营收导出 + 对话助手(T1-T9)均已落地
> 关联:`CONTEXT.md`、`docs/superpowers/specs/2026-09-21-chat-assistant-design.md`、`AGENTS.md`
> 术语:本文出现的「锁座 / 选座 / 待支付订单 / 行动卡片 / 场次上下文 / 座位索引」一律以 `CONTEXT.md` 为准

---

## 0. 一句话决策

在 `cinema-web` 引入 `vue-i18n@^10`,**全量翻译用户可见文案**,在顶栏增加一个 `🌐 EN/中文` chip(移动端进 avatar 菜单),切换语言通过 `localStorage.cinema_locale` 持久化,首次访问根据 `navigator.language` 嗅探默认语言。**Element Plus 内置组件(`ElMessage` / `ElMessageBox` / `Pagination` 等)的按钮文案不翻译**(走默认中文),仅翻译应用层文案。

---

## 1. 背景与问题

### 1.1 现状(代码级证据)

| 事实 | 证据 |
| --- | --- |
| 零 i18n 基础设施 | `grep -r 'i18n\|vue-i18n\|locale' cinema-web/src` 全 0 命中 |
| 文案完全硬编码中文 | `App.vue` 顶栏、`Home.vue` hero/section/筛选、`Login.vue` 表单/规则/`ElMessageBox`、`SeatSelect.vue` 锁座提示/座位状态/图例、`OrderList.vue` 状态/按钮/确认框、`ChatWidget.vue` 提示与时间格式(`toLocaleTimeString('zh-CN')`)等 |
| `package.json` 没有 vue-i18n | `cinema-web/package.json` 仅含 `vue / vue-router / pinia / axios / dayjs / element-plus / qrcode` |
| `main.ts` 全局引入 Element Plus | `createApp(App).use(createPinia()).use(router).use(ElementPlus)` — 若要切 Element Plus 组件的内置文案需要 `el-config-provider`,**本 spec 明确不切** |
| `Element Plus` 中文按钮文案是**运行时强文案** | `ElMessageBox` 的 "确定/取消" / `Pagination` 的 "共 N 条" 等内置 |
| `dayjs` 已引入但未用 `.locale()` | `SeatSelect.vue:5` `OrderList.vue:4` 等都只用 `format('MM-DD HH:mm')` |
| `toLocaleTimeString('zh-CN')` 在 ChatWidget 出现 3 处 | `ChatWidget.vue:53,59,65` |
| `ORDER_STATUS_VIEW` / `BUTTON_BY_ACTION` 已是集中常量 | `views/order/constants.ts` 唯一来源驱动 OrderList/Payment 的状态标签与按钮文案 |
| `EXPORT_PRESETS` 同样是集中常量 | `views/admin/constants.ts` 唯一来源驱动 Dashboard 导出预设 |
| 后端数据下发的 `OrderVO.statusText` / `movie.title` 是业务数据,非 UI 文案 | 这些**不翻译** — 影片名是数据,后端 statusText 也是,见 §2.3 |
| 现有 19 个 `.vue` + 26 个 `.ts` 文件,中文文案主要集中在 6 用户视图 + 6 管理视图 + 3 chat 组件 + App.vue | 见 §4.2 模块清单 |

### 1.2 要解决的问题

课程答辩要给英语评委演示 / 外校交流 / 后续可能开放给海外学生用。当前界面所有按钮、表单 placeholder、`ElMessage` 提示都是中文,**改完代码再让评委看没法临时切**。

---

## 2. 目标与非目标

### 2.1 目标

在**不修改任何既有抢票 / 选座 / 支付 / 对话链路业务代码**的前提下,提供一个**完整双语界面**:

1. 顶栏 chip + 移动端 avatar 菜单中能切语言;
2. 全量翻译用户可见 UI 文案(导航、按钮、表单、提示、状态、按钮文案、空态、错误提示);
3. 切换后即时生效,不刷新页面(响应式更新);
4. 浏览器刷新后保持上次选择;
5. 首次访问根据浏览器语言自动选;
6. 缺翻译时优雅降级(回退中文 + 开发态 console.warn),不显示裸露 key 字符串;
8. TypeScript 类型安全(`t()` 的 key 必须存在,缺失的 key 在 `pnpm type-check` 报错)。

### 2.2 非目标(本 spec 明确不做)

- **不翻译 Element Plus 内置组件的内置文案**:`ElMessageBox` 的 "确定/取消"、`Pagination` 的 "共 N 条"、DatePicker 的月份名等。理由见 §2.4。
- **不翻译后端下发的业务数据**:影片名(`movie.title`)、订单状态文本(`OrderVO.statusText`)、用户昵称(`user.nickname`)、座位描述(`seatDesc`)、场次时间字符串等。这些是**业务数据**,不是 UI 文案。
- **不做服务端 i18n**:本次只做前端翻译,不动 `cinema-server`。
- **不做多语言切换动画 / 渐变**:切换即时生效,无过渡。
- **不做 URL 路由反映语言(/en / /foo)?lang=zh**:切语言无 URL 变化。
- **不做 RTL / 阿语 / 西语**:本次只 zh-CN + en-US 两套。
- **不做内容协商 / Accept-Language 头解析**:仅前端基于 `navigator.language` 嗅探。
- **不引入 i18n-ally 编辑器插件约定**:不在仓库内加 `.vscode/extensions.json`。
- **不改测试栈**:不动 vitest 配置,新功能自带纯函数单元测试。

### 2.3 边界规则 — 哪些"中文"要翻译,哪些不要

这是本 spec 最容易被误读的地方,提前钉死:

| 类别 | 翻译? | 例 |
| --- | --- | --- |
| 模板里的中文文案 | ✅ | `>登录 / 注册`、`<h2>📋 我的订单</h2>` |
| `ElMessage.success('xxx')` 文案 | ✅ | `ElMessage.success('登录成功')` |
| `ElMessageBox.confirm(title, ...)` 的中文 title/body | ✅ | `'确定退出当前登录状态?'` |
| `FormRules` 的 `message` 字段 | ✅ | `{ required: true, message: '请输入用户名' }` |
| 后端返回的 `OrderVO.statusText` | ❌ | 后端 = 数据 |
| 后端返回的 `movie.title` / `seatDesc` | ❌ | 后端 = 数据 |
| 用户输入的 `nickname` / `username` | ❌ | 用户 = 数据 |
| `BUTTON_BY_ACTION.pay.label = '去支付'` 这种**视图常量** | ✅ | 这是 UI 文案,虽然来自常量 |
| `genreOptions = ['动作', '喜剧', ...]` 这种**用作 value 也用作 label 的分类** | 部分 | value 保留中文(后端契约),UI label 通过字典映射显示 |
| 后端下发的 chat 文案 / 行动卡片 | ❌ | LLM = 数据 |
| 元素 / `aria-label` 等可访问性属性 | ✅ | 一并翻译 |

### 2.4 为什么 Element Plus 内置组件不翻译

`ElMessageBox` 的 "确定/取消" 按钮、`Pagination` 的 "共 N 条" 等都是 **Element Plus 库内置的运行时常量**,要切它们需要:

1. 引入 `ElConfigProvider` 包裹根组件;
2. 注册 `element-plus/dist/locale/en` 或 `zh-cn` 包;
3. 在 `<ElConfigProvider :locale="currentElLocale">` 里切换。

代价:
- 额外 ~30KB gz locale 包,只为了改 ~10 个按钮文案;
- 与现有 `<template>` 直接调 `ElMessageBox` 的写法冲突(需统一改 `<el-config-provider>` 提供);
- vue-i18n 与 element-plus locale 是两套独立体系,运行时需要手动同步;
- 我们的组件用法大量依赖 `ElMessage` / `ElMessageBox` 的 `confirmButtonText` 字段来覆盖默认按钮(如 "我知道了" / "再看看"),这些**已经是应用层覆盖**,不影响。

收益:
- 用户看到的 "确定/取消" 之类按钮变成 "OK"/"Cancel",**对完整英文体验的边际提升很小**。

结论:**保留 Element Plus 默认中文**,只在 §2.3 范围内翻译应用层文案。这是性能 / 维护 / 体验的最优解。

---

## 3. 技术选型

### 3.1 版本

| 库 | 版本 | 理由 |
| --- | --- | --- |
| `vue-i18n` | `^11.0.0` | Vue 3.5 兼容;composition API `useI18n()`;支持嵌套 key / 复数 / 插值 / 命名空间;与 Vue 3.5 + Vite 6 + vitest 3 生态已对齐;~25KB gz |
| `element-plus` | 不变 | 不引入 locale 子包,见 §2.4 |

### 3.2 字典结构

按**视图 + 公共**分文件(便于后续按视图懒加载,本次不分):

```
src/i18n/
├── index.ts                # createI18n 实例 + locale 持久化 + 嗅探逻辑
├── fallback.ts             # t() 包装函数: 缺 key 回退中文 + dev warn
├── locales/
│   ├── zh-CN.ts            # 中文(主,key 唯一来源)
│   └── en-US.ts            # 英文翻译
└── __tests__/              # 纯函数单测
    ├── fallback.test.ts
    └── locales.test.ts
```

字典 key 命名:**双段式 + 文件前缀**避免冲突:

```ts
// zh-CN.ts 的导出形状(简化示意)
export default {
  common: {
    confirm: '确定',
    cancel: '取消',
    save: '保存',
    loading: '加载中…',
  },
  app: {
    brand: '星辉影城',
    brandTag: 'CINEMA',
    chipAdmin: '管理端',
    chipOrders: '我的订单',
    chipLogout: '退出',
    chipLogin: '登录 / 注册',
    logoutTitle: '退出登录',
    logoutConfirm: '确定退出当前登录状态?',
    logoutConfirmOk: '确定退出',
    logoutConfirmCancel: '再看看',
  },
  home: {
    heroBadge: 'NOW SHOWING',
    heroTitle: '光影世界 · 星光璀璨',
    heroSubtitle: '精选热映大片,尊享极致观影体验',
    statMovies: '部热映影片',
    statResolution: '超清画质',
    statImmersive: '沉浸体验',
    sectionTitle: '🎬 热映影片',
    searchPlaceholder: '搜索片名 / 关键词',
    filterGenre: '类型',
    filterRegion: '地区',
    filterClear: '重置',
    filterMeta: (n: number) => `${n} 部影片`,
    empty: '暂无匹配影片,试试清空筛选条件',
    viewDetail: '查看详情 →',
    minutes: (n: number) => `${n} 分钟`,
  },
  // ... login / seat / order / payment / admin / chat 各段
} as const
```

- **as const**:给 vue-i18n + TS 提供字面量类型推导,`useI18n()` 自动产出 `t` 的精确 key 类型。
- **类型导出**:每个字典文件 `export type Messages = typeof messages`,`index.ts` 用 `zh: typeof zhCN.Messages` + `en: typeof enUS.Messages` 推断 union 类型,保证 **en-US 的 key 与 zh-CN 一一对应,缺一个 tsc 报错**。
- **插值函数**:`minutes: (n) => '${n} 分钟'` 由调用方 `t('home.minutes', n)` 传参(高级用法,本次只在不得不动态拼接处用)。

### 3.3 持久化与嗅探策略

```ts
// 伪代码,实现在 src/i18n/index.ts
const STORAGE_KEY = 'cinema_locale'
const SUPPORTED = ['zh-CN', 'en-US'] as const

function detectInitialLocale(): SupportedLocale {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved && SUPPORTED.includes(saved)) return saved
  const nav = navigator.language  // e.g. 'en-US', 'zh-CN', 'en-GB'
  if (nav.startsWith('zh')) return 'zh-CN'   // zh-TW / zh-HK 也走 zh-CN
  if (nav.startsWith('en')) return 'en-US'   // en-GB / en-AU 也走 en-US
  return 'zh-CN'                              // 默认
}

i18n = createI18n({
  legacy: false,                              // 用 useI18n() composition API
  locale: detectInitialLocale(),
  fallbackLocale: 'zh-CN',                    // 缺 key 自动回退
  messages: { 'zh-CN': zhCN, 'en-US': enUS },
})

// 切换: i18n.global.locale.value = next
// 同时: localStorage.setItem(STORAGE_KEY, next)
```

- `fallbackLocale: 'zh-CN'`:vue-i18n 内置回退链 — 缺 key 不会抛,只回退到 `zh-CN` 字典。
- 在 `fallback.ts` 再包一层:**当 i18n 内部回退命中时**(key 在 zh-CN 有但 en-US 没有),开发态 console.warn,生产态静默。

### 3.5 与 dayjs / Intl 的衔接

- `dayjs`:项目现有格式串 `'MM-DD HH:mm'` `'YYYY-MM-DD HH:mm'` 已是固定格式串,不依赖 locale;**不需要 dayjs.locale() 切换**。
- `toLocaleTimeString('zh-CN', ...)`:ChatWidget 三处时间格式改成 `toLocaleTimeString(i18n.global.locale.value, { hour: '2-digit', minute: '2-digit' })`,自动跟着语言切换。
- `Intl.NumberFormat`:项目目前只用 `.toFixed(2)`,无货币 / 数字本地化需求,本次不做。
- Element Plus DatePicker / Pagination 项目不用 `.locale()`。

---

## 4. 实现决策

### 4.1 关键决策(用户已确认)

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 引入 `vue-i18n@^10` | 用户已选(Q2) |
| D2 | 全量翻译用户可见 UI 文案(250-400 个 key) | 用户已选(Q1),见 §2.3 边界 |
| D3 | 切换按钮 = 顶栏 chip(桌面)+ avatar dropdown(移动) | 用户已选(Q3),与现有 chip 风格一致 |
| D4 | 持久化 = `localStorage.cinema_locale` + 首次 `navigator.language` 嗅探 | 用户已选(Q4) |
| D5 | 缺翻译回退策略 = 回退中文 + 开发态 console.warn | 用户未明选,作为推荐默认实现 |
| D6 | **不**翻译 Element Plus 内置组件文案 | §2.4,工程权衡 |
| D7 | **不**翻译后端下发的业务数据(影片名 / 状态文本 / 昵称) | §2.3,数据 vs 文案的边界 |
| D8 | 后端**不**动 | §2.2 非目标 |

### 4.2 改动模块清单

**新增**:

| 模块 | 作用 |
| --- | --- |
| `src/i18n/index.ts` | `createI18n` 实例 + 持久化 + 嗅探 + `app.use()` |
| `src/i18n/fallback.ts` | `useT()` 包装 hook,带 dev warn |
| `src/i18n/locales/zh-CN.ts` | 主字典(as const) |
| `src/i18n/locales/en-US.ts` | 翻译字典(类型与 zh-CN 锁齐) |
| `src/i18n/locales/category.ts` | 类型:类型 / 地区 字典(`{ '动作': '动作' \| 'Action' }`) |
| `src/i18n/__tests__/fallback.test.ts` | 纯函数单测 |
| `src/i18n/__tests__/locales.test.ts` | key 对齐 / 类型测试 |

**修改(只动文案 / 不动业务逻辑)**:

| 模块 | 改什么 |
| --- | --- |
| `src/main.ts` | `app.use(i18n)` |
| `src/App.vue` | 顶栏 chip 文案 + 加语言切换 chip;登出 ElMessageBox 文案 |
| `src/stores/i18n.ts` | 新增:暴露 `locale` + `setLocale()`,Pinia 包装以便模板直接用 |
| `src/views/Home.vue` | hero / section / 筛选 / 卡片 overlay / 空态 / 类型-地区映射 |
| `src/views/Login.vue` | tab label / placeholder / 表单规则 message / `ElMessage` / `ElMessageBox` 文案 |
| `src/views/MovieDetail.vue` | 标题 / 按钮 / 场次列表 / 描述 |
| `src/views/SeatSelect.vue` | WS 状态 / 屏幕 / 座位图例 / 摘要 / 锁座 ElMessage / 倒计时标签 |
| `src/views/Payment.vue` | 标题 / 二维码 alt / 倒计时 / 状态 banner / 支付按钮 / 确认弹窗 |
| `src/views/OrderList.vue` | 页头 / tabs / 空态 / 金额标签 / 按钮文案(`BUTTON_BY_ACTION` 字典化) |
| `src/views/order/constants.ts` | `ORDER_STATUS_VIEW` 改为 i18n key(而不是硬编码 label)— 用 `useI18n()` 在 getter 解析,或保留 label 字段为 zh-CN fallback,模板里 `t(key)` |
| `src/views/admin/AdminHome.vue` | 侧栏菜单 |
| `src/views/admin/Dashboard.vue` | 页头 / 导出按钮 / 统计卡片 / 导出对话框 |
| `src/views/admin/constants.ts` | `EXPORT_PRESETS.label` 改为 i18n key |
| `src/views/admin/LiveDashboard.vue` | 实时看板标题 / 占位 / 时间格式 |
| `src/views/admin/SessionManage.vue` | 列表 / 表单 |
| `src/views/admin/MovieManage.vue` | 列表 / 表单 |
| `src/views/admin/HallManage.vue` | 列表 / 表单 |
| `src/components/chat/ChatWidget.vue` | 标题 / 占位 / 时间格式 / LOGIN_REQUIRED 提示 |
| `src/components/chat/ChatMessage.vue` | 行动卡片按钮文案(回退/锁定) |
| `src/components/chat/ActionCard.vue` | 卡片标题 / 副标题 |
| `src/views/NotFound.vue` | 404 文案 |
| `index.html` | `<title>` 标签动态由 i18n 提供(可选,或保留 '星辉影城') |

**不改**:

- `cinema-server/**` (后端不动)
- `stores/seat.ts` / `stores/user.ts` / `stores/movieCache.ts`(无 UI 文案)
- `stores/seatEventReducer.ts`(无 UI 文案)
- `composables/useChatContext.ts`(无 UI 文案)
- `utils/**`(无 UI 文案)
- `api/**`(无 UI 文案)
- `router/index.ts`(无 UI 文案)
- `types/**`(无 UI 文案)
- `styles/**`(无 UI 文案)

### 4.3 切换按钮的 UI 实现要点

```vue
<!-- App.vue 顶栏(简化示意) -->
<div class="user-area desktop-only">
  <!-- 新增: 语言切换 chip -->
  <el-button
    class="header-chip chip-locale chip-icon-text"
    size="default"
    @click="toggleLocale"
  >
    <span class="chip-icon">🌐</span>
    <span class="chip-text">{{ locale === 'zh-CN' ? 'EN' : '中文' }}</span>
  </el-button>
  <!-- ... 现有管理端 / 订单 / 头像 chip 不动 ... -->
</div>

<!-- 移动端: avatar dropdown 内多一项 -->
<el-dropdown-item command="toggleLocale">
  <span style="margin-right:8px">🌐</span>
  {{ locale === 'zh-CN' ? 'English' : '中文' }}
</el-dropdown-item>
```

- 桌面端 chip 显示 `🌐 EN`(当前中文,点了切英文) / `🌐 中文`(当前英文,点了切中文) — **始终显示"切过去之后的语言名"**,符合用户预期。
- 移动端 dropdown 一项,与现有菜单项同款样式。
- chip 颜色:用现有 chip 风格的第三种(避免与 chip-primary / chip-danger 重复),新加 `chip-locale` 类用 brand 配色的中性变体,或复用 `chip-user nickname` 的样式。

### 4.4 类型安全策略

```ts
// src/i18n/locales/zh-CN.ts
const messages = {
  common: { /* ... */ },
  app: { /* ... */ },
  // ...
} as const
export default messages
export type Messages = typeof messages
export type MessageKeys = keyof Messages              // 'common' | 'app' | 'home' | ...
export type NestedKey<T> = T extends object
  ? `${keyof T & string}` | `${keyof T & string}.${NestedKey<T[keyof T]>}`
  : never
export type AllKeys = NestedKey<Messages>             // 完整点路径 union,例如 'app.brand' | 'home.minutes' | ...

// src/i18n/locales/en-US.ts
import type { Messages as ZhMessages } from './zh-CN'
const messages: SameShape<ZhMessages> = { /* ... */ }   // SameShape 工具类型确保 en-US 的 key 与 zh-CN 一一对应
```

`SameShape<A>` 工具类型 = 递归让 B 的 key 与 A 完全相同,且值类型兼容(都 string 或都 (n) => string)。

**结论**:`pnpm type-check` 时,如果 `en-US.ts` 漏 key → `SameShape` 不通过 → tsc 报错 → **CI 阶段就能挡掉翻译缺失**。

### 4.5 与 stores/order + 集中常量的兼容

`views/order/constants.ts` 当前是**硬编码中文 label**:

```ts
[ORDER_STATUS.PENDING_PAY]: { label: '待支付', ... }
```

翻译方案:**不引入运行时 i18n 调用进入纯常量文件**,改用 **i18n key**:

```ts
[ORDER_STATUS.PENDING_PAY]: { labelKey: 'order.status.pending', ... }
```

调用处 `OrderList.vue` 模板里改成 `{{ t(viewOf(o.status).labelKey) }}`,而不是 `{{ viewOf(o.status).label }}`。同理 `BUTTON_BY_ACTION.pay.label` → `pay.labelKey`。`EXPORT_PRESETS.today.label` → `today.labelKey`。

这样:
- 常量文件保持纯数据结构,无运行时依赖
- 翻译文件统一在 `i18n/locales/*`
- 单测不依赖 `vue-i18n`

### 4.6 类别 / 地区字典

`genreOptions` / `regionOptions` 当前 value 和 label 都是中文,且 value 是传给后端的 filter。**保留 value 为中文**(后端 LIKE 匹配),label 走字典:

```ts
// Home.vue
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
// value 不变,后端契约
const genreOptions = ['动作', '喜剧', '科幻', '爱情', '悬疑', '动画', '战争', '剧情']
const regionOptions = ['中国大陆', '美国', '日本', '韩国', '欧洲', '印度', '泰国']

// 模板:
<el-option v-for="g in genreOptions" :key="g" :label="t(`category.genre.${g}`)" :value="g" />
```

字典:

```ts
// zh-CN.ts
category: {
  genre: {
    '动作': '动作', '喜剧': '喜剧', '科幻': '科幻', '爱情': '爱情',
    '悬疑': '悬疑', '动画': '动画', '战争': '战争', '剧情': '剧情',
  },
  region: {
    '中国大陆': '中国大陆', '美国': '美国', '日本': '日本',
    '韩国': '韩国', '欧洲': '欧洲', '印度': '印度', '泰国': '泰国',
  },
}

// en-US.ts
category: {
  genre: {
    '动作': 'Action', '喜剧': 'Comedy', '科幻': 'Sci-Fi',
    '爱情': 'Romance', '悬疑': 'Mystery', '动画': 'Animation',
    '战争': 'War', '剧情': 'Drama',
  },
  region: {
    '中国大陆': 'Mainland China', '美国': 'United States',
    '日本': 'Japan', '韩国': 'South Korea', '欧洲': 'Europe',
    '印度': 'India', '泰国': 'Thailand',
  },
}
```

类型安全由 §4.4 SameShape 工具类型自动保证。

---

## 5. 测试决策

### 5.1 测试 seam(单一集中测试入口)

按用户档案 §2 的偏好 + 项目无 `@vue/test-utils`,**不写组件集成测试**,只测**纯函数模块**:`src/i18n/__tests__/`。

| 测试目标 | 测试 seam | 测什么 |
| --- | --- | --- |
| `fallback.ts` 的回退 + dev warn | 直接 import 函数 | 给一个 en-US 字典有 / 没有的 key,断言返回值 + console.warn 是否触发 |
| `locales/zh-CN.ts` 和 `en-US.ts` 的 key 对齐 | tsc(在 `pnpm type-check` 阶段) | SameShape 工具类型保证缺 key 即报错 |
| `detectInitialLocale()` 的嗅探逻辑 | 直接 import 函数 | mock `localStorage.getItem` / `navigator.language` 三种 case:有 storage / 英文浏览器 / 中文浏览器 / 其他语言 |
| `setLocale()` 持久化逻辑 | 直接 import 函数 | mock `localStorage.setItem`,断言 setItem 被调用 + key 正确 |

**理想 seam 数 = 1**(全部在 `src/i18n/__tests__/`),组件层用 `pnpm type-check` + 手动浏览器验证代替。

### 5.2 集成验证(浏览器侧)

不在自动化测试里,但用户验收流程:

1. `pnpm dev` 启动,默认中文(浏览器 locale=en-US 时验证嗅探);
3. 点击顶栏 `🌐 EN` → 整页所有可见文案变英文,顶栏 chip 文字变为 `🌐 中文`;
4. 刷新页面 → 仍是英文(验证 localStorage 持久化);
5. 访问 `/admin/dashboard` → 切换仍生效;
6. 访问 `/orders` → 状态标签 / 按钮文案变英文;
7. 访问 `/seat/{id}` → 座位图例 / 锁座 ElMessage 变英文;
8. 打开 ChatWidget → 提示文案变英文,时间格式 `HH:mm` 英文 locale;
9. 触发 `ElMessageBox.confirm`(`退出登录` / 取消订单 / 退款)→ 标题与 body 变英文,内置按钮仍是中文(符合 §2.4);
10. `pnpm test` → 4 个新增 i18n 测试通过;
11. `pnpm type-check` → 无新增错误;
12. `pnpm build` → bundle 大小增量 < 30KB gz(vue-i18n ~25KB + 字典 ~3-5KB)。

### 5.3 与既有测试的兼容

- `tests/`(vitest)新增 `tests/i18n/` 与现有 7 个 `.test.ts` 文件并列。
- 既有 81 个测试用例**不应受影响**(不动既有组件实现)。
- 跑 `pnpm test` 验证既有用例仍然全绿。

---

## 6. Out of Scope(再次明确)

1. 不翻译 Element Plus 内置组件(理由 §2.4)。
2. 不翻译后端业务数据(理由 §2.3)。
3. 不做服务端 i18n。
4. 不做 RTL / 多语言扩展(只 zh-CN + en-US)。
5. 不改 URL 路由 / `?lang=` 反映语言。
6. 不做内容协商(`Accept-Language`)。
7. 不做数字 / 货币 / 日期格式的 locale-aware(只用现成的 `dayjs.format('MM-DD HH:mm')`)。
8. 不引入 i18n-ally 编辑器插件约定。
9. 不引入 `vue-i18n` 的日期 / 数字格式化 helper。
10. 不引入 `Intl.NumberFormat` 或类似 API。
11. **不写组件级 vue-test-utils 集成测试**(项目无 @vue/test-utils,无需求单测覆盖所有组件 — 用 `pnpm type-check` + SameShape 工具类型保证类型层一致性)。

---

## 7. Further Notes

### 7.1 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| en-US 字典漏 key | `SameShape<ZhMessages>` 工具类型 → tsc 报错,CI 拦截 |
| 翻译质量差(机翻味) | 用户审阅 en-US.ts;遵循"宁短勿诗意" |
| 切换按钮遮挡 | 桌面 chip 已是 mobile-friendly(media query),移动端在 dropdown 不挤 |
| 与既有 element-plus 全局引入冲突 | 不切 element-plus locale,**互不影响** |
| `OrderVO.statusText` 是后端下发中文 | §2.3 边界,D7 — **不翻译**,这是业务数据 |
| 类型电影 / 地区 category 是动态映射 | §4.6 字典,值是后端契约,key 是 UI label |

### 7.2 后续可扩展点(本次不做)

- **懒加载字典**:按视图拆 `i18n/messages/{zh,en}/home.ts` + 路由级 `lazy: true`,减小首屏 bundle
- **数字 / 货币本地化**:接入 `Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })`
- **日期本地化**:用 `dayjs.locale()` 切 `'en'`
- **服务端 i18n**:给 chat 助手加 `Accept-Language` 头,影响 LLM 行为(独立 spec)
- **ActionCard 翻译**:目前只翻译按钮文案,卡片副标题可读后端数据(`sessionDesc` 等)
- **新增语言**(ja-JP / ko-KR):SUPPORTED 数组加一项,字典加一文件,SameShape 对照 zh-CN 即可

### 7.3 验收 checklist(用户)

- [ ] `pnpm install` 装上 vue-i18n
- [ ] `pnpm type-check` 无新增错误
- [ ] `pnpm test` 既有 81 个 + 新增 4 个 i18n 测试全绿
- [ ] `pnpm build` 成功,bundle 增量 < 30KB gz
- [ ] `pnpm dev` 启动后,顶栏 `🌐 EN` chip 可见,点击切换即时刷新文案
- [ ] 刷新页面保持英文(localStorage)
- [ ] 清 localStorage 后,英文浏览器自动嗅探到英文
- [ ] 主要路径(/, /login, /movie/:id, /seat/:id, /payment, /orders, /admin/dashboard)全英文无中文残留
- [ ] ChatWidget 时间格式英文显示(`9:05 AM` 而非 `9:05`)
- [ ] 触发 `ElMessageBox` 时,标题与 body 英文,内置按钮仍是"确定/取消"(符合预期)