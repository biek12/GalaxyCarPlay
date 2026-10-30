package com.galaxy.auto.contract;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
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
}
