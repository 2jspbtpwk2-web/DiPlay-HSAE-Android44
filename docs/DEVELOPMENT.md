# 开发架构与维护边界

## 目录

- `adaptation/receiver/src/local/airuize/receiver`：API18原生界面、HSAE连接、媒体、诊断与学习。
- `adaptation/receiver/prepare_sources.py`：从shared选择协议源码，并对旧SDK做显式替换，不直接覆盖shared。
- `shared/src/main/java/com/shilapi/xcertplay`：AirPlay/iAP2、USB/NCM、加密、位置和媒体基础实现。
- `adaptation/receiver/native`：USB接口shim；screencrypto及Monocypher源码/许可。
- `adaptation/probe`：独立能力诊断工具。
- `common/mobile/automotive`：继承的现代Android宿主工程，不等于API18车机目标。
- `adaptation/receiver/tests`、`test-support`、`shared/src/test`：纯测试和运行场景源码；公开状态见VALIDATION_PUBLIC.md。

## 成功连接路径应保留的条件

Apple设备枚举 → 必要时切CarPlay模式/等待重枚举 → 关闭旧handle再切真实内核USB配置 → ROOT TUN和IPv6 → USBMUX握手 → NCM接口初始化/真实alternate setting → Lockdown配对及TLS → 启动NCM与TUN的桥 → AirPlay listener就绪 → iAP2启动 → AirPlay认证/SETUP/RECORD → 首视频/解码。

当前目标有USB配置6、NCM数据alt1及作用域IPv6要求。TLS1在carkit生命周期内保持；监听先于iAP；一般TLS/I/O错误不能随意清配对。保留已成功的等待/相同报文重试和资源按代隔离，不能只因为一条ETIMEDOUT就缩预算。旧owner回收不许关闭新owner资源。

## 媒体与学习

原生认证解密保持认证失败拒绝与BC后备语义。全部压缩H264参考帧保序，已解码输出可追最新；局部入队年龄不等于实显/端到端延迟。保留音质/速度，不主动裁剪完整PCM；音频失败worker及时退役。触摸的可选学习状态发布不等待观察/偏好保存锁；媒体统计格式化移到RTP入口锁外。实际音频队列/连续性处理仍有短锁，不宣称整个媒体路径无锁或绝不阻塞。

学习只采用满足前台/活动/代际条件的样本，变化仅作用下一次会话。方向盘转发与正常连接独立，校准和录音资源互斥。长ROOT诊断只回收自己创建且身份匹配的进程，不能全局按进程名杀系统服务。

## 公开源代码差异

公开副本只整理发布输入/脚本、占位图标和说明，协议和产品Kotlin/Java沿用0.60本地源。默认不带运行身份、签名及TUN helper，不能把源码构建说明写成完整实车可连接承诺。补齐helper合法来源/可重建源后，才可逐步建立完整公开构建与硬件回归。
