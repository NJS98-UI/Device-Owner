# 整车 OTA 包解包结论（IHU 00.01.03）—— 仪表到底谁在画

包：`JX52_KD09_NN11_KP02_KB02_IHU_703003767AA_9BJ_0.0.1_00.01.03_3.zip`（5,489,057,154 B）
本地全部为实拆，不是推测。所有偏移量都是文件里的真实字节位置，可复核。

## 0. 外层结构与版本

```
IHU_00.01.03.zip
├── soc.zip   4,886,552,999   → A/B update_engine payload.bin（4,886,549,009 B）
├── mcu.zip     564,826       → radio.bin（2,076,164 B，Intel HEX 文本，非 ELF，挖不出符号）
├── dsp.zip      90,301       → DSPUpgradeFile.tar.gz
├── vr.zip    601,843,612     → 语音资源（voice_data_20251203），与协议无关
└── manifest.txt              → 四个 ECU 的目标版本
```

`soc.zip/META-INF/com/android/metadata` 里明确写了这是 AB 包：

```
pre-device          = msmnile_gvmq          ← Android 是 QNX hypervisor 下的 guest，与实机一致
post-build          = DesaySV/jetour_t1j/msmnile_gvmq:11/RQ3A.210805.001.A1/eng.ici2.20251205.085027
post-sdk-level      = 30                    ← Android 11
ota-type            = AB
```

## 1. payload.bin 的分区表（`payload-dumper-go 2.0.2 -l`）

```
system_qnx 3.2 GB   ifs2 141 MB   bluetooth 684 kB   dsp 67 MB   modem 59 MB
boot 67 MB  dtbo 8.4 MB  vbmeta 8.2 kB  xbl 2.8 MB  xbl_config 45 kB
aop 192 kB  tz 3.2 MB  hyp 2.6 MB  abl 152 kB  keymaster 287 kB
cmnlib 401 kB  cmnlib64 520 kB  devcfg 61 kB  qupfw 74 kB  uefisecapp 115 kB
system 960 MB  vendor 367 MB  system_ext 85 MB  product 5.1 GB
```

**`system_qnx` + `ifs2` 就是仪表面板那一侧的全部。** 这两个分区之前不在任何 Android 取证范围里，
所以"极简模式页面谁在画"在 APK 里永远找不到答案。

## 2. 画仪表的进程：`sva.cluster`（QNX 侧）

`system_qnx.img` 里能直接读到的路径（明文，未加密）：

```
/emmc/sva/bin/sva.cluster        ← 极简/导航那一页就是它画的
/emmc/sva/bin/sva.winmgr         ← QNX 侧窗口合成
/emmc/sva/bin/sva.fastui         ← 启动参数 -display=0 / -display=1
/emmc/sva/bin/sva.extapa         ← 启动参数 -display=1
/emmc/sva/bin/sva.{avas,avm,chime,dms,doip,upgrade}

/emmc/sva/resource/kzb/cluster.kzb         ← Kanzi 资源包（界面全在这里）
/emmc/sva/resource/kzb/cluster_sub.kzb
/emmc/sva/resource/kzb/cluster_car.kzb
/emmc/sva/resource/kzb/globaltip.kzb
/emmc/sva/resource/kzb/mcuupdate_{1920_720,1920_1080,1440_1920}.kzb
/emmc/sva/resource/kzb/racing.kzb
/emmc/sva/etc/configure/cluster/cluster.kzb.cfg
/emmc/sva/etc/configure/cluster/application.cfg
/emmc/sva/etc/DataModule/NavigationData.xml      ← 仪表侧数据模块（导航/音乐/胎压/ADAS 各一个）
/emmc/sva/etc/DataModule/{ADAS,AVM,BT,BodyControl,DrivingInfo,ETM,Electric,
                          Indicator,Music,Radar,Radio,Setting,System,TPMS}Data.xml
```

注意：`-display=0 / -display=1` 是 **QNX 侧自己的编号**，和 Android 的 display id（0 主屏 / 2 仪表）
不是一套，不能对着猜。

## 3. 黑边的本体：九宫格装饰贴图

`cluster.kzb` 里带出来的素材名清单（同一镜像内可读）：

