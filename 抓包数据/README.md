# 抓包数据

两批，都来自捷途 T1J 车机（Desay 固件 / Android 11），adb 实机抓取，不是推测。

## 第一批 2026-09-19 凌晨（v3.2.2 投屏链路取证）

- `theme_capture.log`（76MB）—— 切仪表模式全程 logcat，含 `CarSystemGesturesManager` 手势广播与
  `VDS-CarInfo-CarInfoService` 的 onGet/onSet 链路
- `证据-三指手势.txt` / `证据-仪表模式.txt` / `证据-媒体总线事件.txt` —— 上面那份日志里定位出的关键片段
- `media_session.txt` —— 各音乐 App 的 MediaSession 现状（决定"读正在放什么歌"为什么要跳过空会话）
- `settings对比/` `截图/` `反编译/` —— 切模式前后 Settings/prop 全量 diff、主屏与仪表屏截图、
  vdbus / carinfo / 桌面的 dexdump

## 第二批 2026-09-19 19:00~23:00（车控总线 / 系统弹窗 / 环视摄像头）

- `车控与弹窗-证据摘录.txt` —— **先看这个**，每节标了原始文件与时间戳
- `车控总线-空调座椅车窗档位/` —— HvacModel / ChairModel / WindowController 反编译摘录（cmdId 表）+
  语音实测下发日志（`win_round.log.gz`、`win_ac.log.gz`、`taps.log.gz`）+
  受控挂挡序列（`gear_all.txt`、`gear_run.log.gz`）
- `系统弹窗与显示/` —— `acct60.log.gz`：`android/.accounts.CantAddAccountActivity` 每 20s 一次的现场，
  以及弹窗期间的前台窗口归属；`snap_all.log.gz`：前台包名轮询快照
- `环视摄像头/` —— `cam_full.txt` / `cam_now.txt`（cameraId 4/5/6/7 能力与实际取流）、
  `r_hold.txt`（挂 R 期间 CameraService active clients 采样）、`live_1920.log.gz`（515MB 全量 logcat 压缩）、
  `截图/` 与逐帧序列（逐帧原图在 Release 附件 `frames-20260919.tar`）
- `原车系统应用取证/` —— 已装第三方包的 dexdump 摘录与字符串表

## 读法

`.gz` 就是原始文件，内容一字未改，只是压缩：`gunzip -k xxx.gz` 或 `zcat xxx.gz | grep ...`。

单文件超过 GitHub 100MB 上限的原件**不进仓库正文**，放在 [Releases](../../releases)：
原车系统 APK 原件（`stock-apks-20260919.tar`）、大体积 dexdump（`stock-dexdumps-20260919.tar`）、
逐帧截图（`frames-20260919.tar`），以及第一批那 6 个整机包。
