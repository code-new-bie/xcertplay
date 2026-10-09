<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay 比亚迪版</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>适配比亚迪 DiLink 车机的无线 CarPlay 接收端，基于原版 <a href="https://github.com/shilapi/xcertplay">xcertplay</a> 1.3.3。</p>
</div>

本分支 `byd-song-plus-2021` 在宋 PLUS 2021（DiLink，Android 10）上实车测试。在原版的基础上补充了比亚迪车机相关的功能和修复，并把界面改成了简体中文。原版的功能、MFi 接入方式和构建要求见文末的[原版内容](#原版内容)。

## 下载与安装

- 在 [Releases](https://github.com/code-new-bie/xcertplay/releases) 下载最新的 `xcertplay-mobile-<版本>.apk`，安装到车机。`automotive` 包只用于 Android Automotive OS 车机，比亚迪车机不需要。
- 包名为 `com.shilapi.xcertplay.byd`，桌面名称为“CarPlay”，可以和原版共存。
- 使用调试签名，本系列各版本可以直接覆盖安装。
- 需要 MFi 认证，例如 [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip) 转接板，其他方式见[原版内容](#原版内容)。
- 第一次接上 MFi 转接板时，系统会询问“连接此 USB 设备时是否打开 CarPlay”。勾选默认打开的选项后确定，之后上电不再询问。

## 首次设置

1. 用蓝牙把 iPhone 和车机配对，保持 iPhone 的蓝牙和 Wi-Fi 打开。
2. 打开 CarPlay，点首页的“设置”，也可以三指下滑打开。设置页左侧是分类：连接、显示、声音、车辆、高级、诊断。修改后点底部的“保存”。
3. 授权车机 ADB。表格中标为需要 ADB 的比亚迪功能都依赖它：
   - 车机要先开启网络 ADB（`127.0.0.1:5555`）。只开 USB 调试不够。开启方法因车机固件而异。
   - 打开“设置 → 车辆 → 比亚迪 → 车机 ADB”，点“检查并授权 ADB”，车机弹出调试授权对话框时选择允许。
   - 检查结果会显示能否读取车速和电量。之后后台使用时不会再弹出授权。
4. 按需打开下面的比亚迪功能。
5. 回到首页，应用会自动发起无线 CarPlay 连接。

## 比亚迪功能

| 功能 | 位置 | 默认 | 需要 ADB |
| --- | --- | --- | --- |
| CarPlay 期间断开 iPhone 蓝牙通话（推荐）：通话走 CarPlay，原车不再弹出来电 | 连接 | 开 | 否 |
| CarPlay 期间断开 iPhone 蓝牙音乐：音乐走 CarPlay，不走蓝牙 | 连接 | 开 | 否 |
| CarPlay 期间暂停车机 Wi-Fi 搜索（推荐），并限制高德和百度网络定位的扫描，减少卡顿 | 连接 | 开 | 是 |
| 开机自动启动 | 连接 | 关 | 否 |
| 倒车和 360 画面显示时保持 CarPlay 连接 | 自动 | — | 是 |
| 熄火时自动退出 CarPlay，并恢复对车机的改动 | 自动 | — | 是 |
| 隐藏原车来电弹窗（实验性） | 车辆 → 比亚迪 → 通话 | 关 | 否 |
| CarPlay 通话时降低空调风量，可选 1–3 档，通话结束后恢复 | 车辆 → 比亚迪 → 通话 | 关 | 是 |
| 仪表显示 CarPlay 歌曲；音乐 App 打开“车载歌词”后显示当前歌词 | 车辆 → 比亚迪 → 仪表 | 关 | 是 |
| 隧道导航使用车速和挡位（需同时打开“向 iPhone 上报位置”） | 车辆 → 比亚迪 → 车辆数据 | 关 | 是 |
| 为 Apple 地图提供电量和纯电续航（实验性） | 车辆 → 比亚迪 → 车辆数据 | 关 | 是 |
| 读取车机真实蓝牙地址，发给 iPhone | 车辆 → 比亚迪 → 车机 ADB | 手动 | 是 |
| 向 iPhone 上报位置：车机定位，含车速和卫星信息 | 车辆 | 关 | 否 |
| 媒体、导航、通话分别指定车机音频通道，可试听 | 声音 | 自动 | 否 |
| 方向盘按键控制播放；长按语音键唤起 Siri | 自动 | — | 否 |

## CarPlay 期间对车机的改动

为了减少卡顿，并让通话和音乐只走 CarPlay，无线 CarPlay 期间会对车机做以下改动。改动前会先保存原来的状态，之后再恢复：

| 改动 | 说明 |
| --- | --- |
| 断开 iPhone 的蓝牙通话（HFP）和蓝牙音乐（A2DP） | 把车机对这台 iPhone 的连接优先级设为“关”，不影响其他手机 |
| 暂停车机 Wi-Fi 自动搜索 | 期间车机不会自动连接已保存的 Wi-Fi |
| 高德车机版：限制 Wi-Fi 扫描，每次连接时强制停止一次 | 原车高德导航会结束，不会自动重新打开 |
| 百度网络定位：限制 Wi-Fi 扫描，但不停止它 | 期间车机的网络定位可能变粗 |

恢复时机：
- CarPlay 断开 15 秒后恢复；15 秒内重新连上则继续保持。
- 关闭对应开关，或在设置里退出应用时，立即恢复。
- 熄火时：比亚迪车机熄火约 10 秒后休眠，休眠前会直接结束第三方应用。所以 CarPlay 会在熄火后约 2 秒自动退出，并立即恢复（需授权 ADB）。
- 兜底：如果熄火时没来得及恢复，比如熄火后马上开门、车机很快休眠，下次上电时会补做：
  - 没开“开机自动启动”：在后台立即恢复；
  - 开了“开机自动启动”：先保持，60 秒内没连上 CarPlay 才恢复，避免 iPhone 蓝牙先连上又被断开。

另外，CarPlay 播放时会占用音频焦点，原车的音乐等媒体会暂停；无线会话期间会保持 Wi-Fi 高性能模式。

## 开机自动启动

- 打开：车机开机或上电后自动打开 CarPlay 并连接。
- 关闭：上电后不会自动连接，即使系统识别到 MFi 转接板也不会打开 CarPlay，需要手动打开应用。

## 日志与反馈

在“设置 → 诊断 → 导出日志”导出，文件在“下载/xcertplay”：

| 文件 | 内容 |
| --- | --- |
| `xcertplay-<时间>.log` | 本次运行的日志，每次打开 CarPlay 都会重新开始 |
| `xcertplay-boot-<时间>.txt` | 熄火和上电时的处理记录 |
| `xcertplay-logcat-<时间>.txt` | 本应用的系统日志 |

导出前不要先退出应用，否则本次运行的日志会丢失。日志的原始位置是 `/sdcard/Android/data/com.shilapi.xcertplay.byd/files/logs/`。

提 issue 时请附上这三个文件，并写明车型和车机系统版本。

## 已知问题

- 长时间播放时，音乐偶尔会有短暂卡顿，已加诊断日志排查。
- 熄火后 10 秒内又上电的话，CarPlay 不会自动回来，需要手动打开。
- 倒车不断连和定位续传还需要更多实车验证。熄火退出和上电恢复已在宋 PLUS 2021 上实车验证（1.3.3-byd.5）。

## 开发

构建（Windows PowerShell）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :common:testDebugUnitTest :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

macOS 或 Linux：

```bash
./gradlew :common:testDebugUnitTest :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

发布流程（GitHub Actions，`.github/workflows/android.yml`）：
- 推送分支时只跑测试、lint，并构建 debug 包作为附件。
- 推送 tag 时构建 release 包并自动发布 release。版本名就是 tag 名，通过 `-PreleaseVersionName=<tag>` 传入。
- 附注 tag 的说明就是 release 正文。要用 `git tag -a <tag> --cleanup=whitespace -F notes.md` 创建，否则以 `#` 开头的标题行会被 git 当作注释删掉。
- 签名用到 4 个 secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。本仓库使用调试密钥，保证各版本可以互相覆盖安装。本地构建时设置环境变量 `ANDROID_KEYSTORE_PATH` 等即可签名，不设置则输出未签名包。

实车结论、固件分析和待验证事项见交接文档 [docs/handoff/2026-10-05-byd-song-plus-2021.md](docs/handoff/2026-10-05-byd-song-plus-2021.md)。

## 原版内容

以下为原版 xcertplay 的说明，本分支仍然适用。

### 原版功能

- 面向 Android 和 Android Automotive OS 的 CarPlay 主机应用。
- 支持 CH341 桥接 MFi 芯片、原生 `/dev/i2c-N` 设备连接的 MFi 芯片、本地证书/私钥文件和 Remote MFI 认证（API 见下）。
- 支持 CarPlay 有线或无线连接。
- 支持触发 CarPlay Ultra（未测试/未完成的协议栈，但是确实可以在 iPhone 上触发 CarPlay Ultra 的提示）。
- 支持语音、导航、音乐多通道音频输出并映射到 Android 的对应通道。
- 支持动态 Activity resize，并自动重新握手至新的分辨率。
- 支持车机位置回传。
- 支持 Android 9（API 28）。

MFi 转接板：[CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)。认证方式在“设置 → 连接 → 认证方式”中选择。

### 本地 MFI 文件

在“认证方式”中选择“本地文件”，然后通过两个“选择”按钮，用 Android 系统文件选择器选择证书和私钥。当前支持 DER PKCS#7 证书（`.p7b`），以及与之匹配的、未加密的 DER PKCS#8 私钥（`.pk8`）。应用会在开始连接手机前校验两者是否匹配，并在 MFI 重连时重新读取文件。

建议把私钥放在受保护的位置。应用不会把证书或私钥复制到偏好设置，只会保存 Android 授予的持久读取权限和文档 URI。

### Remote MFI 功能

Remote MFi 客户端把远程服务当作一块 MFi 芯片远程调用，或者采用 BAA 认证。通过远程认证，可以免去本地连接 MFi 芯片的步骤。

| Method | Path | 用途 | Request body | Success response | 失败 response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | 获取 MFI 芯片版本、证书类型和证书内容，客户端首次调用后缓存 | 无 | 证书 JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | 对 challenge 签名 | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | 请求重置远程 MFI 芯片 | `{}` | `{"detail":""}` | `{"detail":"..."}` |

（可选）采用标准 Bearer Authentication 进行验证。

**当前仅测试了 BAA Authentication。**

### 工程结构

| 路径 | 用途 |
| --- | --- |
| `common/` | 两个目标共用的 CarPlay 宿主界面、设置、持久化和应用资源。 |
| `mobile/` | 使用共享 CarPlay 主机界面的 Android 应用。比亚迪车机安装这个。 |
| `automotive/` | 使用共享主机界面并支持高级音频通道映射的 Android Automotive OS 应用。 |
| `shared/` | Car App Library 代码，以及 CH341、I2C、MFi、iPhone、iAP2、NCM、VPN、AirPlay、媒体和比亚迪车机相关实现。 |

### 环境要求

- 启动 Gradle 需要 JDK 17 或更高版本；daemon 通过 Gradle toolchain 解析 Java 25。
- Android SDK Platform 37。
- Android 9（API 28）或更高版本。在 Android 9 上不能使用 Wi-Fi P2P 5 GHz 模式，应用会改用 LocalOnlyHotspot。
- Android NDK `28.2.13676358`。
- 硬件验证需要支持 USB Host/OTG 的 Android 设备以及 MFi 硬件。

## 致谢

感谢 [LIVI](https://github.com/f-io/LIVI) 项目为本项目提供了重要参考。
感谢 [showcase](https://github.com/amineross/showcase) 项目为本项目的 BAA 认证提供重要参考。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可。
