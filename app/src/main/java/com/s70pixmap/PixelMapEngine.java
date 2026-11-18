package com.s70pixmap;

/**
 * 坏点检测与修复算法。
 *
 * <p>纯 Java 实现，不引用任何 Android API，方便在桌面 JVM 上单独测试。
 * 所有像素缓冲区都是 ARGB_8888 的 int 数组（和 Bitmap.getPixels 一致）。
 *
 * <p>检测思路：对每张暗帧，逐通道计算每个像素"周围 8 个邻居的均值"，
 * 若该像素比邻居均值高出 threshold 灰阶，就记一次命中；多张暗帧中命中
 * 次数达到 minHits 的像素判为坏点。用积分行的方式实现，复杂度 O(W*H)。
 */
public final class PixelMapEngine {

    /** 默认判定阈值（灰阶，0-255）。 */
    public static final int DEFAULT_THRESHOLD = 30;
    /** 默认要求命中的暗帧比例。 */
    public static final double DEFAULT_COVERAGE = 0.6;

    private PixelMapEngine() {
    }

    /** 为 width*height 的图像分配命中计数数组。 */
    public static short[] newCounts(int width, int height) {
        return new short[width * height];
    }

    /**
     * 把一张暗帧的检测结果累加进 counts。
     *
     * @param argb      暗帧像素（ARGB_8888）
     * @param width     宽
     * @param height    高
     * @param threshold 高于邻居多少灰阶算热点
     * @param counts    命中计数，长度需 >= width*height
     */
    public static void accumulate(int[] argb, int width, int height, int threshold, short[] counts) {
        if (argb == null || argb.length < width * height) {
            throw new IllegalArgumentException("像素缓冲区太小");
        }
        if (counts == null || counts.length < width * height) {
            throw new IllegalArgumentException("计数缓冲区太小");
        }

        boolean[] hot = new boolean[width * height];
        int[] rowUp = new int[width];
        int[] rowMid = new int[width];
        int[] rowDown = new int[width];

        for (int channel = 0; channel < 3; channel++) {
            int shift = channel == 0 ? 16 : (channel == 1 ? 8 : 0);
            for (int y = 0; y < height; y++) {
                horizontal(argb, width, height, y, shift, rowMid);
                if (y > 0) {
                    horizontal(argb, width, height, y - 1, shift, rowUp);
                } else {
                    System.arraycopy(rowMid, 0, rowUp, 0, width);
                }
                if (y < height - 1) {
                    horizontal(argb, width, height, y + 1, shift, rowDown);
                } else {
                    System.arraycopy(rowMid, 0, rowDown, 0, width);
                }

                int base = y * width;
                long limit = (long) threshold * 8L;
                for (int x = 0; x < width; x++) {
                    int center = (argb[base + x] >> shift) & 0xFF;
                    int sum8 = rowUp[x] + rowMid[x] + rowDown[x] - center;
                    // diff = 8 * (center - 8邻居均值)
                    long diff = (long) center * 8L - sum8;
                    if (diff > limit) {
                        hot[base + x] = true;
                    }
                }
            }
        }

        for (int i = 0; i < hot.length; i++) {
            if (hot[i] && counts[i] < Short.MAX_VALUE) {
                counts[i]++;
            }
        }
    }

    /** 计算某行某通道的横向 3 邻域和（边界钳制）。 */
    private static void horizontal(int[] argb, int width, int height, int y, int shift, int[] out) {
        int base = y * width;
        for (int x = 0; x < width; x++) {
            int xm = x > 0 ? x - 1 : 0;
            int xp = x < width - 1 ? x + 1 : width - 1;
            out[x] = ((argb[base + xm] >> shift) & 0xFF)
                    + ((argb[base + x] >> shift) & 0xFF)
                    + ((argb[base + xp] >> shift) & 0xFF);
        }
    }

