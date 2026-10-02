# 仪表投屏协议一键抓包

这个目录用于在电脑上通过 ADB 抓车机上的高德/导航投屏协议。

## 环境要求

- 电脑已安装 `adb`，并且车机已开启 USB 调试
- 车机已 root，或至少 ADB 能执行 `su`
- 电脑上安装了 `zip`，Linux/macOS 默认有，Windows 可用 7-Zip

## 快速使用

### Linux / macOS

```bash
cd /workspace/cluster-capture
bash run_capture.sh
```

### Windows PowerShell

```powershell
cd C:\path\to\cluster-capture
.\run_capture.ps1
```

脚本会提示你按顺序操作：

1. 车机进入高德车机版，不要开始投屏
2. 脚本开始记录
3. 你执行一次“投屏到仪表”
4. 保持 20 秒
5. 取消投屏
6. 再等 10 秒
7. 脚本自动生成 zip

## 输出文件

脚本会生成一个 zip，例如：

```text
cluster_capture_20260918_120000.zip
```

包含：

```text
getprop.txt
packages.txt
display.txt
processes.txt
logcat_cluster.log
logcat_cluster_filtered.log
strace_amap.txt
tcpdump_local.cap
summary.txt
```

你只需要把这个 zip 发给我。

## 如果缺少工具

如果提示没有 `strace` 或 `tcpdump`，脚本会跳过对应部分，不影响基础 logcat 和 display 信息。
