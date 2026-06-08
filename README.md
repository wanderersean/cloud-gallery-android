# Cloud Gallery Android

<img alt="Cloud Gallery logo" src="graphics/icon.webp" width="120" />

Cloud Gallery Android 是 Cloud Gallery 的 Android 客户端。它基于 Fossify Gallery 的本地图库能力做二次开发，重点新增了面向个人阿里云 OSS 的云端照片存储、云端元数据管理，以及与 Cloud Gallery Web 端协同登录和浏览的能力。

> 本仓库是 Android 客户端的公开镜像。Cloud Gallery 的 Server/Web 与生产配置不在本仓库中，主开发仓库中的 Android 相关代码会自动同步到这里。

## 核心定位

Cloud Gallery 不是把照片集中托管到项目方服务器，而是让用户把原始照片保存到自己的阿里云 OSS Bucket 中：

- App 负责本地浏览、选择照片、上传和云端状态展示。
- Server 负责账号认证、文件状态、元数据、二维码会话等中间能力。
- Web 端负责注册配置、云端相册浏览、搜索、时间线和二维码登录。
- 原始照片文件最终存放在用户自己的 OSS 账户里。

## 云端存储能力

### 上传到个人 OSS

- 在图库缩略图页或大图页直接上传照片到云端。
- 上传目标为用户配置的阿里云 OSS Bucket。
- 上传前计算文件 MD5，服务端可检查文件是否已存在。
- 已存在文件支持跳过重复传输，减少流量和等待时间。
- 上传完成后写入云端文件记录和照片元数据。

### 上传状态与任务管理

- 缩略图上展示云端状态图标，区分已上传、未上传和上传中。
- 上传中展示进度，支持任务状态刷新。
- 上传任务列表可查看等待中、上传中、成功、失败、取消等状态。
- 支持清理已完成任务。
- 支持“仅显示未上传至云端的照片”，方便补齐备份。

### 云端标题与元数据

- 支持给云端照片设置标题。
- 支持在已上传照片上编辑云端标题。
- 上传中的照片可以先设置标题，上传完成后再生效。
- 标题会写入 Cloud Gallery 服务端元数据，也会同步到 OSS Object Tag，降低单一数据源风险。
- 支持按云端标题搜索照片。
- 云端记录包含文件 MD5、大小、类型、OSS 路径、本地路径、创建时间、上传者等信息。

### 云端收藏与云端操作

- 支持云端收藏/取消收藏。
- 已收藏的云端照片在 App 中以不同云端状态呈现。
- 支持删除云端照片记录，不会直接删除本地文件。
- 支持查看和维护云端相关属性。
- 长按云端图标可打开云端操作菜单。

### Web 端协同

- App 可以扫描 Web 登录页二维码，授权 Web 端登录。
- Web 端登录后可浏览已上传照片、查看缩略图、进入大图、搜索标题、按时间线查看和筛选。
- App 和 Web 使用同一套云端账号配置与元数据服务。

## 账号配置方式

云端功能需要先完成 Cloud Gallery 配置。当前推荐流程是通过 Web 端完成初始化：

1. 打开 Cloud Gallery Web。
2. 使用阿里云 OAuth 授权身份。
3. 通过 ROS 在用户阿里云账号中创建 Cloud Gallery 管理角色。
4. 选择已有 OSS Bucket，或创建新的 Bucket。
5. 生成 App 使用的成员配置串和二维码。
6. 在 Android App 中粘贴配置串或扫码导入配置。
7. 登录后即可上传照片并使用云端能力。

配置完成后，Android App 会保存以下云端连接信息：

- AccessKey ID / AccessKey Secret
- OSS Bucket
- OSS Endpoint
- OSS Region

这些配置用于 App 与 Cloud Gallery 服务、用户 OSS Bucket 交互。未登录云端账号时，App 仍可作为本地图库使用。

## 与 Fossify Gallery 的关系

Cloud Gallery 复用了 Fossify Gallery 的本地图库基础能力，包括本地照片/视频浏览、编辑、排序、隐藏/排除目录、收藏和主题定制等。这个仓库的重点改动不是重做本地图库，而是在成熟本地图库基础上加入个人云端存储和多端访问能力。

## 项目边界

这个公开仓库主要包含：

- Android App 源码
- Android 构建配置
- Fastlane 元数据
- 图标和基础项目文件

这个公开仓库不包含：

- Cloud Gallery Server 源码
- Cloud Gallery Web 源码
- 生产环境配置
- 数据库结构和部署脚本
- 私有密钥、服务端环境变量或阿里云 OAuth 密钥

## 构建

### 环境要求

- Android SDK
- JDK 17

### 构建 Debug APK

```bash
./gradlew assembleFossDebug
```

### 代码检查

```bash
./gradlew detekt
./gradlew lintDebug
```

## 公开镜像说明

本仓库是只读公开镜像，主要用于公开 Android 客户端代码。日常开发发生在私有 Cloud Gallery 主仓库中，合并后的 Android 代码会通过 GitHub Actions 自动同步到这里。

如果你想了解 Cloud Gallery 的整体形态，可以从 Android App 的云端上传、云端标题、云端收藏和 Web 扫码登录能力入手阅读代码：

- `app/src/main/kotlin/org/cloud/gallery/cloud/`
- `app/src/main/kotlin/org/cloud/gallery/helpers/CloudActionsHelper.kt`
- `app/src/main/kotlin/org/cloud/gallery/helpers/CloudUploadFilter.kt`
- `app/src/main/kotlin/org/cloud/gallery/dialogs/CloudAccountDialog.kt`
- `app/src/main/kotlin/org/cloud/gallery/dialogs/QrWebLoginDialog.kt`

## 许可证

Cloud Gallery 按照 [LICENSE](LICENSE) 文件中的条款发布。

## 截图

<div align="center">
<img alt="Cloud Gallery screenshot" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="Cloud Gallery screenshot" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="Cloud Gallery screenshot" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>
