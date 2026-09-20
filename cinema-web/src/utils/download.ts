import axios, { type AxiosInstance } from 'axios'

/**
 * T3 独立 axios 实例 (下载用).
 *
 * <p>不复用 src/api/request.ts:
 * <ol>
 *   <li>全局响应拦截器会把 `code !== 0` 当业务错自动 ElMessage.error + reject, 我们的 blob 路径不应被此污染.</li>
 *   <li>全局 `if ('code' in body)` 会把对象型 body 当 JSON 解, 但 Excel 响应是 Blob 不会进该分支, 仍可工作 — 只是默认 timeout 10s 对下载场景偏短.</li>
 * </ol>
 * 这里手动从 localStorage 抓 token + timeout 加到 30s.
 */
export function createCinemaAxios(): AxiosInstance {
  const instance = axios.create({
    baseURL: '/api',
    timeout: 30000,
  })
  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem('cinema_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })
  // 响应拦截器留空: 调用方按 responseType=blob / json 自己处理
  return instance
}

/**
 * 触发浏览器下载一个 Blob (T3 + T5 共用).
 *
 * <p>用隐藏 `<a download>` 触发原生下载; 必须 revokeObjectURL 清理 (放在 finally 兜底).
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.style.display = 'none'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  } finally {
    URL.revokeObjectURL(url)
  }
}