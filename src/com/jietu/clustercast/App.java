package com.jietu.clustercast;

import android.app.Application;

import java.util.TimeZone;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // 车机进程默认时区是 UTC：录像文件名按默认时区生成会差 8 小时，
        // 回放列表按文件名显示时间就对不上。全进程统一按北京时间。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }
}
