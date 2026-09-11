# NiuTrip Android

旅行牛牛 Android 客户端，使用 Kotlin、Jetpack Compose、Room、Retrofit 与高德地图 SDK。

## 环境

- JDK 17 或更高版本
- Android SDK 34
- Android Studio Hedgehog 或更高版本
- 高德组合 SDK 11.2.100（含地图、定位与搜索，支持 16KB page size）
- 后端默认运行在 `http://10.0.2.2:8000/`（Android 模拟器访问宿主机）

在 `android/gradle.properties` 或用户级 `~/.gradle/gradle.properties` 中配置高德 Key：

```properties
AMAP_KEY=your_android_amap_key
```

API 地址与分享链接域名（默认分别为 `http://10.0.2.2:8000/api/` 模拟器别名、`http://127.0.0.1:8000` 本机）。真机调试或让局域网内他人访问时，改为后端所在机器的局域网 IP（模拟器也能访问宿主机局域网 IP，覆盖后两者通用）：

```properties
API_BASE_URL=http://192.168.1.100:8000/api/
SHARE_BASE_URL=http://192.168.1.100:8000
```

### USB 真机调试

手机开启“开发者选项”和“USB 调试”并通过数据线连接后，执行：

在 Android 仓库根目录执行：

```bash
./scripts/usb-reverse.sh
```

随后将 `API_BASE_URL` 配置为 `http://127.0.0.1:8000/api/`。ADB 会把手机访问的
`127.0.0.1:8000` 转发到开发电脑的 `8000` 端口，无需依赖 Wi-Fi 或局域网 IP。
拔线、重启手机或重启 ADB 后需要重新执行脚本。`SHARE_BASE_URL` 不应改为
`127.0.0.1`，它仍需使用接收分享者能够访问的域名或局域网地址。

## 构建与测试

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## GitHub Release

推送 `v*` 标签后，GitHub Actions 会运行单元测试、构建可安装的测试 APK，并自动创建
同名 GitHub Release：

```bash
git tag v0.1.0
git push origin v0.1.0
```

仓库可在 GitHub 的 Actions Variables 中配置 `API_BASE_URL` 和 `SHARE_BASE_URL`，
在 Actions Secrets 中配置 `AMAP_KEY`。未配置时，APK 默认连接
`http://127.0.0.1:8000`，适合配合 `scripts/usb-reverse.sh` 跑通测试流程。

当前流程使用 Android 调试签名，以便无需分发密钥也能自动生成可安装 APK。正式发布前应改为
独立且妥善备份的 Release 签名，并在 GitHub Secrets 中保存加密后的签名材料；同一应用后续
版本必须使用相同签名，才能覆盖升级。

## 功能

- 手机号/邮箱登录与注册
- 我的轨迹/分享给我的双列表
- 自动或仅手动轨迹、前台定位服务、离线队列补传
- 高德地图按天分色、日期切换、手动图文打卡
- 私密/一次性/公开分享与 Deep Link 保存
- 资料、安全与权限管理

地图通过 `TextureMapView` 承载，以兼容 Compose `AndroidView` 与 16KB Android 模拟器的 EGL 渲染。

## 模拟器注意事项

- Django 的 `ALLOWED_HOSTS` 必须包含 `10.0.2.2`，模拟器才可注册和登录。
- Pixel_10 Android 37 16KB 镜像的 Host GPU 与高德 EGL 不兼容时，会出现 `createContext failed: 12288`。在 Device Manager 的 AVD Graphics 中选择 Software/SwiftShader，并执行 Cold Boot。
- 本机 `Pixel_10` 已设置为 `hw.gpu.mode=swiftshader`、强制冷启动，并完成“薄刀峰星空之旅”详情页回归。
