# 构建入口

## 当前HSAE目标

现代2023款途胜L、HSAE i.MX6 / Android4.4.3 的实际适配位于 `adaptation/receiver`。
请按 [Legacy构建说明](../adaptation/receiver/BUILD.md) 使用JDK11/Kotlin1.9.24/API18工具链。这是默认不带身份和TUN helper的源码/兼容性构建，不是完整可连接成品。

## 保留的现代宿主源码

根Gradle的shared/common/mobile/automotive是继承的现代Android宿主，不是API18/19 HSAE目标。它们的依赖由各Gradle文件定义，不能把Gradle assembleDebug当作本车机包。

本脱敏副本尚未完成现代宿主的构建/运行验证。当前GitHub Actions只做源码安全检查，不运行旧文档曾列出的Gradle测试或自动发布APK。不存在已验证的公开Release、对应tag或保证可连接的standalone产物；不要沿用上游历史验证数字。

运行身份、签名、外部组件及许可边界见SOURCE_ORIGIN.md、PUBLIC_ASSETS.md、VALIDATION_PUBLIC.md。原有历史文档有明确保留提示，其命令/下载/包名不自动适用于本仓库。
