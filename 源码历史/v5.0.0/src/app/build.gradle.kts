plugins {
    alias(libs.plugins.android.application)
}

android {
    // 单 APK：全部代码都在 com.ahui.clustercast 一个包里，纯 Java，无第三方依赖。
    namespace = "com.ahui.clustercast"
    compileSdk = 36

    // 签名：ClusterCast 自己的证书（SHA-1 8994…574a），老用户覆盖升级直接装。
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/clustercast.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.ahui.clustercast"
        // 这台车是 Android 11（API 30）、无 root。target 定在 28 是实机验证过的行为面：
        // 悬浮窗、后台起 Activity、WRITE_SECURE_SETTINGS 那套都按老规则走。
        minSdk = 26
        targetSdk = 28
        versionCode = 43
        versionName = "5.0.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // 车机侧载应用，不走 Google Play；targetSdk=28 是实机验证过的行为面
        // （悬浮窗、后台起 Activity、写 secure setting 都按老规则走），这条 lint 关掉。
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // 投屏 + 车控 + 四路记录仪全部走 framework API（Camera2 / GLES20 / MediaCodec /
    // VDBus 反射），一个第三方库都不引。
}
