/* 自有现场探针。认证材料只在车机内读取并交给既有QnxTelnet，不输出或外传。
 * 默认只读清单；显式命令由既有个人远程授权入口提交，不持久化命令正文。
 */
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;
public final class GalaxyQnxProbe {
    public static void main(String[] args) {
        Object channel = null;
        String phase = "credentials";
        try {
            if (android.os.Process.myUid() != 0) throw new IllegalStateException("root_required");
            JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(
                "/data/user/0/com.ecarx.autoservice/no_backup/qnx-login.json")), "UTF-8"));
            phase = "connect";
            Class<?> type = Class.forName("com.ecarx.autoservice.platform.QnxTelnet");
            channel = type.getConstructor(String.class, int.class).newInstance("192.168.118.2", 23);
            phase = "login_uid";
            type.getMethod("login", String.class, String.class).invoke(channel,
                config.getString("username"), config.getString("password"));
            config = null;
            phase = "inventory";
            String command = "uname -s; pidin -p $$ user; cksum /apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi; " +
                "ls -l /apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi; " +
                "pidin ar | grep '[c]luster-hmi'; ls -ld /shared/images; echo KANZI_INVENTORY_COMPLETE";
            if (args.length != 0) {
                if (args.length != 2 || !"--command-base64".equals(args[0])) throw new IllegalArgumentException("arguments");
                byte[] bytes = android.util.Base64.decode(args[1], android.util.Base64.NO_WRAP);
                if (bytes.length == 0 || bytes.length > 8192) throw new IllegalArgumentException("command_size");
                command = new String(bytes, "UTF-8");
            }
            String result = (String) type.getMethod("execute", String.class).invoke(channel, command);
            System.out.println("QNX_UID_VERIFIED=0");
            System.out.println(result);
        } catch (Throwable e) {
            while (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) e=e.getCause();
            System.out.println("QNX_PROBE_FAILED phase=" + phase + " type=" + e.getClass().getSimpleName());
            System.exit(2);
        } finally {
            if (channel != null) try { channel.getClass().getMethod("close").invoke(channel); } catch (Throwable ignored) {}
        }
    }
}
