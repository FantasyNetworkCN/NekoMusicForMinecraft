package xin.neko.fantasynetwork.util;

/**
 * 二维码点阵（不含静默区，坐标 0 起始，左上角为原点）。
 *
 * @param size    边长（模块数），二维码固定为正方形
 * @param modules 长度 {@code size * size} 的一维数组，{@code true} 表示深色模块
 */
public record QrMatrix(int size, boolean[] modules) {

    public QrMatrix {
        if (size <= 0 || modules.length != size * size) {
            throw new IllegalArgumentException("二维码点阵尺寸不合法");
        }
    }

    public boolean isDark(int x, int y) {
        return modules[y * size + x];
    }
}
