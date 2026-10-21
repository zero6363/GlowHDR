# GlowHDR

**把普通 SDR 照片变成 Ultra HDR 照片的 Android 应用。**

GlowHDR 用纯 Kotlin 手写封装 Ultra HDR（JPEG_R）文件，把任意 SDR 图片转换为 Android 14+ 原生支持的 HDR 照片：相册在 HDR 屏幕上按高亮度显示，普通设备上自动退化为普通 JPEG。全部参数可调、实时预览，支持预设与批量处理。

[![Release](https://img.shields.io/github/v/release/zero6363/GlowHDR?label=release&color=ff6b35)](https://github.com/zero6363/GlowHDR/releases/latest)
[![Android](https://img.shields.io/badge/Android-14%2B%20(API%2034)-3ddc84?logo=android&logoColor=white)](https://developer.android.com/about/versions/14)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285f4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/license-see%20below-lightgrey)](#声明)

---

## 下载安装

前往 **[Releases](https://github.com/zero6363/GlowHDR/releases/latest)** 下载最新 APK：

| 文件 | 说明 |
| --- | --- |
| `GlowHDR-v1.1.1-release.apk` | 已签名 release 包，直接安装 |

- 系统要求：**Android 14（API 34）及以上**。
- 要看到 HDR 提亮效果需要 **HDR 屏幕**（近两年的旗舰/中高端机型）；非 HDR 屏幕上只能看到 SDR 基础图。
- 首次安装如提示「未知来源」，在系统设置中允许安装即可。
- APK 使用固定证书签名，SHA-256 指纹：`B5:DA:8B:90:7C:C3:29:BA:E8:2C:7F:0D:1B:A3:60:13:1F:3D:DD:FC:A6:BA:7C:B2:19:87:76:F5:15:83:08:B6`

## 功能特性

### Ultra HDR 生成
- 为 SDR 图片生成 HDR 增益图（gain map），输出兼容 Android 14+ 的 **Ultra HDR JPEG（JPEG_R）**。
- 用 Google 相册、系统相册等查看时，HDR 屏幕按峰值亮度显示；普通屏幕自动退化为 SDR。
- 文件结构按 Ultra HDR Image Format 规范手工封装（主图 JPEG + XMP + MPF + 增益图），与 Google libultrahdr 的输出字节布局一致。

### Ultra HDR 再编辑
- 选择已经带增益图的 Ultra HDR 照片时自动识别（标题旁显示 `Ultra HDR` 标记）。
- 沿用原图增益分布，只重写峰值亮度、增益伽马等元数据，避免二次生成导致的画质损失。

### 实时预览
- 选图后拖动滑块即时预览（30ms 防抖 + 单线程串行渲染），预览与导出共用同一处理管线，所见即所得。
- 可一键切换 **「HDR 效果 / SDR 原样」** 对比，直观看到增益图带来的变化。

### 参数

| 分组 | 参数 | 范围 | 作用 |
| --- | --- | --- | --- |
| 基础调整 | 亮度（曝光） | -2 ~ +2 EV | 整体明暗 |
| | 对比度 | -1 ~ 1 | 影调反差 |
| | 伽马 | 0.2 ~ 3.0 | 中间调明暗曲线 |
| | 饱和度 | 0 ~ 2× | 色彩浓度 |
| | 色温 | -1（冷）~ 1（暖） | 白平衡倾向 |
| HDR 增益 | 峰值亮度 headroom | 1 ~ 8× | HDR 相对 SDR 白点的最大提亮倍数 |
| | 高光权重 | 0 ~ 1 | 增益集中在高光（1）还是全画面（0） |
| | 增益伽马 | 0.5 ~ 2.0 | 增益图亮度映射曲线 |
| 导出设置 | JPEG 质量 | 60 ~ 100 | 主图编码质量（默认 95） |
| | 输出分辨率 | 原图 / 4096 / 2048 | 按长边限制并做像素上限保护 |

### 预设
- 把当前参数保存为命名预设，一键套用；支持管理与删除。
- 预设持久化到 `filesDir/presets.json`，重启不丢失。

### 批量生成
- 一次选择多张图片（可多次追加，自动按 URI 去重，缩略图并排预览）。
- 选择参数来源：当前编辑参数或任一预设。
- 逐张显示进度与成败状态；再次开始时只处理未完成的图片，不重复导出。

### 轻量、无权限烦恼
- 选图走系统相册选择器，保存通过 `MediaStore` 写入 `Pictures/GlowHDR`，**无需申请任何存储权限**。
- 不联网、不上传，全部处理在本地完成。

## 使用

1. 打开 App，点右上角「相册」图标或空状态的「选择图片」，选一张照片。
2. 拖动参数滑块，观察预览；用「HDR 效果 / SDR 原样」切换对比。
3. 需要复用参数时，点「保存预设」命名保存；顶部预设胶囊可一键套用。
4. 点「导出」保存到相册 `Pictures/GlowHDR`。
5. 切到「批量」页，选择多张图片，选择参数来源，点「开始批量生成」。

> 提示：HDR 效果需要在支持 HDR 的屏幕并关闭「省电模式」等可能限制亮度的设置才能看到完整亮度。

## 构建

### 环境
- JDK **17**
- Android SDK Platform **35** + Build-Tools **35.0.0**
- Android Studio（推荐）或命令行 Gradle

项目使用 Gradle Wrapper（Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21），无需单独安装 Gradle。

### 命令行

```bash
# 调试包
./gradlew :app:assembleDebug

# 发布包（自动使用根目录 keystore.properties 中的签名配置）
./gradlew :app:assembleRelease
```

产物路径：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

Windows 下也可以直接双击仓库根目录的 `发布Release.bat`，脚本会自动寻找 JDK/SDK 并完成打包。

### 签名配置

签名信息放在仓库根目录的 `keystore.properties`（**已被 `.gitignore` 忽略，不要提交**）：

```properties
storeFile=keystore.jks
storePassword=******
keyAlias=glowhdr
keyPassword=******
```

- `storeFile` 相对于仓库根目录，例如 `keystore.jks`。
- 找不到 `keystore.properties` 时，release 构建会退回 debug 签名，方便本地快速验证（该产物不能用于正式分发）。

生成新的签名文件：

```bash
keytool -genkeypair -v -keystore keystore.jks -alias glowhdr \
  -keyalg RSA -keysize 2048 -validity 10000
```

## 发布流程

### 自动（GitHub Actions）

推送 `v*` 标签即触发 `.github/workflows/release.yml`：构建 release APK 并创建 GitHub Release。

需要在仓库 **Settings → Secrets and variables → Actions** 中配置：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | `keystore.jks` 的 Base64：`base64 -w0 keystore.jks`；Windows PowerShell 用 `[Convert]::ToBase64String([IO.File]::ReadAllBytes('keystore.jks'))` |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

未配置 `KEYSTORE_BASE64` 时仍可构建，但产物为 debug 签名，仅供测试。

### 手动

```bash
# 1. 修改 app/build.gradle.kts 中的 versionCode / versionName
./gradlew :app:assembleRelease
# 2. 上传 app/build/outputs/apk/release/app-release.apk，重命名为 GlowHDR-vX.Y.Z-release.apk
# 3. 打 tag 并发布
git tag v1.1.1 && git push origin v1.1.1
```

## 项目结构

```
app/src/main/java/com/glowhdr/app/
├── MainActivity.kt            # 入口，底部「编辑 / 批量」导航
├── hdr/
│   ├── HdrParams.kt           # 参数模型、预设、导出分辨率档位
│   ├── ImageProcessor.kt      # 基础调色 + 增益图生成 + 预览用 Gainmap 对象
│   ├── UltraHdrExporter.kt    # Ultra HDR(JPEG_R) 字节封装与 MediaStore 写入
│   └── ImageLoader.kt         # 解码、方向校正、按像素上限降采样
├── data/
│   └── PresetStore.kt         # 预设持久化（presets.json）
├── ui/
│   ├── EditorScreen.kt        # 编辑页：预览、参数、预设
│   ├── BatchScreen.kt         # 批量页：选图、进度、结果
│   ├── Components.kt          # 通用控件
│   └── theme/Theme.kt         # Material 3 主题
└── vm/
    └── AppViewModel.kt        # 状态、预览调度、导出与批量任务
```

## 技术说明

### 为什么不用系统 API 直接写 JPEG_R？
Android 至今没有公开的 `Bitmap.CompressFormat` 支持 JPEG_R 输出。GlowHDR 按 **Ultra HDR Image Format v1.0** 规范手工拼接字节流：

```
主图 JPEG：SOI, APP1(XMP: GContainer 目录), APP2(MPF), ... , SOS..EOI
次图 JPEG：SOI, APP1(XMP: hdrgm 增益元数据), 增益图 JPEG（去掉自身 SOI）
```

同时写入 ISO 21496-1 的 APP2 元数据，Android 14+ 的系统解码器（libultrahdr / Skia）可直接识别。

### 增益图语义

编码端为每个像素计算提升倍数，解码端按规范还原：

```
boost = headroom ^ (stored ^ (1 / gamma))
```

- 最小增益为 1，`OffsetSDR = OffsetHDR = 1/64`。
- 增益图为原图 1/2 分辨率（规范允许），以 JPEG 质量 90 编码。
- `boost` 按 `log2` 存入 XMP 的 `GainMapMin / GainMapMax / HDRCapacity`，与 libultrahdr 一致。
- 框架层 `android.graphics.Gainmap` 的 gamma 与文件内 XMP gamma 互为倒数，代码中已做转换，保证预览与成图一致。

### 预览

- 处理后的位图通过 `Bitmap.setGainmap()` 附加 `android.graphics.Gainmap`，交给系统硬件合成显示。
- Activity 设置 `COLOR_MODE_HDR`，以便在 Android 14 上触发 HDR 输出。
- 预览按最长边 1080、最多 8MP 解码，拖动滑块时约 30ms 防抖，渲染在单线程调度器上串行执行，避免大图抢占内存。

### 大图与内存安全
- 解码时按目标分辨率和像素上限（原图档 24MP）下采样。
- 导出遇到 `OutOfMemoryError` 会自动降档重试。
- 增益图数组原地复用，减少 GC 压力；该应用声明了 `largeHeap`。

## 已知限制

- 需 **Android 14+**；HDR 显示效果取决于硬件与系统亮度策略，低亮度环境下系统可能自动限制峰值亮度。
- 仅保留 EXIF 方向（解码时已摆正像素），其他 EXIF 信息（机型、GPS、拍摄参数等）不会写入输出文件。
- Ultra HDR 再编辑只调整增益元数据，不重新生成增益分布。
- 部分相册/社交 App 会剥离 HDR 元数据，分享后可能只剩 SDR 效果。

## 更新日志

### v1.1.1
- 首发公开版本：SDR → Ultra HDR 转换、实时 HDR/SDR 对比预览、预设、批量生成。
- 支持识别并再编辑已有 Ultra HDR 照片。
- 纯 Kotlin 实现 Ultra HDR(JPEG_R) 封装，无需 native 依赖。

完整历史见 [Releases](https://github.com/zero6363/GlowHDR/releases)。

## 声明

本项目仅供学习与个人使用。Ultra HDR 是 Google/Android 的规范与商标，本项目与 Google 无关联。
