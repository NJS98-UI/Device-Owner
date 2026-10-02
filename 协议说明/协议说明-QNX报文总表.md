# Android → QNX 报文总表（CabinLAN / CAR_LAN 转发服务实拆）

来源：`SVVDSCarLan.apk`（`com.desaysv.ivi.vds.carlan`）反汇编，
类 `com.desaysv.ivi.vds.carlan.manager.cluster.ClusterDataParse` / `ClusterManager` / `impl.ClusterImpl`。
不是推测，是从 smali 里逐条读出来的。

## 0. 转发链路

```
应用 VDBus.set(VDEvent)              ← 我们只能站到这一格
   └─ CAR_LAN 服务 ClusterManager.onSet(event)
        └─ ClusterDataParse.unpackXxx(event) -> VDCLCommonMessage(subtype, gson.toJson(bean))
              .setMsgType(0x1001..0x1009)
        └─ ClusterImpl.sendMessage(VDCLCommonMessage)
              └─ CABIN_LAN 裸隧道 -> vdev.service.cabinlan -> QNX
                   logcat: "CabinLANVDS::mRemoteCommon->setMessage,MsgType = 0x1004, SubType = 0x5, Content = {...}"
```

两个结论：

1. **CAR_LAN 的 bean 事件能不能到 QNX，取决于 `ClusterDataParse` 里有没有对应的 `unpackXxx`。**
   没有就是静默丢弃，`VDBus.set()` 不抛异常，看着像成功。
2. 反过来说，只要 `msgType/subtype/JSON` 三个字段凑对，直接走 CABIN_LAN
   （`VDBus.set` + `VDCLCommonMessage`，eventId `1114113`）就能绕过 bean 表，
   发出转发服务里根本没有对应 unpack 的那几条报文。**我们的投屏铺满就靠这个。**

## 1. msgType 分类

| msgType | 域 |
|---|---|
| 0x1001 (4097) | 音乐 |
| 0x1002 (4098) | 收音机 |
| 0x1003 (4099) | 蓝牙电话 |
| 0x1004 (4100) | **导航 / 仪表显示区** |
| 0x1005 (4101) | 系统（心跳、开机、QNX 版本、DMS、AVM 车牌…） |
| 0x1009 (4105) | ADAS / AR-HUD |

## 1.5 全量 (msgType, subtype) 表 —— `ClusterDataParse` 逐条抽出的 37 条

这就是"安卓能让仪表做什么"的完整清单（`vds/tab.out` 可复算，脚本 `vds/tab.awk`）。
方法名 `unpackNaviDisplayAre` 少一个 `a` 是德赛源码里的拼写错误，不是抽取时截断的。

| msgType | subtype | unpack 方法 |
|---|---|---|
| 0x1001 | 1 | MusicPlayStatus |
| 0x1001 | 2 | MusicPlayAction |
| 0x1001 | 3 | MusicPlayMode |
| 0x1001 | 4 | MusicPlayFavorite |
| 0x1001 | 5 | MusicPlayList |
| 0x1001 | 6 | MusicPlayTime |
| 0x1001 | 7 | MusicLyrics |
| 0x1002 | 0 | RadioFastTuner |
| 0x1002 | 1 | RadioAstState |
| 0x1002 | 2 | RadioList |
| 0x1003 | 0 | BTCallRecordList |
| 0x1003 | 1 | BTCallRecordSyncStatus |
| 0x1003 | 2 | BTCallStatus |
| 0x1003 | 3 | BTCallDuration |
| 0x1003 | 4 | BTCallStatusSecond |
| 0x1003 | 5 | BTCallDurationSecond |
| 0x1004 | 0 | NaviLaneInfo |
| 0x1004 | 1 | NaviRoadInfo |
| 0x1004 | 3 | NaviTotal |
| 0x1004 | 4 | NaviDigitalInfo |
| 0x1004 | 5 | NaviDisplayAre ← 显示区档位 |
| 0x1004 | 6 | NaviCompass |
| 0x1005 | 0 | SystemStart |
| 0x1005 | 1 | SystemHeartBeat |
| 0x1005 | 2 | SystemStartRecovery |
| 0x1005 | 3 | SystemStartHmi |
| 0x1005 | 4 | SystemGetQnxRunInfo |
| 0x1005 | 5 | SystemGetQnxVersionInfo |
| 0x1005 | 6 | SystemGetIviLogoPlayStatus |
| 0x1009 | 0 | AdasHudSettings |
| 0x1009 | 4 | VrHudOtaInfo |
| 0x1009 | 5 | ArHudVersionInfo |
| 0x1009 | 6 | ArHudSettingsInfo |
| 0x14 | 0 | SystemAvmLicencePlate |
| 0x16 | 0 | SystemDmsActiveReady |
| 0x17 | 0 | DmsActiveCode |
| 0x9 | 0 | MediaAtLaunchStatus |