```
Images/t4/navi/big_bg/top_left     top_center     top_right
                   /left           center         right
                   /bottom_left    bottom_center  bottom_right
                     —— 每个位置都有 _0 / _1 两套（白天 / 夜间）
Images/t4/bg/bg_cluster_mask_0.png  bg_cluster_mask_1.png
bg_cluster_all_{0..11}.png    bg_cluster_adas_mask_{0,1}.png
bg_cluster_all_temp_{0,1}.png
```

也就是说：**那条黑边不是"QNX 把我们的流缩到一个小矩形"，而是仪表 UI 自己盖在四周的一圈装饰贴图**，
中间那块 `center` 才是留给导航/投屏的区域。这个区别决定了打法——
能动的只有"让哪一档布局、区域多大"，动不了贴图本身。

## 4. 我们发的 JSON 字段，QNX 侧确有接收端

`system_qnx.img` 里 C++ 侧符号（`grep -a -o -b` 单遍扫出，含计数）：

```
NaviDisplayArea / NaviDisplayAreaAsync / NaviDisplayAreaChangedEvent   （Kanzi 属性 + 变更事件）
NaviDisplayAttri / NaviDisplayAttriAsync / NaviDisplayAttriChangedEvent
navi_area_info_t            ← 区域信息结构体
RequestDisplayNaviArea      PerspectiveResult      DisplayCluster
NaviFrontDeskStatus         NaviDisplaySwitchFeedback
```

字段名和 `0x1004/0x2`、`0x1004/0x5` 两条裸报文里的 JSON key **一字不差**。
之前"发出去没反应"的原因因此可以彻底定死在 Android 侧：
CAR_LAN 转发服务没有 `unpackNaviDisplayCluster`，报文根本没到 QNX（详见《协议说明-QNX报文总表.md》）。

**落点已坐实：不是 socket，是 PPS 文件。** `ivi_service` 把报文写成
`/tmp/pps/ivi_service/tx`，属性名是 `xNNNN` 形式的十六进制串（代码里 `stoi(attrName.substr(1), 0, 16)`）：

| PPS 属性名 | 方法名 | 我们的对应物 |
|---|---|---|
| `x8400` | `RequestPerspective` | CAR_LAN `721704 NAVIGATION_PERSPECTIVE_REQUEST`（**还没发过**） |
| `x8401` | `RequestNaviAreaDisplay` | CAR_LAN `721705 NAVIGATION_AREA_DISPLAY_REQUEST` |

**QNX 认得的字段全集**（`ivi_service` 里的属性名表，逐字抄，偏移 1898546560 起）——
这就是"整套仪表能吃什么"，我们要的功能基本都能在这里找到落点：

```
 MediaType  StartIndex  EndIndex  Band  OperateType
 MusicPlayStatus MusicPlayAction MusicPlayMode MusicPlayFavorite
 CurrentPlayingTime TotalPlayTime MusicAtLaunchStatus AstState
 CallLogSyncState CallDuration Theme
 RoadInfo LaneInfo LaneId LaneIconId
 NaviFrontDeskStatus DisplayCluster Perspective PerspectiveResult RequestDisplayNaviArea
 NaviStatus SpeedingInfo CameraType WarningMessage
 NaviDisplayArea NaviDisplayAreaResult NaviDisplayLoading
 AvmCarModelColor RestartAndroid AduioStreamVolume
 EnterOrExitEngineeringMode EngineeringModePage carMode
 Origin SongIndex SongName SongArtist SongAlbum SongSchools SongAges
 SongAlbumPath TotalNum MusicListInfo SongID
 CurrentFreq StationName HasFocus MediaVolume RadioList Freq
 CallLog Time CallState ContactPicturePath PhoneType
 SegRemainDis RoadType RoadIcon NextNaviActionProgbar IntersectionZoomStatus
 RoadName NextRoadName TotalDistance DistanceUint TimeLeft RemDistance RemDistanceUint
 ArrivalTime Week leftTime progress
 currentVersion targetVersion updateModule lyrics
 LaneList LMW LCPx LFPx LDD
 ObjectList TCL TLC THC TRLaV TRLoV TVT TTW TTH TDD
 PlanPathConfidence PlanPathDisplayLength PlanPathDelayTime
 PlanPathCurvatureParam1..4
```

