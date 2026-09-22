/**
 * en-US.ts — 英文翻译字典
 *
 * 单一契约:`SameShape<typeof zhCN>` 类型工具会强制 key 形状与 zh-CN 完全一致
 * (任何漏 key → tsc 报错,i18n-N ticket 修复后才能继续)。
 *
 * 字典结构与 zh-CN.ts 一一对应。
 */

import type zhCN from './zh-CN'

const messages: SameShape<typeof zhCN> = {
  common: {
    confirm: 'OK',
    cancel: 'Cancel',
    save: 'Save',
    loading: 'Loading…',
    retry: 'Retry',
    back: 'Back',
  },

  app: {
    brand: 'Star Cinema',
    brandTag: 'CINEMA',
    chipAdmin: 'Admin',
    chipOrders: 'My Orders',
    chipLogout: 'Logout',
    chipLogin: 'Sign in / Register',
    localeSwitchTo: '中文',
    mobileMenuLocale: '中文',
    localeSwitchAria: 'Switch language',
    menuAria: 'User menu',
    logoutTitle: 'Sign out',
    logoutConfirm: 'Sign out of your current account?',
    logoutConfirmOk: 'Sign out',
    logoutConfirmCancel: 'Stay',
  },

  home: {
    heroBadge: 'NOW SHOWING',
    heroTitle: 'A World of Light and Starlight',
    heroSubtitle: 'Hand-picked blockbusters, premium viewing experience',
    statMovies: 'movies now showing',
    statResolution: 'Ultra HD',
    statImmersive: 'Immersive',
    sectionTitle: '🎬 Now Showing',
    searchPlaceholder: 'Search title / keyword',
    filterGenre: 'Genre',
    filterRegion: 'Region',
    filterClear: 'Reset',
    filterMeta: '{count} movies',
    empty: 'No movies match. Try clearing filters.',
    viewDetail: 'View details →',
    minutes: '{n} min',
  },

  login: {
    brandName: 'Star Cinema',
    brandSubtitle: 'STAR CINEMA',
    tabLogin: 'Welcome back',
    tabRegister: 'Join us',
    username: 'Username',
    password: 'Password',
    usernameReg: 'Username (4-20 letters/digits/underscore)',
    passwordReg: 'Password (6-32 chars)',
    confirmPassword: 'Confirm password',
    confirmPasswordReg: 'Re-enter your password',
    passwordMismatch: 'Passwords do not match',
    nickname: 'Nickname (optional)',
    phone: 'Phone (optional)',
    submitLogin: 'Sign in',
    submitRegister: 'Create account',
    successLogin: 'Signed in',
    successRegister: 'Registered — please sign in',
    tipFirstUse: 'First time here?',
    tipRegister: 'Create an account',
    tipForgot: 'Forgot password?',
    titleForgot: 'Reset password',
    forgotContent: 'Password reset is under development. Please contact cinema staff for assistance.\nWe apologize for the inconvenience.',
    forgotOk: 'Got it',
    titleRateLimit: 'Too many attempts',
    rateLimitContent: 'Too many sign-in attempts. You have been temporarily rate-limited.\nPlease wait 1-2 minutes before retrying.',
    rateLimitOk: 'Got it',
    footerCopy: '© 2026 Star Cinema · A World of Light and Starlight',
  },

  movie: {
    back: 'Back',
    loadFailed: 'Failed to load. Please try again.',
    notFound: 'Movie not found',
    metaGenre: 'Genre',
    metaRegion: 'Region',
    metaDuration: 'Duration',
    metaRelease: 'Release',
    sessionsTitle: 'Showtimes',
    noSessions: 'No showtimes available',
    selectSeats: 'Select seats',
    hallLabel: 'Hall {hall}',
    priceLabel: '¥{price} / seat',
    remainingLabel: '{n} seats left',
  },

  seat: {
    wsOpen: 'Live sync active',
    wsConnecting: 'Connecting…',
    wsClosed: 'Disconnected, reconnecting — seat status may be stale',
    refresh: 'Refresh',
    screen: 'SCREEN',
    largeHallHint: 'This session has {rows} rows × {cols} seats · long-press or hover to see exact location',
    legendAvailable: 'Available',
    legendSelected: 'Selected',
    legendMine: 'Your lock',
    legendLocked: 'Locked by others',
    legendSold: 'Sold',
    selectedCount: 'Selected',
    countdown: 'Starts in',
    totalAmount: 'Total',
    pricePerSeat: '/seat',
    confirmLock: 'Confirm & lock seats',
    warnLogin: 'Please sign in first',
    warnSelectSeat: 'Please select a seat first',
    warnMaxSelect: 'Maximum {n} seats',
    infoUnavailable: 'This seat is not available',
    successLock: 'Seats locked!',
    warnSeatsTaken: 'Your {n} selected seats were taken — removed automatically',
    errorConflict: 'Selected seats were taken: {list} — please reselect',
    errorConflictRefreshed: 'Selected seats were taken — seat map refreshed',
    errorLockFailed: 'Failed to lock seats',
  },

  order: {
    pageTitle: '📋 My Orders',
    tabAll: 'All',
    empty: 'No orders yet',
    refundingTip: 'Refund is being processed, usually within 1-3 business days. Contact cinema staff if not received in time.',
    refundingBtn: 'Refunding…',
    amountTotal: 'Total',
    amountRefunded: 'Refunded',
    currency: '¥',
    statusPending: 'Pending payment',
    statusPaid: 'Paid',
    statusCancelled: 'Cancelled',
    statusRefunding: 'Refunding',
    statusRefunded: 'Refunded',
    bannerPaid: 'Payment success — enjoy the show!',
    bannerCancelled: 'Order cancelled',
    bannerRefunding: 'Refund in progress, please wait…',
    bannerRefunded: 'Refunded, seats released',
    actionPay: 'Pay now',
    actionCancel: 'Cancel order',
    actionRefund: 'Request refund',
    actionRebook: 'Rebook seats',
    actionViewDetail: 'View details',
    actionViewTicket: 'View ticket',
    cancelTitle: 'Cancel order',
    cancelConfirm: 'Cancel «${movie}» ( {seats} )? Seats will be released immediately. This cannot be undone.',
    cancelOk: 'Cancel order',
    cancelCancel: 'Stay',
    cancelSuccess: 'Cancelled',
    refundTitle: 'Request refund',
    refundConfirm: 'Refund {movie} ({seats})? Refund will be returned to your original payment; seats will be released immediately.',
    refundOk: 'Confirm refund',
    refundCancel: 'Cancel',
    refundSuccess: 'Refund request submitted',
    refundFailStarted: 'Show has started — refund unavailable',
  },

  payment: {
    title: 'Payment',
    qrAlt: 'Payment QR code',
    countdown: 'Time remaining',
    payNow: 'Pay now',
    cancelOrder: 'Cancel order',
    paySuccess: 'Payment success',
    payFailed: 'Payment failed',
    cancelDialogTitle: 'Cancel order',
    cancelDialogConfirm: 'Cancel this order? Seats will be released immediately. This cannot be undone.',
    cancelDialogOk: 'Confirm cancel',
    cancelDialogCancel: 'Stay',
  },

  admin: {
    homeSidebar: {
      dashboard: '📊 Dashboard',
      live: '⚡ Live monitor',
      movies: '🎬 Movies',
      halls: '🏛 Halls',
      sessions: '📅 Sessions',
    },
    dashboardTitle: '📊 Dashboard',
    exportBtn: '📥 Export Excel',
    backToHome: 'Back to admin home',
    liveTitle: '⚡ Live Monitor',
    exportDialogTitle: 'Export revenue report',
    exportToday: 'Today',
    export7d: 'Last 7 days',
    export30d: 'Last 30 days',
    exportMonth: 'This month',
    exportCustom: 'Custom',
    exportDateRange: 'Date range',
    exportOk: 'Export',
    exportCancel: 'Cancel',
    exportSuccess: 'Exported {name}',
    exportFailed: 'Export failed',
  },

  chat: {
    fabAria: 'Open chat assistant',
    closeAria: 'Close',
    title: 'Cinema Assistant',
    metaLogged: 'Signed in',
    metaAnon: 'Anonymous',
    emptyGreeting: '👋 Hi, I am the cinema assistant.',
    emptyExamples: 'Try asking: What movies are playing at 8pm tonight? Are there seats available for this one?',
    emptyReadonly: 'Read-only — seat lock / payment / refund still requires your confirmation (spec ADR-0002).',
    inputPlaceholder: 'Type a message, Enter to send, Shift+Enter for new line',
    sendBtn: 'Send',
    sending: 'Sending…',
    errorSend: 'Send failed, please retry',
    errorGeneric: 'Sorry, something went wrong.',
    loginRequiredTitle: 'Notice',
    loginRequiredBody: 'You need to sign in to continue. Sign in now?',
    loginRequiredOk: 'Sign in',
    loginRequiredCancel: 'Later',
    cardSeatsLabel: 'Suggested seats',
    cardAmountLabel: 'Estimated amount',
    cardJumpBtn: 'Go to seats',
    cardFallbackBtn: 'Show fallback',
  },

  notFound: {
    code: '404',
    title: 'Page not found',
    subtitle: 'The page you visited does not exist or has been removed.',
    backHome: 'Back to home',
  },

  category: {
    genre: {
      '动作': 'Action',
      '喜剧': 'Comedy',
      '科幻': 'Sci-Fi',
      '爱情': 'Romance',
      '悬疑': 'Mystery',
      '动画': 'Animation',
      '战争': 'War',
      '剧情': 'Drama',
    },
    region: {
      '中国大陆': 'Mainland China',
      '美国': 'United States',
      '日本': 'Japan',
      '韩国': 'South Korea',
      '欧洲': 'Europe',
      '印度': 'India',
      '泰国': 'Thailand',
    },
  },
}

export default messages

/**
 * SameShape<A> 工具类型 — 强制 B 与 A 的 key 形状完全一致。
 * - 每个顶层 key 必须存在
 * - 子对象的 key 也必须完全匹配(递归)
 * - 值类型放宽为 string(允许不同 locale 写不同字面量)
 *
 * 任何 en-US 漏 key → tsc 报错 → i18n ticket 卡住,直到补齐。
 */
type SameShape<A> = {
  [K in keyof A]: A[K] extends Record<string, unknown>
    ? SameShape<A[K]>
    : string
}