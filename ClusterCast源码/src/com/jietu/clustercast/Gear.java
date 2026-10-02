package com.jietu.clustercast;

import android.content.Context;

/**
 * 读真实挂挡（P/R/N/D）：走车机 CarService 的 GEAR_SELECTION 属性，全程反射，
 * 不需要任何权限声明（CarService 是车机自带服务）。这台 ROM 没有这套服务、
 * 或 CarService 还没连上时如实返回 null，界面显示「挡位 --」，绝不瞎猜。
 */
public final class Gear {

    private Gear() { }

    /** VehiclePropertyIds.GEAR_SELECTION（AOSP 常量，各车 ROM 通用）。 */
    private static final int GEAR_SELECTION = 287310600;

    /** ROM 上根本没有 android.car（Class.forName 都过不去），记下来不再重试。 */
    private static boolean sNoCar = false;
    private static Object sMgr = null;

    /** 返回 "P"/"R"/"N"/"D"，现在拿不到返回 null（调用方下个刷新周期再试）。 */
    public static String get(Context c) {
        if (sNoCar) return null;
        try {
            if (sMgr == null) {
                Class<?> carCls = Class.forName("android.car.Car");
                Object car = carCls.getMethod("createCar", Context.class).invoke(null, c);
                if (car == null) return null;
                sMgr = carCls.getMethod("getCarManager", String.class)
                        .invoke(car, "car_property");
                if (sMgr == null) { sNoCar = true; return null; }
            }
            Object v = sMgr.getClass()
                    .getMethod("getProperty", int.class, int.class)
                    .invoke(sMgr, GEAR_SELECTION, 0);
            if (v == null) return null;
            Object val = v.getClass().getMethod("getValue").invoke(v);
            if (!(val instanceof Integer)) return null;
            return letter((Integer) val);
        } catch (Throwable t) {
            // CarNotConnectedException 之类都算「此刻拿不到」，服务连上下个周期就有
            return null;
        }
    }

    /** VehicleGear：PARK=1 REVERSE=2 NEUTRAL=4 DRIVE=8，按位掩码兼容组合值。 */
    private static String letter(int v) {
        if ((v & 1) != 0) return "P";
        if ((v & 2) != 0) return "R";
        if ((v & 4) != 0) return "N";
        if ((v & 8) != 0) return "D";
        return null;
    }
}