顺带暴露了几个我们还没动的能力：`RestartAndroid`（QNX 侧重启安卓，比 `adb reboot` 温和，
和"重启后无法投屏"那条直接相关）、`EnterOrExitEngineeringMode` + `EngineeringModePage`（原厂工程模式入口）、
`Theme` / `carMode` / `AvmCarModelColor`（仪表主题与车模颜色）、
`LaneList` / `ObjectList` / `PlanPath*`（车道线 + ADAS 目标 + 规划路径数组，做自定义仪表页的原料）。

## 5. "地图信息准备中" = Kanzi 文案表里的一条

UTF-8 全镜像单遍扫描，命中 2 处，都在 `system_qnx.img`：

```
偏移 2012492032    偏移 2032422595
```

上下文是 Kanzi 的多语言文案表：

```
TextNavi_MapInPreparation  →  地图信息准备中
TextNavi_NoNaviData / TextNavi_Arrive / TextNavi_km / TextCluster_Output /
TextWarning_306 / TextOTAUpgrade_*
```

安卓侧全部取证（含 6 个 `com.desaysv.*` 反编译、111 个已装包）里这句话**一次都没有**。
所以这页占位是仪表端 Kanzi 在"导航数据没送来"时自己画的，跟 psmap / 我们的 App 都无关。

## 6. 结论与打法

1. **替换渲染器这条路不成立。** 画仪表的是 `sva.cluster` + `cluster.kzb`，在 Android 侧没有任何可写落点。
   （"改一个字节起不来"是我**没实测过的推断**：包里确实有 `vbmeta` 分区（8.2 kB），
   但它是否覆盖 `system_qnx`、启动校验处于什么状态，从没在真机上回读看过，别当事实引用。
   不过这条路本来也不该走——要拆装整个分区、丢掉 OTA 回滚，明确超出"免特权"范围。）
   "开机自动投屏"不能靠换渲染实现，只能靠把投屏本身做到开机就绪。
2. **黑边是可谈的，谈的是区域档不是贴图**：`big_bg` 九宫格是 QNX 画的装饰，中间 `center` 才是留给导航/投屏的区域。
   能写的只有 `0x1004/5` 的 `NaviDisplayArea`（**枚举确认只有 0~4**：0=CLOSE_CAST_SCREEN、1=MIDDLE_FIRST_THEME、
   2=LEFT_FIRST_THEME、3=SECOND_THEME、4=THIRD_THEME）和 `0x1004/2` 那三个字符串布尔
   （`DisplayCluster` / `NaviFrontDeskStatus` / `RequestDisplayNaviArea`）。
   `NaviDisplayAttri` 在安卓侧 `ClusterDataParse` 的 37 条 unpack 里**一条都没有**，我们从这条路径发不出它。
3. **更正原先的打法**：`Perspective` 已从 `VDValueCarLan$Perspective` 逐字读出
   = `HEAD_UP_2D=0 / NORTH_UP_2D=1 / HEAD_UP_3D=2 / VIEW_ALTERNATELY=3`，它是**地图朝向不是画面大小**。
   所以 `run.sh` 里 `p0..p4` 那一组预期"看不出差别"，别把时间花在那儿；
   真正要盯的是 `area=0..4` 与 `latch=` 三个布尔的组合。
   另外 svmap 里 `CarLanManager.setNaviDisplayAreaToQNX` / `naviDisplayToQNX` / `naviNotDisplayToQNX`
   这一族**全是只打日志的空壳**，所以不存在"照地图 App 的实现抄"这条路，只有 §2 那五条预制 JSON 是可信参照。
   QNX 侧落点也已坐实：不是 socket，是 PPS 文件 `/tmp/pps/ivi_service/tx`（属性名 `x8400=RequestPerspective`、
   `x8401=RequestNaviAreaDisplay`）。
4. 下一步只有真机能定案：装 v4.2.2 → 跑 `抓包数据/投屏铺满-黑边/run.sh`（`HOLD=8`），
   每条留时刻 + 实拍，看哪一档的 `center` 区域最大。**在拿到实拍之前，任何"已生效"的说法都不算数。**
   若 0~4 全部实测的最大档仍然只是现状，那结论就是"无黑边的整屏铺满在免特权前提下做不到"，
   届时转为「按实测最大矩形做适配」，不再继续找旋钮。
