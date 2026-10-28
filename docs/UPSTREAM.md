# 上游 xcertplay 跟进记录

本分支 `byd-song-plus-2021` 是 [shilapi/xcertplay](https://github.com/shilapi/xcertplay) 的比亚迪长期分支。这里记录怎么跟进上游，以及每个上游提交的处理结果。

## 跟进方式（2026-10-12 起）

两边的方向已经分开：上游在做网页显示、新的握手界面等，本分支有自己的中文界面、音频处理和比亚迪功能。整体合并会在界面代码上大量冲突，还会带进很多用不上的代码，所以不再整体合并上游，改为挑选移植：

1. **fork 的 `master` 只镜像上游**：只做快进同步，不在上面提交。
2. **定期查看**：上游每发一个版本（或至少每两周）查看一次自“已查看到”以来的新提交，也看一眼上游的开发分支。
3. **挑选移植**：
   - 有用的提交用 `git cherry-pick -x` 拿到本分支，提交说明末尾会自动记下上游原提交号；
   - 需要改写才能用的，手工移植，并在提交说明里写 `Ported from upstream <提交号>`。
4. **每个上游提交都记入下表**：移植了就写本分支的提交号，跳过就写原因。合并提交（如合入 PR 的 merge）也列出，注明对应哪个提交。
5. **反向贡献**：本分支的通用修复（与比亚迪无关的），择机从 `master` 拉分支给上游提 PR，并记入“提给上游的 PR”。

2026-10-12 之前的约定是“用 merge 跟进上游”，但实际从未合并过；本分支的分叉点是上游 `17c9243`（1.3.3 之后的音频修复）。

## 版本号

2026-10-13 起发布版本叫 `1.4.0-byd.N`（N 接着之前的编号往上加），表示已跟进到上游 1.4.0。注意这不等于整体合并了 1.4.0：上游 1.4.0 里只移植了下表标为“已移植”的提交，网页显示、新外观等没有采用。上游以后发新版本并跟进后，前面的版本号随之更新。上游 1.4.1、1.4.2 只是界面和少量修复，本分支挑了几项（2026-10-25），版本号仍沿用 `1.4.0-byd.N`（1.4.0-byd.20）。默认版本号（没有 tag 的本地构建）在 `mobile/build.gradle.kts` 和 `automotive/build.gradle.kts`，`versionCode` 为 1400。

## 操作步骤

```bash
git fetch upstream

# 新提交（把 551895a 换成下面“已查看到”的提交号）
git log --format="%h %ad %an | %s" --date=short 551895a..upstream/master

# 上游开发分支领先 master 的提交
git log --oneline --no-merges upstream/master..upstream/videoplayback
git log --oneline --no-merges upstream/master..upstream/iAP2-rewrite

# 移植一个提交
git cherry-pick -x <提交号>

# 把 fork 的 master 快进到上游（不是快进时会被拒绝，正好防止误改）
git push origin upstream/master:refs/heads/master
```

移植后跑 `./gradlew.bat :common:testDebugUnitTest :shared:testDebugUnitTest assembleDebug`，再更新本文件的表格和“已查看到”。

## 已查看到

- 上游 `master`：`1298fb3`（1.4.2，2026-10-18），2026-10-25 查看。
- 分叉点：`17c9243`。

## 上游提交处理记录

| 上游提交 | 日期 | 内容 | 处理 | 原因 / 本分支提交 |
| --- | --- | --- | --- | --- |
| `e12cb10` | 2026-10-08 | 有线 CarPlay 的 VPN 只代理本应用（`addAllowedApplication`），不再让整车断网 | 已移植 | `7a0e2eb` |
| `1bea0b9` | 2026-10-10 | 网页版主屏和仪表显示 | 跳过 | 本分支用车机原生显示；约 1000 行，与 CarPlay 页面大量冲突 |
| `26ee907` | 2026-10-10 | 新外观：握手面板、连接时间线、会话日志文件等（约 3500 行） | 跳过 | 本分支有自己的中文首页、连接进度和日志导出 |
| `c9eea8a` | 2026-10-11 | 网页显示管线和体验优化 | 跳过 | 同 `1bea0b9` |
| `57e6ab6` | 2026-10-11 | 合并上游 PR #37 | — | 合并提交，内容即 `e12cb10` |
| `a6bd9ef` | 2026-10-11 | 音频焦点（`AndroidMediaSink.AudioFocusSession`） | 跳过 | 本分支已有 `CarPlayAudioFocus`（媒体和通话焦点，方向盘按键跟随焦点），两套会冲突 |
| `c2912a5` | 2026-10-11 | 去掉 `26ee907` 新主题里的 `enforceStatusBarContrast` 等两项 | 跳过 | 修的是 `26ee907` 引入的主题，本分支没有 |
| `551895a` | 2026-10-11 | 版本号改为 1.4.0 | 跳过 | 本分支版本号由 tag 决定（`-PreleaseVersionName`） |
| `f2ad404` | 2026-10-14 | `.gitignore` | 跳过 | 只是上游自己的忽略列表 |
| `0e9048e` | 2026-10-14 | 车辆数据设置、可复用的定位来源策略 | 跳过 | 本分支车辆数据走比亚迪 SDK 和车机 ADB，比亚迪的车速、挡位不在标准 Android 车辆属性里 |
| `4f02cf0` | 2026-10-14 | 标准 Android 车辆属性（`CarPropertyManager`）只读数据源 | 跳过 | 本分支车辆数据走比亚迪 SDK 和车机 ADB，比亚迪的车速、挡位不在标准 Android 车辆属性里 |
| `d20d599` | 2026-10-14 | 车辆属性事件时间戳、读取失败作废 | 跳过 | 同 `4f02cf0` |
| `47034a4` | 2026-10-14 | 有线和隧道 iAP2 上报订阅的部分电动车状态 | 跳过 | 本分支已有电量和续航上报（从 DiPlay 移植） |
| `ddb3a65` | 2026-10-14 | 按 iPhone 的 0xFFFA 请求选择 NMEA 语句和间隔，多条语句分参数发送 | 部分移植 | 2026-10-15，未提交：只发请求里的语句类型（`NmeaSentenceSelector`）、每条语句一个参数；按请求间隔发送没有移植，只把间隔记进日志。交接文档第 32 节 |
| `7eb38f9` | 2026-10-14 | 用车速和挡位发 `$PASCD` | 跳过 | 本分支已有（从 DiPlay 移植，`VehicleSpeedNmea`） |
| `84a05c1` | 2026-10-14 | 车外温度等车辆状态扩展 | 跳过（暂缓） | iPhone 拿车外温度显示在哪里还不清楚，以后再看 |
| `003f01e` | 2026-10-14 | 车辆属性来源可用性、断线恢复 | 跳过 | 同 `4f02cf0` |
| `0288177` | 2026-10-14 | 手机退订后停止车辆采样 | 跳过 | 同 `4f02cf0` |
| `4ef48f9` | 2026-10-14 | 车辆数据来源诊断、申请车速权限 | 跳过 | 同 `4f02cf0` |
| `6e8c2ca` | 2026-10-14 | Material 新界面（约 7800 行）；麦克风增益改为 0.5x–10x | 跳过 | 本分支有自己的中文界面；没人反映通话对方听不清，增益范围不改 |
| `8b01399` | 2026-10-14 | 多个 view area、不重连快速切换；音频焦点开关 | 跳过 | 本分支旋转不断连用自己的方形画布做法，已实车通过；上游在 `1836c27` 又改回只声明当前方向。分屏的做法后来按 DiPlay 的移植了（2026-10-28，见下面 DiPlay 表）：原版只在新窗口正好是声明过的尺寸时切换，分屏窗口一般没声明过，等于还是重连 |
| `57221e4` | 2026-10-14 | 无线高性能 Wi-Fi 锁 | 跳过 | 本分支已有（`WirelessPerformanceLock`，也是 PR #47） |
| `bca2e35` | 2026-10-15 | JSON 车辆配置和可插拔数据源 | 跳过 | 同 `4f02cf0` |
| `1836c27` | 2026-10-15 | 降低串流开销、显示合成帧率 | 部分移植 | 2026-10-15，未提交：移植了系统 ChaCha20 解密、4 字节长度原地转 Annex B、视频接收/解码线程 DISPLAY 和音频播放线程 AUDIO 优先级；帧率显示、会话日志、view area 等跳过。交接文档第 32 节 |
| `c43810b` | 2026-10-15 | 清单里声明文档和图片选择器的 `queries` | 跳过 | 只影响 Android 11 及以上，宋 PLUS 是 Android 10；其他车机需要时再加 |
| `2bb1ade` | 2026-10-15 | 更好的定位上报 | 部分移植 | 2026-10-15，未提交：GPS 新鲜时优先、按开机时间判断新旧（`LocationFixChoice`）、GGA 写真实定位质量和卫星数、时间带百分秒、同一个定位不重复发；按请求间隔（约 250 ms）发送、海拔转换（Android 14 才有）、“精确定位”开关没有移植 |
| `2b76c3c` | 2026-10-14 | `/ctrl-int/1/connect` 探测的接收端设备号改为十进制；只在同一地址族时绑定本机地址（PR #52） | 已移植 | 2026-10-25，1.4.0-byd.20（`cherry-pick -x`）。绑定那段与本分支的接口 mDNS 选地址合并：两种来源都只在与目标同一地址族时绑定。这个探测在本分支只写日志 |
| `f769650` | 2026-10-15 | 网页显示优化 | 跳过 | 同 `1bea0b9` |
| `d8bee63` | 2026-10-15 | 测试整理（握手回放等） | 跳过 | 对应的是上游自己的界面和握手代码 |
| `d8bf152` | 2026-10-15 | 手动重连按钮 | 跳过 | 上游新界面的按钮，本分支有自己的首页 |
| `bc30320`、`d58cd44` | 2026-10-15 | 合并 PR #52、合并分支 | — | 合并提交，内容即 `2b76c3c` |
| `0bed3d9` | 2026-10-16 | 音频格式菜单、AAC-ELD、按路声明格式（约 1500 行） | 跳过 | 本分支有自己的音频设置。它把导航的 Opus 只声明 48 kHz，但 10-23 实车 iPhone 在可选 16/24/48 kHz 时给导航选了 16 kHz，与它的说法对不上，不贸然改 |
| `3e07bca` | 2026-10-16 | 大量优化：解码线程不再轮询、预览解码器、网页显示等 | 部分移植 | 2026-10-25，1.4.0-byd.20：换显示表面后请求关键帧（静止画面不会再发新画面）、判断关键帧只读 NAL 头不复制整帧、`MediaCodec.BufferInfo` 复用、`UsbManager` 从应用上下文取。解码线程改为阻塞等待、按 uuid 请求副屏关键帧、网页和界面部分没有移植 |
| `a477de1` | 2026-10-16 | CarPlay 画面淡入 | 跳过 | 上游界面 |
| `0d6c78c` | 2026-10-16 | 视频缓冲复用，避免周期性垃圾回收卡顿 | 已移植 | 2026-10-25，1.4.0-byd.20，手工改写：`VideoBufferPool`、`VideoAccessUnit`，读、解密、转 Annex B、送解码器都用池里的缓冲；视频改用 BouncyCastle 的 `ChaChaOpener` 直接解密进池里的缓冲（系统解密会另外分配结果），音频仍用系统解密。会话日志的视频包记录本分支没有，不涉及 |
| `56f7bc2` | 2026-10-16 | 版本号 1.4.1 | 跳过 | 本分支版本号由 tag 决定 |
| `42ad6da` | 2026-10-18 | 麦克风上行的几处修改 | 部分移植 | 2026-10-25，1.4.0-byd.20：Opus 麦克风的 RTP 时间戳按所选采样率递增（原来按 48 kHz，20 ms 加 960，现在 16 kHz 加 320），与 1.4.0-byd.19 的下行时钟一致。按协商采样率直接录音、取消 16 kHz 上限依赖 `0bed3d9`，没有移植 |
| `1298fb3` | 2026-10-18 | 版本号 1.4.2 | 跳过 | 同上 |

## 从 DiPlay 上游移植的内容

DiPlay（[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)）是另一个从 xcertplay 分出来的项目，包名相同，有些功能值得拿过来。移植时手工改写，不用 `cherry-pick`（两边的界面和控制器已经差得很远），说明里写清来源提交。

| 功能 | DiPlay 上游提交 | 本分支 | 说明 |
| --- | --- | --- | --- |
| 现有 Wi-Fi / 同一局域网 | `31b8dfcd`、`0b31720b`、`f6bfea43`、`ebbcb647`、`d729cd89`（2026-10-04 至 10-07，作者 Dlochon，DiPlay 0.2.18 内） | 2026-10-13，未提交 | 移植了 `ExistingWifiManager`、地址选择、0x5703 的 AP BSSID、JmDNS 版 Bonjour、设置页选项和测试。没有移植双栈监听（`AirPlayPortSelector.bindAll`、`CarPlayVpnService` 的多监听）：本分支无线监听本来就绑 `::`。DiPlay 把所有无线模式都改成 JmDNS，本分支只让新模式用，P2P、本地热点、手动热点继续用系统 NSD，因为它们已在宋 PLUS 上验证过 |
| iOS 27 停车看视频 | DiPlay 0.2.8 的视频提交 | `docs/handoff` 第 24 节 | 见上 |
| 音频解码器输入：先排空再取，最多等 100 ms | `daffd2de`、`7d974f42`（PR 618、644，DiPlay 0.2.18、0.2.19） | 2026-10-14，未提交 | 原来只等 10 ms 就丢包。DiPlay 0.2.18 用 500 ms 出过问题（issue 649），所以照 0.2.19 的 100 ms。本分支另加了统计：`Audio stats` 的 `decoderDropped`、`decoderStalls`、`decoderStallMaxMs` |
| GPS 航向未知时留空 | PR 109（DiPlay 0.2.9） | 2026-10-14，未提交 | 原来写 `0.00`，等于说车头朝正北 |
| 建热点前先检查蓝牙 | `2d2f0ec8`（PR 679，DiPlay 0.2.19） | 2026-10-14，未提交 | 蓝牙关着时不再先建 P2P 组再拆掉；本分支只移植了检查，没有移植 DiPlay 的提示文字 |
| 16/24 kHz Opus 的 RTP 时钟 | PR 644、547 | `ca89885`（1.4.0-byd.19） | 10-23 实车日志：type=101 default 流（0x10000000）实测 16000 Hz，/feedback 原按 48000 报。`AudioFormat.rtpClockRate` 取所选 Opus 格式位的采样率，/feedback 的 `sampleRate`、`sampleTime` 按它算；解码仍是 48 kHz，麦克风不变 |
| 检查更新 | 参考 PR 413、488、593（DiPlay 0.2.15–0.2.18）的做法，代码是本分支自己写的 | 2026-10-14，未提交 | 同样每天查一次、首页提示、点了才下载、用 SHA-256 校验。不同：本分支经车机 ADB 执行 `pm install` 安装，不弹系统安装器（DiPlay 遇到过车机安装器打不开）；没有 ADB 时把安装包放进“下载/xcertplay”，用文件管理器装 |
| 有线连接 InvalidHostID 时重新配对 | DiPlay 当前代码的 `rejectedPairRecordError` | 2026-10-14，未提交 | 本分支原来只认 InvalidPairRecord，InvalidHostID 会一直用旧记录重试；改写为 `LockdownPairRejection` |
| 音频线程优先级 | `656577fe`（DiPlay 0.2.20，issue 737：开 360 摄像头时音频包晚到 45–138 ms） | 2026-10-15，未提交 | 音频接收、播放和麦克风线程用 `THREAD_PRIORITY_AUDIO`；视频照原版 `1836c27` 用 `THREAD_PRIORITY_DISPLAY` |
| iOS 27 有线：上一个回复的尾巴后面跟着 VERSION 回复 | `19649861`（DiPlay 0.2.20，issue 100） | 2026-10-15，未提交 | 原样移植，连同 3 个测试 |
| 车机音频时钟和 iPhone 不一致 | `dfd7c3d6`（DiPlay 0.2.20，issue 610） | 只加了测量，未改行为 | Audio stats 行新增 `sendHz playHz clockDiffPct clockWindowSec`（`AudioClockDrift`）。DiPlay 的做法是微调播放速度（`MediaClockTrim`）。10-23 实车 18 分钟音乐 `clockDiffPct` 从 -0.26% 收敛到 -0.033%（开头的测量偏差），没有漂移，宋 PLUS 不需要 |
| 没有 ADB 时用系统安装器装更新 | `aa7cd204`（PR 593，DiPlay 0.2.18） | 2026-10-15，未提交 | 照 DiPlay：`FileProvider` 交给系统安装器，能装 APK 的应用不止一个时让用户选。不同：本分支仍先用车机 ADB；只在系统已允许本应用安装时直接打开（DiPlay 不预先检查），否则让用户选比亚迪文件管理器或照样试系统安装器；直接打开前先正常退出 CarPlay |
| 有线：USB 网卡（NCM）读数据改为异步请求，状态通知改为短查询 | DiPlay 最早公开的代码里就有（注释：`bulkTransfer` 等待时锁住数组，挡住 ART 垃圾回收，画面和声音约卡 0.8 秒） | 2026-10-15，未提交 | 本分支的 `NcmUsbBridge` 是原版未改过的，读数据和状态通知都用 `bulkTransfer`，状态线程每次等 1 秒；整份换成 DiPlay 的 `NcmUsbBridge`，加 `UsbRequestQueue`、`UsbReadQueuePolicy` |
| 有线：NCM 补位字节、按端点包长、坏数据块跳到下一个、接口设置重试 | `515bc9ec`、`5a5b5328`（DiPlay issue 565、558、518） | 2026-10-15，未提交 | 随上一行整份带上；跳过时会话日志有 `NCM resynchronized after …` |
| 有线：iPhone 接收第一帧前 NCM 写入只等 20 ms | `817ff3fa` 的一部分（DiPlay issue 634、335、301） | 2026-10-15，未提交 | 原来每次等 2 秒，拖慢 iAP2 握手；会话日志有 `NCM bulk-out ready after N dropped frames` |
| 有线：USBMUX 读在超时那一刻完成时不断线 | `1c93dc9b`（DiPlay issue 610、301） | 2026-10-15，未提交 | 只换了 `IphoneUsbHost.kt` 里的 `Iap2UsbSession`；DiPlay 在这个文件里的驱动分离、USB 配置切换等没有拿 |

| 同一局域网模式：JmDNS 启动前先给地址命名 | PR 972（DiPlay 0.2.21 之后） | 2026-10-25，1.4.0-byd.20 | JmDNS 即使给了名字也会读地址的主机名，未命名的地址会去查 DNS，比亚迪 Android 10 上每次连接前要 20 秒。只影响“现有 Wi-Fi / 同一局域网”模式，其他三种连接方式用系统 NSD |
| 双指手势：每根手指固定在一个触点 | PR 912（issue 444） | 2026-10-25，1.4.0-byd.20 | 原来按序号填触点，先抬起的手指松开后，另一根手指被当成新的按下，抬起的点跳到左上角（Apple 地图双指缩放会跳）。`AirPlayTouchSlots` 按 Android 指针编号分配触点，松开时留在原位 |
| 有线：经车机 ADB 预先允许 VPN | `732e1918`（DiPlay issue 604） | 2026-10-25，1.4.0-byd.21 | DiPlay 只在固件没有“连接请求”对话框时用；宋 PLUS 每次有线连接都弹这个框，所以本分支在需要同意时总是先试 ADB（`cmd appops set <包名> ACTIVATE_VPN allow`），不行再弹框；有设置项，默认开 |
| 分屏时铺满窗口 | `be57cfeb`、`ab070ce2`、`f8b8f53a`（2026-10-05 至 10-07，DiPlay 在唐 DiLink 5.0 上测过） | 2026-10-28，1.4.0-byd.22 | 手工改写：本分支在方形画布里每个屏幕方向多加一块分屏区域（`CarPlaySquareCanvas.withSplitAreas`），进出分屏时用 `updateViewArea` 切换；窗口按整屏比例记住，没见过时按屏幕、状态栏和分隔条推算（`CarPlaySplitScreen`）。需要旋转不重连的方形画布，DiPlay 的侧边面板、固定 Dock 的多套区域没有移植；设置项默认关 |
| 清单声明 `BYD_SUPPORT_SPLIT_ACTIVITY` | PR 875（DiPlay 0.2.21） | 2026-10-28，1.4.0-byd.22 | DiLink 7.0 据此把分屏的宽侧给应用；宋 PLUS（DiLink 3.0）的固件不读它，分屏看启动页的 `resizeableActivity`（`ScreenSplitUtils.isAppSuportSplit`），本应用已是可调整大小 |
| 有线：USB 设备过滤加上苹果 | PR 170（DiPlay 0.2.12，issue 11） | 2026-10-28，1.4.0-byd.23 | 过滤里没有苹果设备时，系统权限弹框不显示“默认情况下用于该 USB 设备”，iPhone 每次插线、连接中重新接入都要再问（用户反馈弹两遍）。本分支另外在 iPhone 刚接入时先等系统放行最多 10 秒再自己申请，避免两个弹框叠在一起。DiPlay 的无障碍服务自动点确定（PR 575）没有移植 |

DiPlay 已查看到 `9d896330`（0.2.21 之后，2026-10-24）。0.2.21 和之后的合并里没有拿的：音乐时钟微调的修复（本分支没有微调，10-23 实测无偏差）、有线重发启动请求等几项（等有线实车验证后再看）、通话后恢复音频模式和被打断后重新申请焦点（本分支不改音频模式，失去焦点也不静音）、仪表封面（本分支不发封面）、仪表地图、氛围灯律动、其他车型和界面。

## 上游开发分支（还没合入上游 `master`）

| 分支 | 最后提交 | 内容 | 备注 |
| --- | --- | --- | --- |
| `upstream/videoplayback` | `7a67a4c`（2026-09-27） | iOS 27 停车看视频的能力声明和基础组件，领先 2 个提交，约 2600 行 | 2026-10-12 本分支已从 DiPlay 移植停车看视频（PR #77、#129、#157，交接文档第 24 节），因为这个分支未完成、做法不同（自带 Opack 编解码和数据流实现）。它合入上游 `master` 后，逐个提交对照本分支的实现取舍，不要整体拿 |
| `upstream/iAP2-rewrite` | `e021581`（2026-09-30） | iAP2 重写、CarPlay Ultra 副屏（仪表）及模拟数据，领先 6 个提交，约 7000 行 | 宋 PLUS 2021 不支持仪表导航，暂不关注；合入上游 `master` 后按上表方式逐个看 |

## 提给上游的 PR

2026-10-12 提交，都基于上游 `551895a`（1.4.0），每个 PR 只做一项改动。分支在 fork 的 `pr/*`，本地工作区是与本仓库同级的 `xpr`（`git worktree`）。上游合并其中几个后，其余 PR 可能要同步上游再更新（多个 PR 改了 `CarPlayController.kt` 的不同位置）。

| PR | 内容 | 对应本分支 | 状态 |
| --- | --- | --- | --- |
| [#43](https://github.com/shilapi/xcertplay/pull/43) | iOS 27 有线 USBMUX 回复的填充字节（来自 DiPlay `3d0411d5`、`f7af3286`） | `e790975` | 待审 |
| [#44](https://github.com/shilapi/xcertplay/pull/44) | 同类型音频流按 audioType 区分 | `8f349e4` | 待审 |
| [#45](https://github.com/shilapi/xcertplay/pull/45) | 音频队列按时长（2 s）限制 | `f651a1f` 的音频队列部分 | 待审 |
| [#46](https://github.com/shilapi/xcertplay/pull/46) | P2P 和本地热点在射频忙时重试（不含信道选择） | `ccd3156`、`b8d547d` | 待审 |
| [#47](https://github.com/shilapi/xcertplay/pull/47) | 无线期间高性能和低延迟 Wi-Fi 锁 | `be4b0b3`，以及 `7d404ef` 的低延迟锁 | 待审 |
| [#48](https://github.com/shilapi/xcertplay/pull/48) | 定位请求从蓝牙续传到 Wi-Fi | `e901ee4`，以及 `2137a38` 的定位部分 | 待审 |
| [#49](https://github.com/shilapi/xcertplay/pull/49) | 收到 disableBluetooth 时断开 iPhone 蓝牙通话和音乐（可选，默认关） | `BluetoothHandoff` 相关提交 | 待审 |
| [#50](https://github.com/shilapi/xcertplay/pull/50) | 本地证书私有副本兜底，以及配置不合法时不闪退 | `4649b92`、`509284a` | 待审 |

Issue：[#51](https://github.com/shilapi/xcertplay/issues/51) Wi-Fi 直连优先用车机 Wi-Fi 当前的 5 GHz 信道（`0b9f560`、`7d404ef`），先讨论再决定提哪部分代码。

这次没提的：
- 设备 ID 按车机生成：上游无线连接用热点 BSSID、有线连接用 USB 网卡地址作设备 ID，写死的值只是兜底，价值小，还会让现有用户重新配对；
- 屏幕旋转不断连、简体中文界面：要基于上游 1.4.0 的新界面重写，暂不提。
