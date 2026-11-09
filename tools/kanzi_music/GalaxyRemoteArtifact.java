/* 现场自有工件接收器：复用AutoService的HTTPS身份；不输出、复制或拼接认证材料到Shell。 */
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public final class GalaxyRemoteArtifact {
    public static void main(String[] args) {
        Object http = null;
        String phase = "arguments";
        File file = null;
        try {
            if (android.os.Process.myUid() != 0 || args.length != 3 ||
                !args[0].matches("[a-f0-9]{32,64}") || !args[1].matches("[a-f0-9]{64}") ||
                !args[2].matches("carplay\\.apk|cluster-hmi-candidate|libgalaxy_kanzi_music\\.so"))
                throw new IllegalArgumentException("arguments");
            phase = "context";
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object instance = thread.getMethod("systemMain").invoke(null);
            Context system = (Context)thread.getMethod("getSystemContext").invoke(instance);
            Context context = system.createPackageContext("com.ecarx.autoservice", Context.CONTEXT_IGNORE_SECURITY);
            phase = "download";
            Class<?> settings = Class.forName("com.ecarx.autoservice.remote.RemoteSettings");
            Object state = settings.getConstructor(Context.class).newInstance(context);
            if (!((Boolean)settings.getMethod("getEnabled").invoke(state))) throw new IllegalStateException("expired");
            Class<?> remote = Class.forName("com.ecarx.autoservice.remote.RemoteHttp");
            http = remote.getConstructor(Context.class, settings).newInstance(context, state);
            File directory = new File("/data/local/tmp/galaxy-kanzi-field");
            directory.mkdirs();
            android.system.Os.chmod(directory.getPath(), 0700);
            file = new File(directory, args[2]);
            remote.getMethod("download", String.class, File.class).invoke(http, args[0], file);
            phase = "digest";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count=input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format("%02x", value & 255));
            if (!actual.toString().equals(args[1])) throw new IllegalStateException("digest");
            android.system.Os.chmod(file.getPath(), 0600);
            System.out.println("ARTIFACT_VERIFIED bytes=" + file.length() + " sha256=" + actual);
        } catch (Throwable error) {
            if (file != null) file.delete();
            while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause()!=null) error=error.getCause();
            System.out.println("ARTIFACT_FAILED phase=" + phase + " type=" + error.getClass().getSimpleName());
            System.exit(2);
        } finally {
            if (http != null) try { http.getClass().getMethod("close").invoke(http); } catch (Throwable ignored) {}
        }
        // systemMain初始化的Binder/框架线程不属于常驻服务，工件校验后结束本次自有进程。
        System.exit(0);
    }
}
