# 基站地图 CellMap

自用 Android 基站查询工具。导入 XLSX 台账后，按联通/电信、共享/独享四色分类标注，生成压缩优化数据集，并在 MapLibre 地图上按区域、制式、频段与分类查询。

## 环境要求

- Android Studio Ladybug 或更新版本
- JDK 17
- Android SDK 35，最低运行 Android 7.0（API 24）

## 构建

```bash
cd cellmap
./gradlew assembleDebug
```

首次构建前在 `cellmap/local.properties` 写入本机 SDK 路径：

```properties
sdk.dir=/path/to/Android/Sdk
```

也可用 `ANDROID_HOME` 环境变量代替。产物位于 `app/build/outputs/apk/debug/`。

## 首次配置

1. 申请天地图 Key：https://console.tianditu.gov.cn/ ，创建「浏览器端」Key。
2. 打开应用，进入「Key 管理」，录入 Key 后执行校验。Key 使用 Android Keystore + AES-GCM 加密保存，只存在本机。
3. 授予通知权限，允许导入、标注、优化任务在后台继续运行。
4. 在系统设置中把应用加入电池优化白名单，避免长任务被中断。

没有可用 Key 时地图显示纯色底图，基站图层与查询仍可正常使用；进入地图时会探测当前 Key，鉴权失败时自动标记失效并切换到下一个可用 Key。

## 使用流程

1. **导入数据**：选择 `.xlsx` 文件，确认表头行与字段映射后开始导入。导入在 WorkManager 前台任务中执行，可暂停、取消与重试。
2. **分类标注**：对未分类记录逐条或批量指定四色分类，完成后确认标注结果。
3. **压缩优化**：生成按缩放级别聚类的 GeoJSONL 数据集与索引，原始数据保持不变，可回退。
4. **地图查询**：按搜索或管理区县定位，叠加筛选条件，查看聚合点与基站详情，支持搜索基站 ID、小区 ID 与名称。

首页会列出未完成的准备项；缺少 Key、数据或标注时，可直接跳转到对应页面。

## 导入格式

仅支持 `.xlsx`（不支持旧版 `.xls`），单文件上限 200MB。读取第一个工作表。

必填列：

| 字段 | 说明 |
| --- | --- |
| 纬度 | -90 到 90 |
| 经度 | -180 到 180 |
| 承建方 | 联通 / 电信，用于四色分类 |
| 基站 ID | 基站标识 |
| 小区 ID | 小区标识 |
| 是否共享 | 已共享 / 未共享，用于圆点与方块区分 |
| 制式 | 4G / 5G |

可选列包括基站名称、小区名称、运营商、所属区县、频段、覆盖类别、天线总挂高、45G 共设备等。表头支持常见别名与模糊匹配，导入前可手动调整映射。

同一「基站 ID + 小区 ID」重复导入会被忽略。

## 技术要点

- Kotlin + Jetpack Compose + Material 3，MVVM + Repository，Coroutines/Flow
- Room 业务表、R-Tree 空间索引与 FTS4 搜索表同事务写入
- XLSX 使用平台 SAX 直接解析 OOXML，流式按批写入，不构建工作簿对象
- 地图使用 MapLibre，符号图层绘制四色标记，聚合图层分级展示
- 后台任务使用前台 Worker 与通知，支持进度、取消与失败重试
- 天地图 Key 经 Keystore 加密；瓦片请求直接从设备发出

## 目录

```text
app/src/main/java/com/selfuse/cellmap/
├── core/        领域模型与工具
├── data/        数据库、导入、仓库、偏好、Key、风险规则
├── di/          依赖装配
├── map/         底图样式、GeoJSON、标记图标
├── ui/          首页、Key、导入、标注、地图、管理
└── work/        导入、标注、优化 Worker
```

## 说明

- 已在 JDK 17 + Android SDK 35 下通过 `./gradlew assembleDebug`，调试包位于 `dist/cellmap-debug.apk`。
- MapLibre 13.7.1 的 arm64-v8a / x86_64 原生库按 16KB 页对齐，可在 Android 15 及以上 16KB 页设备运行。
- 地图依赖使用 MapLibre `android-sdk-opengl`（OpenGL ES），规避部分设备 Vulkan 驱动初始化失败；地图初始化延迟到地图页，失败时显示错误页而不是闪退。
- 空间索引使用普通表 + B-Tree，不依赖 SQLite `rtree`/`fts4` 可选模块；部分 Android 17 系统 SQLite 未编译这些模块，虚拟表会导致建库失败并闪退。
- 启动步骤、未捕获异常和系统退出原因会写入 `Download/cellmap-diagnostics.txt`，便于反馈问题。
- 项目仅自用，不上架应用商店。
- Key 与天地图瓦片不随应用分发。
- 修改数据后需重新执行标注与压缩优化，地图才会使用新数据集。
