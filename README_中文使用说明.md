# 照片按日期归档 · Android 手机直连 Type‑C U 盘版

> **这才是 Android 手机上的 App**，与前面的 Windows Python/EXE 版本不同。
>
> 包内提供完整的 Android Studio/Gradle 原生 Java 项目和 Windows 一键构建 APK 的脚本。
> **当前文件包不含预编译 APK**：创建此项目的环境没有 Android SDK，无法对 APK 进行真实编译、签名或实机验证。请在 Windows 电脑上按下文步骤构建；生成 APK 后即可在小米手机上使用，不必每次通过电脑拷照片。

## 一、运行效果

打开 App → 选择 `手机/DCIM/Camera` → 选择 `USB U盘/照片归档` → 选择分类粒度 → **扫描预览** → **安全移动**。

例如，选择「按年月」：

```text
USB U盘 / 照片归档 /
    ├─ 2021 /
    │  ├─ 05 /
    │  │  ├─ IMG_20210518_143025.jpg
    │  │  └─ IMG_20210520_152007.jpg
    │  └─ 10 /
    │     └─ IMG_20211011_092318.heic
    ├─ 2024 /
    │  └─ 08 /
    └─ 未识别日期 /
       └─ 未能取得日期的照片.jpg
```

可切换：

- 按年：`2024/照片.jpg`
- 按年月：`2024/08/照片.jpg`
- 按年月日：`2024/08/15/照片.jpg`

不修改照片像素、不压缩、不重写 EXIF。

## 二、怎么生成可安装的 APK（电脑只用这一次）

