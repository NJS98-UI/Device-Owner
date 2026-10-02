# 捷途 T1J 仪表投屏（ClusterCast）

把第三方应用投到捷途 T1J 的仪表屏（display 2，1920x720），**运行期不需要 root、不需要 Shizuku、不需要无障碍**。

适用：捷途 T1J / 德赛西威 DesaySV 固件 / Chery-8155 / Android 11。
当前版本 **v4.1.4（versionCode 25）**，Kotlin 那条线；v3.2.2（Java）源码保留作对照。

![build-apk](https://github.com/NJS98-UI/yibiao/workflows/build-apk/badge.svg) `.github/workflows/build-apk.yml`
会在 push / PR / 手动触发时构建出**同一个签名的 APK** 并跑 dex 完整性自检；构建不碰车机，真机步骤见安装方法。

## 先看这几份

| 文档 | 内容 |
|---|---|
| [源码/安装方法.md](源码/安装方法.md) | 装机、授权、逐项验证（含本版新增的两个开关怎么验） |
| [协议说明.md](协议说明.md) | 三指手势广播、仪表模式 cmdId 232、桌面音乐卡片 `VDMediaInfo` |
| [协议说明-空调与座椅.md](协议说明-空调与座椅.md) | eventId 327690 / 327681 全套空调与座椅 cmdId，含取值编码 |
| [协议说明-车窗尾门后视镜.md](协议说明-车窗尾门后视镜.md) | 车窗 162~165、尾门 92、后视镜 51/201、天窗 327696、档位 327684/26 |
| [抓包数据/README.md](抓包数据/README.md) | 两批原始抓取怎么读，哪条结论对应哪个文件 |

## 怎么用

| 操作 | 结果 |
|---|---|
| 主屏点任意应用 | 只在主屏打开，**绝不自动投屏** |
| 三指左滑 | 仪表切到原车「极简模式」，再把画面铺到仪表屏 |
| 三指右滑 | 收掉页面，仪表**退回进投屏前的模式**（写入后回读确认） |
| 勾「屏蔽弹窗」 | 认出 `android` 包的全屏"管理员不允许"弹窗，把底下的应用顶回前台 |
| 勾「dock 沉浸」 | 写 `policy_control=immersive.full=<自装三方包>`，只对自装应用隐藏 dock |
| 原车高德 | 完全不动，主屏照开，它自己的三指投屏照常 |

## 目录

```
源码/v4.1.3/         当前版本 Kotlin 源码 + build.ps1(Windows) + build_ci.sh(Linux/CI)
                     + ClusterCast-v4.1.4.apk
源码/                v3.2.2 Java 源码 + build_clustercast.ps1 + debug.keystore + 安装方法.md
源码/vehprobe/       车控总线验证包（把上面两份协议里的 cmdId 逐个按一遍）
源码/camprobe/       环视四路摄像头取流验证包
ClusterCast-v3.apk   上一版已签名产物 v3.2.2 (versionCode 12)
抓包数据/            两批实机原始抓取：投屏链路 / 车控总线 / 系统弹窗 / 环视摄像头
原车jar/             vdbus.jar、vdbus_extra.jar（从 /system/framework 拉取）
.github/workflows/   build-apk.yml
```

## 发布附件（超过仓库单文件 100MB 上限的原始材料）

仓库正文只留有分析价值的截取片段，整机包与全量 dexdump 放在
[Releases](../../releases)：

| 附件 | 体积 | 是什么 |
|---|---|---|
| `com.desaysv.setting.apk` | 831MB | 原车设置，「仪表模式」四档弹窗就在这里 |
| `com.desaysv.launcher.apk` | 231MB | 桌面 SVLauncher，`T1hMediaCard` 音乐卡片的渲染方 |
| `com.kugou.music.apk` | 136MB | 酷狗音乐（实测投屏目标） |
| `services-dexdump-part1.txt` | 174MB | `/system/framework/services.jar` 全量 dexdump，PMS 安装限制的证据 |
| `services-dexdump-part2.txt` | 51MB | 同上 classes2.dex 部分 |
| `legacy-webview.apk` | 15MB | 排查"管理员不允许"弹窗时拉出的系统 webkit 包，留作现场记录 |
| `stock-apks-20260919.tar` | 481MB | 第二批：SVHvac / SVPersonalizedSetting / KanziCarModel / DVR / IQYVideo 等原车系统 APK 原件 |
| `stock-dexdumps-20260919.tar` | 67MB | 第二批：上述包的全量 dexdump（已 gzip，623MB 原始） |
| `frames-20260919.tar` | 62MB | 第二批：摄像头逐帧截图序列（cover/ 与 lrseq/） |
| `clustercast-delivery-20260919.zip` | 26MB | 本仓库全部内容的整包压缩 |

## 原理速览

1. **手势**：`system_server` 里的 `CarSystemGesturesManager` 用 `sendBroadcastAsUser` 广播
   `android.intent.action.car_system_gesture_mode`，**不带接收权限**，
   `gesture = 手指数×100 + 方向`（300=三指左滑，301=三指右滑）。
   普通应用注册接收器就能收到——这是免 root 的入口。
2. **投屏**：`ActivityOptions.setLaunchDisplayId(2)`，display 2 没有 `FLAG_PRIVATE`，零权限可用。
   实测 display 2 = 1920x720 且应用区无系统栏，铺上去就是全屏。
3. **仪表模式**：只能走德赛私有 VDBus（CAR_INFO / eventId 327681 / `CMD_ID=232`），
   不在 Settings、不在 prop、也不是广播。取值 1=数字 2=经典 3=导航 4=极简。
   **写入已车机实证**：uid 10018 的普通应用写 232 后回读确认换档成功。
4. **桌面音乐卡片**：`T1hMediaCard` 读 VDBus MEDIA 的 `393218 / VDMediaInfo`，
   而 `MediaType` 枚举里没有酷狗这一档，所以自装音乐只能显示"未知频道"——
   本项目自己 new 一个 `VDMediaInfo` 推上总线，补这个白名单缺口。
5. **"管理员不允许"弹窗**：是 framework 的 `android/.accounts.CantAddAccountActivity`，
   由蓝牙电话本每 20 秒重试加账号撞上 user 0 的 `no_modify_accounts` 基础限制（ROM overlay 默认值）触发，
   与设备管理器无关，普通应用清不掉这个限制，只能把它顶回后台。

## 编译

Windows（当前版本）：

```
powershell -ExecutionPolicy Bypass -File 源码\v4.1.3\build.ps1
powershell -ExecutionPolicy Bypass -File 源码\v4.1.3\build.ps1 -Install   # 编完推上车并拉起安装页
```

Linux / CI：`bash 源码/v4.1.3/build_ci.sh`（环境变量 `BT` / `AJ` / `KC_HOME`，见脚本头）。

需要 Android SDK（build-tools 34.0.0 + platform android-34）、JDK、Kotlin 1.9.20 编译器。
三个已知的坑写在 `源码/安装方法.md` 第四节：aapt2 不吃中文路径（源码先同步到 `%TEMP%` 再构建）；
d8 编不了捕获 `this` 的匿名内部类（全项目只用 lambda 和 `static` 嵌套类）；
**kotlin-stdlib 必须作为 R8 的位置参数传进去**，放 `--classpath` 会被当外部库剔掉，一启动就闪退。

## 装包

这台 ROM 的 `adb install` 被策略挡了（`SecurityException: Restriction prevents installing`），
但正常安装器不拦，所以推文件后用 MT 管理器拉起安装界面：

```
adb push 源码\v4.1.3\ClusterCast-v4.1.4.apk /sdcard/Download/
adb shell am start -n bin.mt.plus/.OpenFileActivity -d file:///sdcard/Download/ClusterCast-v4.1.4.apk -t application/vnd.android.package-archive
```

装完授权一次（`pm grant` 通路在这台 ROM 上是开的，重启不失效）：

```
adb shell pm grant com.ahui.clustercast android.permission.WRITE_SECURE_SETTINGS
```

## 还没定论

- v4.1.4 的两个新开关（屏蔽弹窗 / dock 沉浸）**只在代码与构建层面验证过，没在车上跑过**。
- 我们自己的应用写 162~165 / 175 / 92 / 51 是否被 MCU 接受、行车中 MCU 是否真的拦，
  要用车控验证包（`源码/vehprobe/`）在车上扫一遍才有结论。
- 车机重启后偶发"要先打开 App 才能投屏"，未稳定复现。
