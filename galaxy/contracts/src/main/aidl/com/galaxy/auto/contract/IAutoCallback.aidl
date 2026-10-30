package com.galaxy.auto.contract;
import android.os.Bundle;
import android.view.Surface;
/** 单向回调：调用方须在自己的worker消费，并核对连接代次。 */
oneway interface IAutoCallback {
    void onSnapshot(in Bundle snapshot);
    void onSurface(String role, in Surface surface);
    void onProjectionState(String role, String state);
}