    /** 根据计数生成坏点坐标（x0,y0,x1,y1,...），可先做膨胀。 */
    public static int[] pointsFromCounts(short[] counts, int width, int height, int minHits, int dilate) {
        boolean[] mask = maskFromCounts(counts, width, height, minHits);
        if (dilate > 0) {
            mask = dilate(mask, width, height, dilate);
        }
        return pointsFromMask(mask, width, height);
    }

    /** 计数 >= minHits 的像素置 true。 */
    public static boolean[] maskFromCounts(short[] counts, int width, int height, int minHits) {
        int n = width * height;
        boolean[] mask = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (counts[i] >= minHits) {
                mask[i] = true;
            }
        }
        return mask;
    }

    /** 由坐标数组构造掩膜（越界坐标忽略）。 */
    public static boolean[] maskFromPoints(int[] points, int width, int height, int dilate) {
        boolean[] mask = new boolean[width * height];
        if (points != null) {
            for (int i = 0; i + 1 < points.length; i += 2) {
                int x = points[i];
                int y = points[i + 1];
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    mask[y * width + x] = true;
                }
            }
        }
        if (dilate > 0) {
            mask = dilate(mask, width, height, dilate);
        }
        return mask;
    }

    /** 把掩膜中为 true 的像素写成坐标数组。 */
    public static int[] pointsFromMask(boolean[] mask, int width, int height) {
        int count = 0;
        for (boolean b : mask) {
            if (b) count++;
        }
        int[] points = new int[count * 2];
        int k = 0;
        for (int y = 0; y < height; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                if (mask[base + x]) {
                    points[k++] = x;
                    points[k++] = y;
                }
            }
        }
        return points;
    }

    /** 掩膜膨胀（方形结构元），用于抵消 JPEG 把亮点晕开的 1-2 像素。 */
    public static boolean[] dilate(boolean[] mask, int width, int height, int radius) {
        if (radius <= 0) return mask;
        boolean[] out = new boolean[mask.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!mask[y * width + x]) continue;
                int y0 = Math.max(0, y - radius);
                int y1 = Math.min(height - 1, y + radius);
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);
                for (int yy = y0; yy <= y1; yy++) {
                    int base = yy * width;
                    for (int xx = x0; xx <= x1; xx++) {
                        out[base + xx] = true;
                    }
                }
            }
        }
        return out;
    }

    /**
     * 用周围 8 个邻居的中位数替换掩膜内的像素，原地修改 argb。
     *
     * @return 修复的像素个数
     */
    public static int repair(int[] argb, int width, int height, boolean[] mask) {
        int fixed = 0;
        int[] buf = new int[8];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                int idx = base + x;
                if (!mask[idx]) continue;
                int r = medianNeighbour(argb, width, height, x, y, 16, buf);
                int g = medianNeighbour(argb, width, height, x, y, 8, buf);
                int b = medianNeighbour(argb, width, height, x, y, 0, buf);
                int alpha = (argb[idx] >>> 24) & 0xFF;
                argb[idx] = (alpha << 24) | (r << 16) | (g << 8) | b;
                fixed++;
            }
        }
        return fixed;
    }

    /** 取 8 邻居某通道的中位数。 */
    private static int medianNeighbour(int[] argb, int width, int height, int x, int y, int shift, int[] buf) {
        int k = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (yy < 0) yy = 0;
            if (yy >= height) yy = height - 1;
            int base = yy * width;
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int xx = x + dx;
                if (xx < 0) xx = 0;
                if (xx >= width) xx = width - 1;
                buf[k++] = (argb[base + xx] >> shift) & 0xFF;
            }
        }
        // 8 个元素的插入排序，取中间两个的平均
        for (int i = 1; i < k; i++) {
            int v = buf[i];
            int j = i - 1;
            while (j >= 0 && buf[j] > v) {
                buf[j + 1] = buf[j];
                j--;
            }
            buf[j + 1] = v;
        }
        return (buf[3] + buf[4]) / 2;
    }
}
