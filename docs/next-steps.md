# 下一步开发清单

## 1. 确认代理内核

建议选择其一：

- `sing-box`: 推荐，适合长期维护和扩展
- `tun2socks`: 简单轻量，适合只做 HTTP / SOCKS 转发

## 2. 接入内核

需要把 `ProxyVpnService` 中的：

```java
vpnInterface = builder.establish();
```

得到的文件描述符传给内核，让内核读取 tun 流量并转发到代理。

## 3. DNS 策略

必须明确：

- DNS 是否走代理
- 是否使用远程 DNS
- 是否禁用本地 DNS
- 国内环境是否需要可配置 DNS

如果 DNS 不处理，会出现 DNS 泄露或部分 App 无法访问。

## 4. 后台保活

国产系统需要补充引导页：

- 电池优化白名单
- 自启动权限
- 后台运行权限
- 锁定最近任务

这些权限很多无法由 App 自动开启，只能跳转设置页并提示用户。

## 5. 签名 APK

准备一个 release keystore：

```bash
keytool -genkeypair -v \
  -keystore proxy-vpn-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias proxy-vpn
```

然后在 Gradle 中配置签名，生成正式 APK。

