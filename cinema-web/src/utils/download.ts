/**
 * 触发浏览器下载一个 Blob (T5).
 *
 * <p>用隐藏 `<a download>` 触发原生下载; 必须 {@code revokeObjectURL} 清理, 放 finally 兜底.
 *
 * <p>不复用 src/api/request.ts: 全局响应拦截器会把非 0 业务码自动 ElMessage.error,
 * 且全局 {@code if ('code' in body)} 会把对象型 body 当 JSON 解.
 * 调用方需自建 axios 实例 (responseType=blob) 后用此工具触发下载.
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