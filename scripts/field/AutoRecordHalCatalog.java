import dalvik.system.PathClassLoader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** 本车VHAL只读核对；默认只列配置，--read只读清单内已支持的踏板/转角。无订阅或写接口。 */
public final class AutoRecordHalCatalog {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new PathClassLoader(
            "/system/priv-app/ECarXCarService/ECarXCarService.apk",
            AutoRecordHalCatalog.class.getClassLoader());
        Class<?> api = Class.forName("android.hardware.automotive.vehicle.V2_0.IVehicle", true, loader);
        Object vehicle = api.getMethod("getService", String.class, boolean.class).invoke(null, "default", false);
        if (vehicle == null) throw new IllegalStateException("vehicle_service_unavailable");
        Map<Integer, String> names = new HashMap<>();
        for (String type : new String[] {
            "android.hardware.automotive.vehicle.V2_0.VehicleProperty",
            "vendor.ecarx.xma.automotive.vehicle.V1_0.VendorVehicleProperty"}) {
            for (Field field : Class.forName(type, true, loader).getFields()) {
                if (field.getType() == int.class) names.put(field.getInt(null), field.getName());
            }
        }
        List<?> configs = (List<?>) api.getMethod("getAllPropConfigs").invoke(vehicle);
        System.out.println("CONFIG_COUNT=" + configs.size());
        int[] expected = {559947208, 557855126, 559971591, 557849839, 291504649};
        for (int id : expected) {
            boolean found = false;
            for (Object config : configs) {
                if (config.getClass().getField("prop").getInt(config) == id) {
                    found = true;
                    System.out.println("CONFIG_DETAILS " + config);
                }
            }
            System.out.println("CONFIG_PRESENT " + id + "=" + found);
        }
        for (Object config : configs) {
            int id = config.getClass().getField("prop").getInt(config);
            String name = names.getOrDefault(id, "");
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("pedl") || lower.contains("pedal") || lower.contains("brkped") ||
                lower.contains("accrped") || lower.contains("steerwhlsnsrag") ||
                lower.contains("steering_angle")) {
                System.out.println("CONFIG " + id + " " + name + " access=" +
                    config.getClass().getField("access").getInt(config));
            }
        }
        if (args.length == 1 && "--read".equals(args[0])) {
            Class<?> valueType = Class.forName("android.hardware.automotive.vehicle.V2_0.VehiclePropValue", true, loader);
            Class<?> callbackType = Class.forName("android.hardware.automotive.vehicle.V2_0.IVehicle$getCallback", true, loader);
            for (int id : expected) {
                for (Object config : configs) {
                    if (config.getClass().getField("prop").getInt(config) != id ||
                        (config.getClass().getField("access").getInt(config) & 1) == 0) continue;
                    Object request = valueType.getConstructor().newInstance();
                    valueType.getField("prop").setInt(request, id);
                    valueType.getField("areaId").setInt(request, 0);
                    Object callback = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {callbackType},
                        (proxy, method, values) -> {
                            if (!"onValues".equals(method.getName())) return null;
                            Object value = values[1];
                            Object raw = valueType.getField("value").get(value);
                            List<?> bytes = (List<?>) raw.getClass().getField("bytes").get(raw);
                            String packet = "";
                            if (bytes.size() == 16) {
                                byte[] data = new byte[16];
                                for (int index = 0; index < 16; index++) data[index] = ((Number) bytes.get(index)).byteValue();
                                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                                packet = " paAvailability=" + buffer.getInt() + " paData=" + buffer.getInt() +
                                    " paFormat=" + buffer.getInt() + " paStatus=" + buffer.getInt();
                            }
                            System.out.println("VALUE " + id + " result=" + values[0] + " status=" +
                                valueType.getField("status").getInt(value) + " timestamp=" +
                                valueType.getField("timestamp").getLong(value) + " floats=" +
                                raw.getClass().getField("floatValues").get(raw) + " ints=" +
                                raw.getClass().getField("int32Values").get(raw) + " bytesLength=" + bytes.size() + packet);
                            return null;
                        });
                    api.getMethod("get", valueType, callbackType).invoke(vehicle, request, callback);
                }
            }
        }
    }
}
