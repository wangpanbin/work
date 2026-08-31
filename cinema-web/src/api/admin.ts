import request from './request'

/** O3 经营看板汇总 */
export interface DashboardSummary {
  todayRevenue: number
  todayOrders: number
  todayPaid: number
  todayPendingSeats: number
  todayCancelled: number
  todayRefunded: number
  weeklyTrend: Array<{ date: string; amount: number }>
  topMovies: Array<{ title: string; revenue: number; orders: number }>
  topSessions: Array<{
    sessionId: number
    movieTitle: string
    hallName: string
    startTime: string
    occupancyRate: number
  }>
}

export function dashboardSummary(): Promise<DashboardSummary> {
  return request.get('/admin/dashboard/summary') as Promise<DashboardSummary>
}
