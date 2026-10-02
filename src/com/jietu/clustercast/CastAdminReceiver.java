package com.jietu.clustercast;

import android.app.admin.DeviceAdminReceiver;

/**
 * 设备所有者（Device Owner）挂名接收器。
 * 一次性授权：adb shell dpm set-device-owner com.jietu.clustercast/.CastAdminReceiver
 * 有了它 DevicePolicyManager.setApplicationHidden 才允许隐藏/恢复原车高德 ——
 * 普通 setApplicationEnabledSetting 对第三方应用是 signature|privileged 权限，
 * 永远被拒（实车已验证）。不声明任何管理策略，随时可由应用自己 clearDeviceOwnerApp 解除。
 */
public class CastAdminReceiver extends DeviceAdminReceiver {
}
