package com.galaxy.auto.contract;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import com.galaxy.auto.contract.IAutoCallback;
import com.galaxy.auto.contract.IAutoResult;
/** v1：小状态走Bundle，画面走独占Surface，封面走只读FD；Shell另需ADMIN签名权限；没有车辆写接口。 */
interface IAutoService {
    int getProtocolVersion();
    Bundle getSnapshot();
    Bundle getVehicleCatalog(int offset, int limit);
    Bundle getVehicleProperties(int offset, int limit);
    Bundle getVehicleValue(int propertyId, int areaId, int characterOffset);
    void attach(IBinder owner, String role, IAutoCallback callback);
    void detach(IBinder owner);
    void setActive(IBinder owner, boolean active);
    void publishMedia(IBinder owner, in Bundle media);
    void publishNavigation(IBinder owner, in Bundle navigation);
    void publishArtwork(IBinder owner, in ParcelFileDescriptor artwork);
    void executeShell(IBinder owner, String transport, String command, int timeoutMillis, IAutoResult result);
    void mediaCommand(IBinder owner, int command);
    void configureScene(IBinder owner, in Bundle scene);
    void setPreviewSurface(IBinder owner, in Surface surface);
    void setProjectionPolicy(IBinder owner, in Bundle policy);
    /**
     * 订阅语义车辆信号；空数组取消。键必须来自当前车型目录，未知键拒绝。
     * minIntervalMillis 为该租约的最小推送间隔：采集端按它决定读取与推送节拍，
     * 无人订阅的键不读取；多个租约订阅同一键时取最小间隔。
     */
    void setVehicleFeed(IBinder owner, in String[] keys, int minIntervalMillis);
    /** 直接读服务端已采集的缓存（不触发车机读取）；未订阅或无有效值的键不出现。 */
    Bundle getVehicleValues(in String[] keys);
    /** 显式全目录诊断扫描（默认关）；只允许本包调用，用于保留“读尽读”能力。 */
    void setVehicleScan(boolean enabled);
    /** 追加式接口：只为调用者自身申请明确允许的权限，完成后回调实际授权结果。 */
    void requestClientPermissions(IBinder owner, in String[] permissions, IAutoResult result);
}
