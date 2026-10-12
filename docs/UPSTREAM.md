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

- 上游 `master`：`551895a`（1.4.0，2026-10-11），2026-10-12 查看。
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

## 上游开发分支（还没合入上游 `master`）

| 分支 | 最后提交 | 内容 | 备注 |
| --- | --- | --- | --- |
| `upstream/videoplayback` | `7a67a4c`（2026-09-27） | iOS 27 停车看视频的能力声明和基础组件，领先 2 个提交，约 2600 行 | 本分支要做停车看视频前，先看它的进展，再决定从上游拿还是参考 DiPlay（PR #77、#129、#157） |
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
