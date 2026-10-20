# 钉钉聊天记录导出工具

Windows 桌面应用。通过官方 `dws` CLI 只读拉取钉钉群聊消息，去重后按规则库提取字段，再把结果写入 Excel 模板。应用不读取或解密钉钉数据库，不向钉钉写入任何内容。

## 功能

- 会话与快照：检查数据源、搜索群聊、抓取消息、去重、规则预览、按群保存快照与增量水位。
- 规则库：字段规则增删改、版本历史与恢复、本地样例测试。
- 表格管理：导入 Excel 模板、绑定规则库、建立 SQLite FTS5 索引、表内搜索、移入回收站与恢复。
- 导出：预检、追加或覆盖写入、低置信度高亮、导出统计。
- 历史：导入记录、回收站、导出统计。
- 设置：数据源、实验性功能开关、主题与字号、日志级别、打开数据目录。
- 首次使用向导、F5 刷新、`Ctrl+1~6` 切换页面。

## 环境要求

- Windows 10 1809 及以上或 Windows 11，x64。
- 已安装并完成登录授权的 `dws` CLI；默认从 `PATH` 查找，也可在设置中指定可执行文件路径。
- 发布包为自包含版本，目标机无需预装 .NET 运行时。
- 从源码构建需要 .NET 8 SDK。

## 构建与测试

```bash
# 还原、编译
dotnet build DingTalkChatExporter.sln

# 运行单元测试
dotnet test DingTalkChatExporter.sln
```

Linux 上也可以编译（项目已设置 `EnableWindowsTargeting`），但 WPF 应用只能在 Windows 运行。

## 发布 Windows x64

```bash
# 使用仓库内的发布配置，生成自包含文件
dotnet publish src/DingTalkChatExporter.App/DingTalkChatExporter.App.csproj -p:PublishProfile=win-x64
```

输出目录：`src/DingTalkChatExporter.App/bin/Release/net8.0-windows/publish/win-x64/`。

把整个目录复制到目标 Windows 机器，运行 `DingTalkChatExporter.exe`。

## 发布 Windows x64 单文件

```bash
# 生成单个自包含 exe，目标机无需安装 .NET
dotnet publish src/DingTalkChatExporter.App/DingTalkChatExporter.App.csproj -p:PublishProfile=win-x64-single
```

输出文件：`src/DingTalkChatExporter.App/bin/Release/net8.0-windows/publish/win-x64-single/DingTalkChatExporter.exe`。

也可以直接使用仓库内已打包的 `dist/DingTalkChatExporter-win-x64.exe`。单文件版本首次启动会先把原生依赖解压到临时目录，启动速度会略慢于文件夹版本。

## 使用流程

1. 首次启动按向导检查数据源。
2. 在“会话与快照”选择群聊，设置日期范围与条数，抓取快照。勾选“增量读取”时，从该群上次水位继续拉取并合并已有预览。
3. 在“规则库”维护字段规则，可保存多个版本。
4. 在“表格管理”导入 Excel 模板，绑定规则库并建立索引。
5. 在“导出”选择模板，先预检，再写入。默认先备份已有文件。
6. 在“历史”查看导入记录、恢复回收站条目或查看导出统计。

## 数据与隐私

数据默认存放在：

```
%APPDATA%\DingTalkChatExporter\
```

可用 `--data-dir <path>` 或 `--data-dir=<path>` 指定其他目录。主要文件：

| 文件 | 内容 |
| --- | --- |
| `snapshot.json` | 工作台状态与按群预览 |
| `state.json` | 按群增量水位 |
| `tables.json` | 已导入表格 |
| `import-history.json` | 导入历史 |
| `removed-tables.json` | 回收站 |
| `features.json` | 功能开关 |
| `ui-settings.json` | 主题、字号、最近群聊 |
| `data-sources.json` | dws 与 FlaUI 设置 |
| `export-stats.json` | 导出统计 |
| `rule-libraries/*.json` | 规则库与版本历史 |
| `indexes/*.db` | 每张表的 SQLite 索引 |
| `logs/*.log` | 运行日志 |

所有文件按类别独立保存，写入使用同目录临时文件替换。从列表移除表格只进入回收站，Excel 文件与索引仍保留在磁盘上；物理删除是单独入口并需要二次确认。

## 只读边界

- 只调用 `dws` 的只读查询命令。
- 不读取、不导出钉钉本地数据库。
- 不启用会话内容存档。
- 不向钉钉发送、修改或删除任何消息。
- FlaUI 降级默认关闭，仅在用户明确开启后用于读取可见窗口文本。

## 配置说明

- 功能开关中 `AllowFlaUiFallback`、`HideAppDuringFlaUiCapture`、自动导出和后台监控等高风险项默认关闭。
- `EnableIncrementalRead`、导出前备份、低置信度高亮等低风险项默认开启。
- 模板字段规则默认读取首行表头，也支持 `ExportStart` 命名区域。
- 追加导出会从模板最后一个已用行之后继续写入。

## 故障排查

- 提示找不到 `dws`：确认命令在 `PATH` 中，或在“设置 → 数据源”填写完整路径。
- `dws` 返回未授权：先在终端完成 `dws` 登录授权，再回到应用检查数据源。
- 索引损坏：在“表格管理”对该表执行重建索引。
- 界面出现未处理异常：查看 `%APPDATA%\DingTalkChatExporter\logs\` 下最新日志。
- 数据异常需重置：先备份整个数据目录，再删除对应 JSON 文件；应用会使用干净状态启动并隔离损坏文件。

## 验证状态

- 当前环境已通过 `dotnet build`（0 错误）和 53 项单元测试。
- 已使用 `win-x64` 自包含发布配置成功产出发布目录。
- 由于开发环境没有 Windows、钉钉客户端和 `dws` 授权，真实抓取、FlaUI 降级、WPF 界面运行与 Windows 实机导出仍需在目标机器上联调验证。
