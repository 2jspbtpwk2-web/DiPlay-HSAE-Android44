# 代码出处、作者与版本关系

## 来源表

| 组成 | 来源 | 本仓库保留的证据/范围 |
| --- | --- | --- |
| DiPlay 接收器与宿主工程 | [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) | 保留 shared/common/mobile/automotive、原作者注释及历史 README |
| iAP2 / AirPlay 等基础协议 | [shilapi/xcertplay](https://github.com/shilapi/xcertplay) | 根 GPLv3 与 docs/UPSTREAM-README.md；不是本适配作者原创全部协议 |
| 部分上游主页/设置 UI | [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto) | common 中的 AGPL-3.0-only 文件声明、docs/licenses/DiAuto-AGPL-3.0.txt |
| 协议研究致谢 | 上游致谢的 LIVI / Showcase | 见 THIRD_PARTY_NOTICES.md 与原源码注释；致谢不等于把所有文件归为这两个项目 |
| Android 4.4 / HSAE 本地适配 | adaptation/receiver、adaptation/probe | 原生界面、USB/NCM/TUN 集成、旧 SDK 替换、诊断、媒体优化及有限自学习 |
| 认证解密实现 | Monocypher 4.0.2 | native/screencrypto/vendor 源码和双许可原文；JNI 封装及构建脚本随仓库 |
| JVM/Android 依赖 | Kotlin、Bouncy Castle、JmDNS、SLF4J、AndroidX 等 | legacy 固定版本见 dependency-sha256.json/fetch_dependencies.py，现代工程见 Gradle 声明 |

Legacy 使用 Bouncy Castle 1.79、JmDNS 3.5.9、SLF4J 1.7.36、Kotlin 编译器 1.9.24。现代 Gradle 子项目有自己的依赖声明，不能混为相同构建。Monocypher、Bouncy Castle、Apache/MIT 等全文保留在 vendor、docs/licenses 与 assets/licenses。

## 版本关系

- `0.60-tucsonl / 21040` 是本地途胜 L 适配版，不是上游 DiPlay 0.2.60。
- 历史 DiPlay README 标为 0.2.8；历史 legacy 0.21 README 记录过 `f2d06951b4e8114dbb62f551c12a32a845a3042f` 作为其基础快照。
- 提供的工程没有原 Git 历史。目录名中的提交形状字符串、旧 README 记录，均不能证明当前所有文件逐字节对应某一个上游提交。后续已存在本地修正。本次公开以实际文件指纹和新建干净提交为准，不伪造历史提交或原作者身份。
- 公开副本移除了私有运行资产、未经确认许可素材和本机记录，并调整构建输入/占位资源；不是原私有 APK 的字节相同重建包。
- `docs/UPSTREAM-*` 是保留的历史出处材料。其比亚迪、无线功能、下载地址、版本和验证描述属于对应上游/历史版本；当前适配范围以根 README 和 COMPATIBILITY.md 为准。

## 许可边界

接收器主体沿用 GPLv3，部分继承 UI 带 AGPLv3 文件级声明，第三方库保留其原许可。没有删除作者署名，也没有改成 MIT 或宣称全部代码归本适配作者独有。

Apple 的图标、现代车图/车标、BYD 图标等不因为和 GPL 源码同目录就自动获得代码许可；本公开副本排除了未明许可素材。具体替代与未公开组件见 PUBLIC_ASSETS.md。认证私钥/证书不属于公开源代码，本仓库不提供、下载或重新许可它们。