看这张表要注意：**0x1004 里 2 和 9 是空的**，正是我们裸发的两条（§2）；
而 `0x1005` 那一族（开机、心跳、QNX 版本）是**问 QNX 要东西**的方向，
以后要判断"报文到底进没进 QNX"，可以拿 `0x1005/5 SystemGetQnxVersionInfo` 的回执当探针用。

## 2. 0x1004 导航域 subtype 全表

| subtype | bean | 语义 | 我们能不能发 |
|---|---|---|---|
| 0 | `VDNaviLaneInfo` | 车道线 | 能（bean 有 unpack） |
| 1 | `VDNaviRoadInfo` | 道路名 | 能 |
| 2 | ~~`VDNaviDisplayCluster`~~ | **占/让仪表页** | **不能走 bean：没有 unpackXxx，静默丢；必须裸发** |
| 3 | `VDNaviTotalInfo` | 续航/总信息 | 能 |
| 4 | `VDNaviDigitalInfo` | 数字导航信息 | 能 |
| 5 | `VDNaviDisplayArea` | 导航布局档 0~4 | 能（实机已验证到 QNX） |
| 6 | `VDNaviCompass` | 罗盘 | 能 |
| 9 | 无 bean | **"地图信息准备中"加载态** | 只能裸发（实机抓自原车高德） |

`subtype=2` 和 `subtype=9` 在整个 `ClusterDataParse` 里**一个 unpack 都没有**。
原车高德（`com.desaysv.jetour.t1n.psmap`）是自己直接压 `VDCLCommonMessage` 发出去的，
所以这两条我们只能照它做——裸 JSON 走 CABIN_LAN。

### 实机抓到的 subtype=2 原文（psmap 开机三连发的第 2 条）

```
MsgType = 0x1004, SubType = 0x2,
Content = {"DisplayCluster":"true","NaviFrontDeskStatus":"true","Perspective":0,
           "PerspectiveResult":"false","RequestDisplayNaviArea":"false"}
```

psmap 里三条预制 JSON（`com.desaysv.ivi.vds.navi.constants.AmapAutoConstants`，逐字抄）：

| 常量名 | 值 |
|---|---|
| `QNX_DISPLAY_JSON` | `{"DisplayCluster":"true","NaviFrontDeskStatus":"true","Perspective":0,"PerspectiveResult":"false","RequestDisplayNaviArea":"false"}` |
| `QNX_NOT_DISPLAY_JSON` | `{"DisplayCluster":"false","NaviFrontDeskStatus":"false","Perspective":0,"PerspectiveResult":"false","RequestDisplayNaviArea":"false"}` |
| `NAVI_FIRST_START_TO_DISPLAY_JSON` | `{"DisplayCluster":"false","NaviFrontDeskStatus":"true","Perspective":0,"PerspectiveResult":"false","RequestDisplayNaviArea":"true"}` |
| `NAVI_DISPLAY_AREA` | `{"NaviDisplayArea":1,"NaviDisplayAreaResult":"true"}` |
| `NO_DISPLAY_AREA` | `{"NaviDisplayArea":0,"NaviDisplayAreaResult":"true"}` |

注意布尔字段是**字符串** `"true"`/`"false"`，只有 `Perspective` 是裸整数。

