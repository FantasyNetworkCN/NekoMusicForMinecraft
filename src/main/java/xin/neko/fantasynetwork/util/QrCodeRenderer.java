package xin.neko.fantasynetwork.util;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

import java.util.EnumMap;
import java.util.Map;

/**
 * 把文本编码成二维码点阵，交给界面自己按像素画出来（不需要贴图资源）。
 *
 * <p>纠错级别用 M：扫码登录链接很短，M 级足够抗手机拍摄的畸变与反光。
 */
public final class QrCodeRenderer {

    private QrCodeRenderer() {
    }

    public static QrMatrix encode(String content) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 0); // 静默区由界面自己留，保证留白是整数个模块

        QRCode code;
        try {
            code = Encoder.encode(content, ErrorCorrectionLevel.M, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("二维码编码失败：" + e.getMessage(), e);
        }

        ByteMatrix matrix = code.getMatrix();
        int size = matrix.getWidth();
        boolean[] modules = new boolean[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                modules[y * size + x] = matrix.get(x, y) == 1;
            }
        }
        return new QrMatrix(size, modules);
    }
}
