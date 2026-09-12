# NiuTrip Android

旅行牛牛 Android 客户端，使用 Kotlin、Jetpack Compose、Room、Retrofit 与高德地图 SDK。

## 环境

- JDK 17 或更高版本
- Android SDK 34
- Android Studio Hedgehog 或更高版本
- 高德组合 SDK 11.2.100（含地图、定位与搜索，支持 16KB page size）
- 默认连接部署在 `https://niutrip.gyberpunk123.asia/` 的后端

在 `android/gradle.properties` 或用户级 `~/.gradle/gradle.properties` 中配置高德 Key：

```properties
AMAP_KEY=your_android_amap_key
```

API 地址与分享链接域名默认使用线上服务。本地联调时，可以改为后端所在机器的局域网 IP（模拟器也能访问宿主机局域网 IP，覆盖后两者通用）：

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

发布前先提交所有功能改动并切换到 `main` 分支，然后运行发布脚本。未指定版本时，脚本会将
最新 Git 标签的补丁版本加一，例如从 `v0.2.0` 推算为 `v0.2.1`：

```bash
./scripts/release.sh
```

也可以手动指定版本号；`v` 前缀可省略：

```bash
./scripts/release.sh 0.3.0
./scripts/release.sh v1.0.0
```

只查看脚本推算的版本，不修改文件或推送：

```bash
./scripts/release.sh --dry-run
./scripts/release.sh --dry-run 0.3.0
```

脚本会更新 `versionCode` 和 `versionName`，运行单元测试并构建本地 APK，创建版本提交与
Git 标签，然后将 `main` 和标签原子推送到 GitHub。标签会触发 GitHub Actions 构建可安装
的 APK、生成 SHA-256 文件并创建同名 Release；脚本会等待并输出最终下载地址。

仓库可在 GitHub 的 Actions Variables 中配置 `API_BASE_URL` 和 `SHARE_BASE_URL`，
在 Actions Secrets 中配置 `AMAP_KEY`。未配置地址变量时，APK 默认连接
`https://niutrip.gyberpunk123.asia`；需要 USB 联调时可在本地通过 Gradle 属性覆盖。

当前流程使用 Android 调试签名，以便无需分发密钥也能自动生成可安装 APK。正式发布前应改为
独立且妥善备份的 Release 签名，并在 GitHub Secrets 中保存加密后的签名材料；同一应用后续
版本必须使用相同签名，才能覆盖升级。

## 功能

- 手机号/邮箱登录与注册
- 我的轨迹/分享给我的双列表
- 自动或仅手动轨迹、前台定位服务、离线队列补传
- 高德地图按天分色、起终点标记、静止漂移过滤与日期切换
- 手动图文打卡支持拍照或相册选择，超过 1 MB 的图片自动压缩
- 私密/一次性/公开分享与 Deep Link 保存
- 资料、安全与权限管理

地图通过 `TextureMapView` 承载，以兼容 Compose `AndroidView` 与 16KB Android 模拟器的 EGL 渲染。

## 模拟器注意事项

- Django 的 `ALLOWED_HOSTS` 必须包含 `10.0.2.2`，模拟器才可注册和登录。
- Pixel_10 Android 37 16KB 镜像的 Host GPU 与高德 EGL 不兼容时，会出现 `createContext failed: 12288`。在 Device Manager 的 AVD Graphics 中选择 Software/SwiftShader，并执行 Cold Boot。
- 本机 `Pixel_10` 已设置为 `hw.gpu.mode=swiftshader`、强制冷启动，并完成“薄刀峰星空之旅”详情页回归。