## 3. 闩锁行为（实机验证，别丢）

QNX 收到 0x1004 这一族之后把 display 2 的透出状态**闩住**：
psmap 被杀掉之后 QNX 从不重读，display 2 一直透出。
只有再走一遍 `NaviDisplayLoading:1` → 内容 → `NaviDisplayLoading:0` 才会重新取一次。
所以任何改布局/申请区域的动作后面必须补一次重闩，代码里是 `Vd.relatch()` / `Vd.areaRelatch()`。

单独发 `{"NaviDisplayLoading":1}` 不发第 0 条，面板会卡在"地图信息准备中"——实机复现过。

## 3.5 QNX 侧对上了（2026-09-21，OTA 包 `system_qnx.img` + svmap 反汇编双向核对）

**QNX 落点不是 socket，是 PPS 文件。** `ivi_service` 把每报文写成
`/tmp/pps/ivi_service/tx`，属性名是 `xNNNN` 十六进制串（代码里 `stoi(attrName.substr(1), 0, 16)`），
值就是那段 JSON。已坐实的两个：

| PPS 属性名 | 名字 | 对应我们的 |
|---|---|---|
| `x8400` | `RequestPerspective` | CAR_LAN `721704` / `VDNaviPerspective{int RequestPerspective}` |
| `x8401` | `RequestNaviAreaDisplay` | CAR_LAN `721705` / `VDNaviAreaDisplay{int RequestNaviAreaDisplay}` |

⇒ `721704 NAVIGATION_PERSPECTIVE_REQUEST` 是**我们一直没发过的一档**（bean 存在、QNX 有真实接收方）。

**枚举域（`VDValueCarLan$*` 逐字读，不是猜）：**

- `NaviDisplayArea`：`CLOSE_CAST_SCREEN=0`、`MIDDLE_FIRST_THEME=1`、`LEFT_FIRST_THEME=2`、
  `SECOND_THEME=3`、`THIRD_THEME=4`。**只有 0~4，5 以上不存在**，试验矩阵别往 5~12 铺。
- `Perspective`：`HEAD_UP_2D=0`、`NORTH_UP_2D=1`、`HEAD_UP_3D=2`、`VIEW_ALTERNATELY=3`。
  ⇒ 它是**地图朝向，不是画面尺寸**，指望它撑大矩形是徒劳的（这条把 `relatch()` 的第 3 个参数定性了）。
- `NaviDisplaySwitch` / `DisplaySwitch`：`INVALID=0`、`CLOSE=1`、`OPEN=2`（QNX 侧符号 `NaviDisplaySwitchFeedback` 成对）。

**纠正一个说法**：svmap 里 `CarLanManager.setNaviDisplayAreaToQNX(I)`、`naviDisplayToQNX()`、
`naviNotDisplayToQNX()`、`noDisplayAreaToQNX()`、`setNaviFrontDeskStatus()` **全是只打日志的空壳**
（逐条看字节码，函数体就 `Log.i` + `return-void`）。所以"仪表矩形是地图 App 画的"这个说法不成立——
真正的写入方是 `AmapAutoConstants` 里那五条预制 JSON（见 §2 表）经高德自己发出；
而 `QNXRequestHandleUtil.handleRequestNaviAreaDisplayEvent` 处理的是 **QNX→Android 的反向请求**
（`msgType=0x2004, subType=1`，content `{"RequestNaviAreaDisplay":int}`）。

## 4. 还没挖的

- `0x1005` 系统域里 `SYSTEM_SET_DISPLAYER`（EventID `1508620` = 0x17050C → 0x1005/0x0C）
  和它的回执 `SYSTEM_SET_DISPLAY_RESULT`（0x17850A）。名字直译就是"设置显示器"，
  载荷 schema 未知，没敢盲发。
- `VDValueCabinLan$EventID` 全表已解出打包规则：
  `EventID = 0x17<<16 | (msgType & 0xFF)<<8 | subtype`，最高位 0x80 表示 QNX→Android 回执方向。
  解出来的 100 条在 `vds/vdbus_consts.txt` 里可复算。
