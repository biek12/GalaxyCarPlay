import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.FileOutputStream;

/** 一次性现场截图缩放器；不装 APK，不常驻，不访问车辆数据，不上传截图。 */
public final class AutoRecordScreenshot {
    public static void main(String[] args) throws Exception {
        Bitmap source = BitmapFactory.decodeFile("/data/local/tmp/autorecord-screen.png");
        if (source == null) throw new IllegalStateException("screenshot_decode_failed");
        Bitmap scaled = Bitmap.createScaledBitmap(source, 720,
                Math.max(1, source.getHeight() * 720 / source.getWidth()), true);
        try (FileOutputStream output = new FileOutputStream("/data/local/tmp/autorecord-screen.jpg")) {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, output))
                throw new IllegalStateException("screenshot_encode_failed");
        } finally {
            if (scaled != source) scaled.recycle();
            source.recycle();
        }
    }
}
