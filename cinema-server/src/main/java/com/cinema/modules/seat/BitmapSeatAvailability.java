package com.cinema.modules.seat;

import com.cinema.modules.seat.vo.SeatMapVO;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 基于双位图快照的 {@link SeatAvailability} 实现。
 *
 * <p>构造时一次性解码两张位图并算出可选/被锁/已售三个计数, 之后的查询都是 O(1) 内存读。
 *
 * <p><b>本类是纯搬迁</b>: 每个方法的判定口径都逐条对齐搬迁前的实现,
 * 没有做任何「顺手修正」。凡是看起来不合理的口径(例如最长连座跨行不切断),
 * 都是为了保持模型可见输出不变而刻意保留的, 详见 {@link SeatAvailability} 的类注释。
 */
final class BitmapSeatAvailability implements SeatAvailability {

    private final int rows;
    private final int cols;
    private final int seatCount;
    private final byte[] lockBitmap;
    private final byte[] soldBitmap;

    /** 每个座位是否可选;越界位置视为不可选。 */
    private final boolean[] available;

    private final int availableCount;
    private final int lockedCount;
    private final int soldCount;

    private BitmapSeatAvailability(int rows, int cols, int seatCount,
                                   byte[] lockBitmap, byte[] soldBitmap) {
        this.rows = rows;
        this.cols = cols;
        this.seatCount = seatCount;
        this.lockBitmap = lockBitmap;
        this.soldBitmap = soldBitmap;
        this.available = new boolean[Math.max(0, seatCount)];

        int avail = 0;
        int locked = 0;
        int sold = 0;
        for (int i = 0; i < seatCount; i++) {
            boolean isSold = bitSet(soldBitmap, i);
            boolean isLock = bitSet(lockBitmap, i);
            // sold 优先 —— 与原 getSeatSummary 的 if / else-if 分支同口径
            if (isSold) {
                sold++;
            } else if (isLock) {
                locked++;
            } else {
                avail++;
            }
            this.available[i] = !isSold && !isLock;
        }
        this.availableCount = avail;
        this.lockedCount = locked;
        this.soldCount = sold;
    }

    static BitmapSeatAvailability of(SeatMapVO vo) {
        int seatCount = Math.max(0, vo.getSeatCount());
        int cols = vo.getCols() > 0 ? vo.getCols() : 1;
        int rows = vo.getRows() > 0 ? vo.getRows() : 1;
        return new BitmapSeatAvailability(rows, cols, seatCount,
                decode(vo.getLockBitmap()), decode(vo.getSoldBitmap()));
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(base64);
    }

    @Override
    public boolean isAvailable(int seatIndex) {
        if (seatIndex < 0 || seatIndex >= seatCount) {
            return false;
        }
        return available[seatIndex];
    }

    @Override
    public int availableCount() {
        return availableCount;
    }

    @Override
    public int lockedCount() {
        return lockedCount;
    }

    @Override
    public int soldCount() {
        return soldCount;
    }

    @Override
    public Run longestAvailableRun() {
        int bestStart = -1;
        int bestLen = 0;
        int curStart = -1;
        int curLen = 0;
        for (int i = 0; i < seatCount; i++) {
            if (available[i]) {
                if (curStart == -1) {
                    curStart = i;
                }
                curLen++;
                continue;
            }
            // 严格大于 → 同长时先出现的那段胜出(与原实现一致)
            if (curLen > bestLen) {
                bestStart = curStart;
                bestLen = curLen;
            }
            curLen = 0;
            curStart = -1;
        }
        if (curLen > bestLen) {
            bestStart = curStart;
            bestLen = curLen;
        }
        return new Run(bestStart, bestLen);
    }

    @Override
    public List<Integer> findRun(int count, RunPolicy policy) {
        return findRun(count, policy, null);
    }

    @Override
    public List<Integer> findRun(int count, RunPolicy policy, Integer preferRow) {
        if (count < 1) {
            return List.of();
        }
        return switch (policy) {
            case FIRST_ROW -> findFirstRow(count, preferRow);
            case CENTERED -> findCentered(count);
        };
    }

    /**
     * 从低索引开始扫, 撞到第一个够长的连座就返回。
     *
     * <p>与原 {@code ChatTools#findContiguousSeats} 的差别只有一处:
     * 原实现用 {@code List.contains} 做 O(n) 查找, 这里直接读 {@code available[]};
     * 判定顺序与返回内容完全一致。
     */
    private List<Integer> findFirstRow(int count, Integer preferRow) {
        for (int idx = 0; idx < seatCount; idx++) {
            if (!available[idx]) {
                continue;
            }
            int row = idx / cols;
            int col = idx % cols;
            List<Integer> candidate = new ArrayList<>(count);
            candidate.add(idx);
            for (int nextCol = col + 1; nextCol < cols && candidate.size() < count; nextCol++) {
                int nextIdx = row * cols + nextCol;
                if (nextIdx < seatCount && available[nextIdx]) {
                    candidate.add(nextIdx);
                } else {
                    break;
                }
            }
            if (candidate.size() == count && (preferRow == null || preferRow == row)) {
                return candidate;
            }
        }
        return List.of();
    }

    /**
     * 取最靠近中心的一组连座。
     *
     * <p>与原 {@code OrderProposalService#pickCenteredRun} 逐条对齐:
     * 行距按列数加权, 同分取先出现的那一组, 末行按 {@code seatCount} 截短。
     */
    private List<Integer> findCentered(int count) {
        int centerRow = (rows - 1) / 2;
        int centerCol = (cols - 1) / 2;
        int bestScore = Integer.MAX_VALUE;
        List<Integer> best = null;

        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            if (base >= seatCount) {
                break;
            }
            int rowCols = Math.min(cols, seatCount - base);
            int c = 0;
            while (c < rowCols) {
                if (!available[base + c]) {
                    c++;
                    continue;
                }
                int start = c;
                while (c < rowCols && available[base + c]) {
                    c++;
                }
                int runEnd = c; // exclusive
                for (int s = start; s + count <= runEnd; s++) {
                    int mid = s + count / 2;
                    int score = Math.abs(r - centerRow) * cols + Math.abs(mid - centerCol);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new ArrayList<>(count);
                        for (int k = 0; k < count; k++) {
                            best.add(base + s + k);
                        }
                    }
                }
            }
        }
        return best == null ? List.of() : best;
    }

    @Override
    public boolean isSameRowContiguous(List<Integer> seatIndexes) {
        for (int i = 1; i < seatIndexes.size(); i++) {
            int prev = seatIndexes.get(i - 1);
            int curr = seatIndexes.get(i);
            if (prev / cols != curr / cols || curr != prev + 1) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int rowOf(int seatIndex) {
        return seatIndex / cols;
    }

    @Override
    public int colOf(int seatIndex) {
        return seatIndex % cols;
    }

    /**
     * 位取位 —— 与前端 {@code decodeBitmap} 同一套位序(字节内高位在前)。
     *
     * <p>用算术右移 + {@code & 1} 取最低位, 与有符号 byte 的符号扩展无关。
     */
    private static boolean bitSet(byte[] bitmap, int index) {
        int byteIdx = index >>> 3;
        if (byteIdx >= bitmap.length) {
            return false;
        }
        int bitInByte = 7 - (index & 7);
        return ((bitmap[byteIdx] >> bitInByte) & 1) == 1;
    }
}
