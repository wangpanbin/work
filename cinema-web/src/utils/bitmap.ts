/**
 * 后端返回的 Base64 位图解码工具。
 * 位图每个 bit(0/1)对应一个 seat_index:
 *   byte = seat_index / 8, bit 位 = 7 - (seat_index % 8)  (Redis Big Endian)
 */

/**
 * 解码座位位图。
 *
 * <p><b>位图短于座位数时, 缺失的位按「占用」处理</b>(2026-10-24)。
 * 原先缺失位落进 `result[i] = 0` → 也就是「可选」—— 位图与座位布局一旦错位
 * (影厅改过行列、位图是历史脏数据), 用户就能选到一个其实已经被占的座位。
 * 宁可显示成占着(用户点不动), 也不能显示成可选(用户点了才被服务端拒绝)。
 *
 * <p>正常情况下不会走到这条分支: 后端 {@code SessionBitmapReader.readWithPadding}
 * 保证位图覆盖 seatCount。走到这里说明位图与布局已经不一致, 记一条 warn 便于排查。
 *
 * @throws base64 非法时抛错 —— 那不是「部分座位可选」, 是**整张图不可信**,
 *   交给调用方渲染错误态(而不是让 atob 的异常把整页带崩)。
 */
export function decodeBitmap(b64: string, count: number): Uint8Array {
  let bytes = ''
  if (b64) {
    try {
      bytes = atob(b64)
    } catch (e) {
      throw new Error('座位位图解码失败(base64 非法)')
    }
  }
  const result = new Uint8Array(count)
  const buf = new Uint8Array(bytes.length)
  for (let i = 0; i < bytes.length; i++) buf[i] = bytes.charCodeAt(i)
  if (bytes.length * 8 < count) {
    console.warn(
      `[bitmap] 位图只有 ${bytes.length * 8} 位, 座位数 ${count} —— 缺失的位按「占用」处理`,
    )
  }
  for (let i = 0; i < count; i++) {
    const byte = buf[i >> 3]
    if (byte == null) {
      // 位图没覆盖到这个座位 → 按占用处理(见上方说明)
      result[i] = 1
      continue
    }
    const bit = 7 - (i & 7)
    result[i] = (byte >> bit) & 1 ? 1 : 0
  }
  return result
}
