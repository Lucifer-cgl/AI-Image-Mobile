# AI Image Mobile

完全离线的安卓本地生图应用。项目目标不是把桌面模型原样塞进手机，而是组合已经开放的端侧模型与运行时：

1. Qwen2.5 1.5B Q4 在 CPU 上把中文整理为详细英文生图提示词。
2. 规划器释放内存后，LCM/少步图片模型在 Vulkan GPU 上绘制完整图片。
3. TAESD 完成快速解码并输出图片。

模型权重不进入 Git 历史和 APK。应用从项目 Release 附件下载，依次尝试国内镜像一、国内镜像二和 GitHub 官方源。下载支持断点续传；大于 GitHub 单附件限制的模型自动分片下载、逐片校验、合并并再次校验完整 SHA-256。

## 下载

- [安卓安装包 v0.1.1-alpha](https://github.com/Lucifer-cgl/AI-Image-Mobile/releases/download/v0.1.1-alpha/AI-Image-Mobile-v0.1.1-alpha.apk)
- [v0.1.0-alpha 发布页与模型附件](https://github.com/Lucifer-cgl/AI-Image-Mobile/releases/tag/v0.1.0-alpha)

安装 APK 后进入“模型管理”，依次安装三个必需模型包。无需手工拼接分片，也无需把模型放进 APK。

## 当前状态

- 已迁移在 Redmi 21091116C 上跑通的 `stable-diffusion.cpp + Vulkan` 安卓骨架。
- 已保留历史记录、进度、错误显示和从最近任务划走后释放模型的逻辑。
- 已将 Mali Vulkan 兼容修复保存为可复现补丁。
- 已完成独立应用标识、独立界面和模型包管理器，不包含旧应用网页或内置权重。
- 已接入 LCM sampler、LCM scheduler、CFG=1 与 TAESD 路径。
- 已在 Redmi 21091116C 上完成中文全流程真机验证：规划约 14.4 秒，256×256 四步生图约 74.5 秒。
- llama.cpp/ggml 使用 ARMv8.2 FP16、dot-product 与 `-O3` 编译；目标设备需支持 ARMv8.2 指令。

## 目录

```text
app/                 安卓应用
model-packs/         模型包清单与发布元数据（不含权重）
patches/             安卓/Mali兼容补丁
scripts/             依赖初始化、模型校验和发布脚本
docs/                架构、SceneSpec和真机测试说明
third_party/         固定版本的Git子模块
```

## 初始化

```powershell
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File scripts/apply-android-patches.ps1
```

在 `local.properties` 中配置 Android SDK，随后执行：

```powershell
.\gradlew.bat assembleDebug -PleanDeviceTest=true
```

`leanDeviceTest` 不把任何模型打进 APK，适合通过 ADB 反复覆盖安装和真机调试。

## 模型发布原则

- `base APK`：界面、调度器、推理运行时，不含大模型。
- `planner`：中文场景规划模型。
- `image-lcm`：LCM少步图片模型。
- `taesd`：快速解码器。
- 第一版不加载前景分割、动漫超分或 ControlNet，避免增加下载、内存和操作负担。
- 每个附件必须同时提供大小、SHA-256、许可证和上游来源。
- 国内镜像只改变下载地址，不改变文件内容；所有来源必须通过同一哈希校验。

详见 [架构说明](docs/architecture.md) 与 [模型包规范](docs/model-packs.md)。

