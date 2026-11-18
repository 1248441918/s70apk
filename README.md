# S70 坏点修复 · 安卓版

把桌面版 `s70-pixel-mapper` 的算法搬到了手机上：插上读卡器 → 授权一次 → 生成映射 → 批量修复卡里的照片。

## 先说清楚：这里没有生成 APK

这台机器上**没有 JDK、没有 Android SDK、也没有可用的外网**（下载 JDK/SDK 的域名全部不可达），所以无法在这儿编译出 `.apk`。代码是完整的 Android 工程，下面两条路任选一条，几分钟就能拿到 APK：

### 路线 A：GitHub Actions 自动构建（推荐，不用装任何环境）

1. 在 GitHub 新建一个空仓库；
2. 把本文件夹（`s70-android/`）里的所有文件传上去——网页拖拽也行，注意 `.github/` 是隐藏目录，要一起传；
3. 打开仓库的 **Actions** 页，等 "Build APK" 跑完（约 3–5 分钟）；
4. 在该次运行的 **Artifacts** 里下载 `s70-pixmap-debug-apk`，解压得到 `app-debug.apk`；
5. 传到手机安装（需要允许"安装未知来源应用"）。

> 这个 APK 用调试证书签名，自己用没问题；要上架应用商店才需要正式签名。
> 国内访问 GitHub 可能需要代理，这是唯一的外部依赖。

### 路线 B：Android Studio 本地构建

1. 安装 [Android Studio](https://developer.android.com/studio)（自带 JDK 和 SDK，约 1 GB）；
2. `File → Open` 选择 `s70-android` 文件夹；
3. 首次打开会自动同步并下载 Gradle / AGP（需要能访问 `dl.google.com`）；
4. 菜单 `Build → Build Bundle(s) / APK(s) → Build APK(s)`；
5. APK 在 `app/build/outputs/apk/debug/app-debug.apk`，用数据线传到手机安装。

命令行等价操作（在 Android Studio 的 Terminal 里）：

```bat
gradlew assembleDebug
```

> 工程里只放了 `gradle-wrapper.properties`，没放 `gradle-wrapper.jar`（二进制）。
> Android Studio 打开时如果提示 wrapper 缺失，按提示让它自动生成一次即可；
> 或者在 Terminal 里执行一次 `gradle wrapper --gradle-version 8.7`。
> GitHub Actions 那条路线不需要 wrapper，直接就能构建。

## 手机上怎么用

1. 用 OTG 线把读卡器插到手机，系统会挂载成"USB 存储"；
2. 打开 App，它会检测到可移动存储并弹窗询问是否授权 → 点"授权"，在系统文件选择器里选中**卡的根目录**（或 `DCIM`）；
   - 只需授权一次，之后 App 会记住（重启手机也有效）；
   - 换卡或换读卡器路径变了，点"① 选择存储卡文件夹"重新授权即可；
3. 先把暗帧放进卡里的 `darks` 文件夹（或者命名为 `dark_*.jpg`）——拍法见桌面版 README；
4. 点"② 用暗帧生成映射"，几秒到几十秒（视暗帧数量）；
5. 点"③ 修复卡里所有照片"，结果写在卡里的 `S70Fixed/` 文件夹，原图不动。

## 实现要点

| 需求 | 做法 |
|---|---|
| 自动识别读卡器 | `StorageManager.getStorageVolumes()` 枚举已挂载的可移动卷，插卡后回到 App 自动弹窗 |
| 访问存储卡 | SAF（`ACTION_OPEN_DOCUMENT_TREE`）+ `takePersistableUriPermission`，**不需要任何存储权限**，也不用 `MANAGE_EXTERNAL_STORAGE` |
| 遍历文件 | `DocumentsContract` 递归查询子文档，最多 5000 个目录，避免坏目录死循环 |
| 检测坏点 | 逐通道比较像素与 8 邻居均值（行积分实现，O(W·H)），多帧按命中比例判定 |
| 修复 | 用 8 邻居中位数替换，掩膜默认外扩 1 像素抵消 JPEG 光晕 |
| 依赖 | **零第三方依赖**：不用 AndroidX、不用 Kotlin，只用框架 API，构建快、体积小 |

## 已知限制

- 只处理 JPEG；S70 的 RAW（.CRW）要先转 TIFF（手机端暂不支持）；
- 修复后不保留 EXIF（拍摄时间、方向等信息会丢），需要的话用电脑版处理；
- 带旋转信息的竖拍照片，解码后宽高会互换，会因"尺寸与映射不符"被跳过；
- 7MP 图片处理峰值内存约 80 MB，老手机可能吃紧，App 会捕获 OOM 并提示；
- 输出 JPEG 质量固定 95，会二次压缩，介意的话用电脑版出 TIFF。

## 工程结构

```
s70-android/
├─ settings.gradle / build.gradle / gradle.properties
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/s70pixmap/
│     │  ├─ MainActivity.java    界面、授权、流程编排
│     │  ├─ CardScanner.java     SAF 遍历、读写、目录创建
│     │  └─ PixelMapEngine.java  检测与修复算法（纯 Java，可单测）
│     └─ res/values/            字符串与主题
├─ .github/workflows/build-apk.yml
└─ tools/validate.py            无编译器时的工程自检
```

## 自检

```bat
python tools/validate.py
```

会检查 XML 合法性、Java 括号平衡，并用 Python 复刻一遍检测/修复算法跑合成数据。当前结果：XML 3 个文件全部合法、3 个 Java 文件括号平衡、5 个已知坏点全部命中、修复后误差从 143 降到 21.8（随机噪声底色下的理论下限约 18）。

**注意**：这个脚本不能替代真正的 Gradle 编译。如果 Actions 构建报错，把日志发我，我按报错改。
