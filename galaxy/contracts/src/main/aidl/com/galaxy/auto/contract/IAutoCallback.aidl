package com.galaxy.auto.contract;
import android.os.Bundle;
import android.view.Surface;
/** 单向回调：调用方须在自己的worker消费，并核对连接代次。 */
oneway interface IAutoCallback {
    void onSnapshot(in Bundle snapshot);
    void onSurface(String role, in Surface surface);
    void onProjectionState(String role, String state);
    /** 按需车辆信号：顶层为订阅键各自的有效值Bundle，另有at/sequence；未出现的键本轮无值。 */
    void onVehicleFeed(in Bundle values);
}
