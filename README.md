# double_proxy_vpn

Double Proxy 是一个 Android 全局代理 VPN 工具，支持 HTTP、HTTPS 和 SOCKS5 代理。

## 功能

- 保存多个代理配置
- 支持代理 host、端口、用户名、密码
- 用户名和密码默认以 `*` 隐藏，输入时显示明文
- 支持 HTTP / HTTPS / SOCKS5 代理连通性测试
- 通过 Android `VpnService` 启动全局代理
- 使用 `tun2socks` 接管全局 TCP 流量
- 本地 SOCKS 桥处理 HTTP/HTTPS/SOCKS 上游代理适配
- 前台服务通知，适合后台持续运行
- Double Proxy 应用名称和图标

## 开发环境

- Android Studio
- JDK 17
- Android SDK Platform 35
- Gradle Wrapper

## 构建

```bash
./gradlew assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release APK 需要先配置正式签名证书。

## 说明

当前内置 `arm64-v8a` 版本的 `tun2socks`，适配近年主流华为、荣耀、小米等 Android 手机。旧 32 位设备需要额外补充对应 native 内核。
