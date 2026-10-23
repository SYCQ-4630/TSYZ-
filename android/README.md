# 自动记账（AutoLedger）

本机运行的 Android 自动记账应用。通过无障碍服务读取微信、支付宝、云闪付与银行 App 的支付结果页面，结合截图 OCR、银行短信、可选通知监听和账单 CSV 导入，将流水写入本机加密数据库并生成月报。应用不申请网络权限，数据不离开设备。

## 功能概览

- 五层抓取策略：DFS 节点遍历、文本关键词匹配、事件源回溯、多窗口遍历、截图 + ML Kit 中文离线 OCR 兜底
- 错峰重试：0 / 800 / 2000 / 3500ms 四次尝试，同包名 5 秒冷却；横竖屏切换时调整遍历顺序与 OCR 节流
- 保底通道：银行短信解析；可选通知监听补充；账单 CSV 导入补齐历史
- 入账决策：规则版本化 + 置信度分流，高置信静默入账，中低置信进入待核对
- 幂等去重：金额 + 类型 + 商户 + 1 分钟时间桶，配合 ±120 秒相似查询与数据库唯一索引
- 月报：精确闹钟每月 1 日 9:00，WorkManager 10:00 兜底，开机/时间变更后重建；月报前自动交叉验证待核对记录
- 界面状态快照：DataStore 保存标签页、筛选、滚动位置与批量选择；Room 保存未提交草稿，进程重建后可恢复
- 加密存储：Room + SQLCipher 全库加密，密钥由 Android Keystore 保护；数据库升级前使用 `VACUUM INTO` 生成加密快照
- 脱敏日志：金额与商户掩码，不记录支付正文，按条数与保留期滚动清理，可导出 CSV
- 技术栈：Kotlin、Jetpack Compose Material3、Room、SQLCipher、DataStore、WorkManager、ML Kit 中文 OCR、MPAndroidChart

## 目录结构

```
android/
├── app/src/main/java/com/tsyz/autoledger/
│   ├── capture/          无障碍、OCR、短信、通知监听、账单导入、前台守护
│   ├── core/             模型、金额/时间/脱敏/指纹工具、规则文本解析
│   ├── data/             Room 实体与 DAO、SQLCipher、DataStore 设置、账本仓库
│   ├── decision/         入账流水线、待核对复核、规则回溯
│   ├── health/           通道心跳与超期检测
│   ├── notification/     通知渠道与月报通知
│   ├── permissions/      权限检查与厂商后台设置引导
│   ├── report/           月报聚合纯函数、编码、生成器
│   ├── rules/            规则模型、规则引擎、规则仓库
│   ├── schedule/         月报闹钟、WorkManager、开机重建、维护任务
│   ├── snapshot/         界面快照与草稿
│   └── ui/               ViewModel、Compose 主题、组件与页面
└── app/src/test/         JVM 单元测试
```

## 构建

环境要求：JDK 17、Android SDK Platform 35、Build Tools 35.0.0。

```bash
cd android
```

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

```bash
export ANDROID_HOME=/opt/android-sdk
```

```bash
./gradlew :app:assembleDebug
```

APK 输出：

```
android/app/build/outputs/apk/debug/app-debug.apk
```

单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

## 首次使用

1. 安装 APK 后打开应用，按引导开启无障碍服务（核心通道）。
2. 授权短信权限作为保底通道，按需开启通知使用权。
3. 授权通知权限、精确闹钟，并将应用加入电池优化白名单。
4. 按管理页的厂商指引开启自启动与后台运行，必要时在最近任务中加锁。
5. 完成引导后，正常使用支付 App；流水会出现在“报表”和“明细”中。

无障碍服务只读取配置中目标应用的界面文本。可在系统设置中随时关闭。

## 规则文件

内置规则位于 `app/src/main/assets/rules.json`，启动时写入加密数据库。管理页支持导入同格式 JSON，导入会整表替换并提升版本号。规则字段示例：

```json
{
  "version": 4,
  "rules": [
    {
      "id": "wechat_pay",
      "packages": ["com.tencent.mm"],
      "titleKeywords": ["支付成功", "转账"],
      "amountRegex": "(?:¥|￥)\\s?([0-9,]+(?:\\.[0-9]{1,2})?)",
      "merchantRegex": "收款方[:：]\\s*([^\\n]+)",
      "type": "EXPENSE",
      "category": "转账",
      "confidence": 0.9,
      "priority": 10
    }
  ]
}
```

规则导入后可对近期解析失败的原始捕获执行回溯重解析。

## 风险边界与已知限制

- 自动化无法保证 100% 捕获。支付页面改版、系统回收权限、厂商后台清理、锁屏与分屏等场景都可能漏抓。
- 无障碍、短信读取与截屏属于敏感能力，请确保使用场景符合当地法律与个人授权范围。
- 目标包名与页面文案会随 App 升级变化，需要持续维护规则；解析失败会写入脱敏日志并可回溯。
- 通知监听默认关闭，开启后才会作为补充通道工作。
- 月报依赖精确闹钟权限；未授权时使用系统空闲窗口，触发时间可能延后。
- 通道健康区分“正常”“待触发”“未启用”“心跳超期”：短信、通知与截图 OCR 属于事件驱动，长时间没有新事件只显示待触发，只有无障碍、前台守护、月报等主动心跳通道超期才算异常。
- 依赖库可能合并 `ACCESS_NETWORK_STATE`（仅读取网络状态），`INTERNET` 权限已在 Manifest 中显式移除，应用无法发起网络请求。
- 本应用面向个人侧载使用，未上架应用商店，不包含签名分发配置。
- 备份快照保存在应用私有目录，卸载应用会一并删除；项目不提供一键恢复入口，避免误覆盖。

## 测试覆盖

JVM 单元测试覆盖：

- 支付文本金额与商户解析、失败页排除
- 去重键与原始捕获指纹的稳定性
- 规则引擎的包名过滤、金额/商户/类型/分类与置信度
- 月报聚合、环比、异常检测
- 界面快照序列化、版本校验与损坏数据回退
- 通道健康状态判定（未启用、待触发、超期与事件驱动空闲）
