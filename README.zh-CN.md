# DiPlay HSAE · 现代 2023 款途胜 L

面向 **现代 2023 款途胜 L、航盛 HSAE 老款 Android 车机** 的有线 Apple CarPlay 接收端适配。软件安装在车机，连接 iPhone 后显示 CarPlay，并处理触摸、声音、Siri 和可选车机功能。

本仓库是 **0.60 适配工程的脱敏源码版**，不是 DiPlay 官方发布，也不是 Apple 或现代汽车的官方产品。版本号 `0.60-tucsonl` 是本适配分支编号，不是上游 DiPlay 的版本号。

## 适配对象

| 项目 | 当前资料 |
| --- | --- |
| 用户实车 | 现代 **2023 款途胜 L** |
| 车机厂商/型号 | 航盛 Hangsheng / HSAE，SABRESD-MX6DQ |
| 产品代号 | `nx4c_avnt_imx6q` |
| 系统 | Android **4.4.3 / API 19** |
| 处理器 | Freescale i.MX6，32 位 ARM；设备资料报告双核 |
| 屏幕 | 竖屏 **960 × 1280**；系统/OEM 保留区域后应用约 960 × 1150 |
| 连接 | 有线 USB；该适配目标需要数据 USB 口及 ROOT 能力 |
| 手机反馈 | iPhone 16 Pro Max，用户报告 iOS 27.0.1；不是全部 iPhone/iOS 的认证列表 |

**车型相同不代表车机相同。** 兼容性应以车机硬件、系统、USB 和解码器为准；没有验证所有年款、配置、改装车机或全部 2023 款途胜 L。具体配置与固件差异见 [适配范围](docs/COMPATIBILITY.md)。

## 能做什么

- 有线 CarPlay 接收、竖屏等比显示、H.264 硬件解码和触摸回传。
- 音乐、导航提示和语音通道；保持协商的采样率、声道、编码及播放速度。
- 设置画面模式、帧率、音量、启动缓冲、麦克风和 GPS。
- 本地规则驱动的自动画面优化、方向盘短按识别引导、麦克风候选检查和 GPS 质量观察。
- 问题简报、运行日志、完整超级诊断与方向盘按键采集；采集结果留在车机。

功能存在不等于本机效果已全部验证。方向盘、物理麦克风、手机采用车机 GPS 和长期流畅度仍需实车反馈。自学习是**本地有限规则与记忆**，不是云端 AI，也不会自动获得任意车型的 CAN 协议。

## 公开源码与可连接安装包的区别

公开仓库**没有认证私钥/证书、签名库、配对记录、实车日志、现成 APK 或历史压缩包**。没有自动下载认证资料的步骤。

默认构建是无身份的源码/兼容性构建，不能直接当作能够独立连接 iPhone 的成品。完整运行还需要合法可用的外部认证输入，以及本分支所需、当前尚未补齐公开源码与许可来源的 TUN helper。这个 helper 二进制没有随仓库发布。详见 [构建说明](adaptation/receiver/BUILD.md) 和 [公开素材与缺口](docs/PUBLIC_ASSETS.md)。

原私有测试版中的车图、车标及未明许可的图标/测试媒体已排除。公开源码使用可复现的原创占位图标，外观与原测试 APK 有差别。

## 阅读顺序

| 文档 | 内容 |
| --- | --- |
| [代码来源与版本](docs/SOURCE_ORIGIN.md) | DiPlay、xcertplay、DiAuto、第三方依赖和本地适配边界 |
| [适配车型与硬件](docs/COMPATIBILITY.md) | 2023 款途胜 L 的具体车机、权限和验证范围 |
| [完整使用说明](docs/USER_GUIDE.md) | 安装、连接、四个入口、全部设置、自学习和常见问题 |
| [诊断与隐私](docs/DIAGNOSTICS_AND_PRIVACY.md) | 如何一次采集、报告包含什么、如何安全反馈 |
| [Legacy 构建](adaptation/receiver/BUILD.md) | API18/19 工具链、外部输入、签名及构建限制 |
| [开发架构](docs/DEVELOPMENT.md) | 源码目录、连接顺序、所有权和优化边界 |
| [验证状态](docs/VALIDATION_PUBLIC.md) | 原测试版、公开源码副本与实车证据的区别 |
| [发布与贡献](docs/RELEASING.md) | 安全检查、提交流程和发布要求 |
| [许可及第三方声明](docs/THIRD_PARTY_NOTICES.md) | GPL/AGPL、依赖、保留署名和排除素材 |

## 代码出处与许可证

本项目基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，其接收器源自 [shilapi/xcertplay](https://github.com/shilapi/xcertplay)；部分上游 UI 来源于 [DiAuto](https://github.com/shihabal3amri/DiAuto)。不是从零独立实现，也不冒用上游作者的支持承诺。

保留根 [GPLv3](LICENSE)、文件级版权、AGPLv3 UI 声明及第三方许可。不能把全部文件统一改称 MIT。详细来源、历史快照不能确认的部分和依赖版本见 [SOURCE_ORIGIN.md](docs/SOURCE_ORIGIN.md)。

## English summary

This is a sanitized source adaptation for a **2023 Hyundai Tucson L with an HSAE i.MX6 Android 4.4.3 portrait head unit**. It focuses on wired CarPlay. The car model alone does not establish compatibility. Credentials, pairing records, signing keys, vehicle reports and APKs are excluded. Default source builds are not standalone authenticated receivers; the TUN-helper source/provenance gap is explicitly documented. Preserve all original GPL/AGPL and third-party notices. Desktop checks do not establish real vehicle latency or iPhone connectivity.
