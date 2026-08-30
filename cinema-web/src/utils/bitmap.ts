/**
 * 后端返回的 Base64 位图解码工具。
 * 位图每个 bit(0/1)对应一个 seat_index:
 *   byte = seat_index / 8, bit 位 = 7 - (seat_index % 8)  (Redis Big Endian)
 */
export function decodeBitmap(b64: string, count: number): Uint8Array {
  const bytes = b64 ? atob(b64) : ''
  const result = new Uint8Array(count)
  const buf = new Uint8Array(bytes.length)
  for (let i = 0; i < bytes.length; i++) buf[i] = bytes.charCodeAt(i)
  for (let i = 0; i < count; i++) {
    const byte = buf[i >> 3]
    const bit = 7 - (i & 7)
    result[i] = byte != null && (byte >> bit) & 1 ? 1 : 0
  }
  return result
}