需要一台 **Windows 10/11 电脑**、网络、[Android Studio](https://developer.android.com/studio)（含 Android SDK）和 Android SDK Platform 35。项目使用 Java 17、Gradle 8.9、Android Gradle Plugin 8.7.3。

1. 完整解压这个 ZIP，进入 `PhotoDateUSB_Android` 文件夹。
2. 如果没有 Android Studio，安装它，并打开 Android Studio 的 **SDK Manager**，安装 **Android SDK Platform 35** 和 **Build-Tools 35**。
3. **双击 `构建APK_Windows.bat`**。脚本会从 Gradle 官方下载编译工具（首次约 130 MB），生成 Gradle Wrapper，再编译 Debug APK。首次 Gradle 同步会自动下载 Android Gradle Plugin。
4. 看见 `SUCCESS` 后，在以下路径找到成品：

   `app\build\outputs\apk\debug\app-debug.apk`

5. 用数据线将 APK 复制到手机（例如 `Download` 目录），在小米系统文件管理器中打开 APK 安装。系统可能会要求你为文件管理器开启「允许安装未知应用」，只对你自己构建并信任的 APK 授权。

**以后只要在手机上打开 App、接上 U 盘操作即可，无须电脑。**

### 另一种构建方法

也可以直接用 Android Studio 打开本项目目录。首次本地一键脚本运行后会自动生成标准 Gradle Wrapper；或者按 Android Studio 提示安装 Gradle 8.9，再使用 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。构建完成的 `app-debug.apk` 位于相同目录。

项目另附 `.github/workflows/build-apk.yml`，适合熟悉 GitHub 的人使用 GitHub Actions 生成 Debug APK；不要求安装本地构建环境。

## 三、在小米手机上使用

1. **插入 Type-C U 盘**。先在小米「文件管理」中确认能看到 U 盘且能新建文件夹。
2. **在 U 盘上新建一个文件夹**，建议命名：`照片归档`。
3. 打开「照片按日期归档」App，点击 **选择手机照片文件夹**，在系统选择器里进入 `内部存储 > DCIM > Camera`，点击「使用此文件夹」并授予读写权限。
4. 点击 **选择 U 盘目标文件夹**，在文件选择器侧边栏选择 USB 存储设备（不是手机内置存储，也不要选云盘），进入刚才建好的 `照片归档`，点击「使用此文件夹」。
5. 选择 **按年 / 按年月 / 按年月日**，建议先保持「包含子文件夹」「从文件名推测日期」「SHA-256 校验」为开启；「使用文件修改时间」默认关闭。
6. 点击 **扫描并预览**，检查照片来源、拍摄日期与目标路径。
7. 核对没问题后点击 **确认安全移动**。请保持手机解锁、屏幕亮着，直到操作结束，不要拔出 U 盘。

> 部分 Xiaomi/HyperOS 系统上的文件选择器入口名称不同。如果 Android 11+ 不允许你选 U 盘根目录，这是系统文件夹授权限制，请在 U 盘内先建子文件夹，再选择该子文件夹。

### 关于日期

优先顺序：

1. **EXIF DateTimeOriginal**，即照片内部的原始拍摄时间。
2. **EXIF DateTimeDigitized**，无法读取原始拍摄时间时的备用元数据。
3. 如果勾选，从**文件名**解析 `IMG_20240518_143025.jpg`、`PXL_20240518143025123.jpg` 等。
4. 如果你主动勾选，最后才使用「文件修改时间」，但复制照片可能改变此值，因此默认关闭。
5. 仍然识别不出：`未识别日期`。

文件系统的「创建日期」在移动到 U 盘后可能改变，但照片文件内容和内部 EXIF 不变。按年月目录分类就是为了不依赖文件系统创建日期。

照片格式：JPG/JPEG、PNG、WEBP、HEIC/HEIF、AVIF、TIFF、DNG、BMP、GIF。读取 EXIF 的能力因 Android 版本和具体文件格式而异；HEIC/HEIF 推荐 Android 9（API 28）或更新系统，AVIF 建议 Android 12 或更新系统。**不处理视频**。

## 四、默认安全机制

- **无需宽泛的存储权限**：通过 Android 的 Storage Access Framework，让你自己挑选手机与 U 盘目录。
- **无 Internet 权限**：App 本身不上传照片。务必选择系统的 USB 存储设备，避免不小心选到其他云文件提供者。
- **源文件安全**：先在 USB 目录写入带 `.照片整理中_` 前缀的临时文件。
- **复制后验证**：记录源文件 SHA-256，检查 U 盘最终文件和重新读取的手机原件一致，才尝试删除源文件。
- **保留原照片字节内容**：不是重新编码，也不修改 EXIF。
- **不主动覆盖同名照片**：查看目标文件夹里的文件名，自动使用 `_001` 等编号。
- **取消操作**：复制中停止则清理未完成的临时文件、源文件不动；如果已经完成最终文件验证，则完成当前安全步骤并停止后续文件。
- **CSV 操作日志**：每张照片处理后写本地日志，结束时尝试另存到 U 盘根目录（即你选择的目标文件夹）。
- **USB 断开 / 无空间**：写入或校验失败时不会删除这张手机原件。

### 重要限制

1. 本项目尚未在实际小米手机和各种 U 盘上实机测试。请**先用 10 张非唯一备份的照片试验**，确认日期、目标目录、删除行为正确再处理大量照片。
2. 这个首版没有前台服务：**务必保持 App 打开且屏幕亮着**，不要锁屏/切到后台进行长时间移动。系统仍可能因电量、内存和 USB 断开中断任务。
3. 断电或 App 被系统强制杀死时，U 盘可能剩下 `.照片整理中_...` 临时文件；这些是未完成的副本，请检查后再手工处理。**不要未经确认直接删原件。**
4. 不同 Android 文件提供者的重命名、文件大小、访问权限实现不完全相同。同名文件碰撞采用先检查再重命名；**外部其他应用恰好同时修改同一目标目录**时，不可能在所有第三方提供者上提供原子级防覆盖保证。运行中不要让其他程序同时改 U 盘目录。
5. 手机和 U 盘应有足够供电与存储空间。FAT32 单文件上限约 4GB；大量照片建议使用兼容手机的 exFAT U 盘。
6. Windows 上生成的 Debug APK 是开发调试签名包，适合个人侧载，不代表已经通过 Google Play 发布审核。
7. 不处理 Google Photos 云端只有缩略图、未下载到本地目录的内容。
8. App 只删除逐张完成校验的源照片，不会帮你批量删除空文件夹。
9. **最好先保留另一份独立备份**，移动硬盘不是备份的唯一副本。

## 五、源码结构

```text
PhotoDateUSB_Android/
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/photodateusb/organizer/
│     │  ├─ DateRules.java           # 拍摄日期解析，按年/月/日路径
│     │  ├─ TransferEngine.java      # SAF 扫描、复制、校验、删除与日志
│     │  └─ MainActivity.java        # 中文图形界面
│     └─ res/                        # 主题、名字、图标
├─ settings.gradle
├─ build.gradle
├─ gradle.properties
├─ 构建APK_Windows.bat
├─ tests/DateRulesTest.java
└─ .github/workflows/build-apk.yml
```

无广告、无服务器、无账号登录要求。欢迎按需修改。

### 本地规则测试（JDK 17）

不需 Android SDK：

```powershell
javac -encoding UTF-8 -d tests\.runclasses app\src\main\java\com\photodateusb\organizer\DateRules.java tests\DateRulesTest.java
java -cp tests\.runclasses DateRulesTest
```

当前在提供项目的环境中已使用 JDK 运行 519 个日期规则断言，均通过。**尚未完成 Android APK 编译与实机 SAF/U 盘测试。**

## 六、Android 参考文档

- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/android/provider/DocumentsContract
- https://developer.android.com/reference/android/media/ExifInterface
