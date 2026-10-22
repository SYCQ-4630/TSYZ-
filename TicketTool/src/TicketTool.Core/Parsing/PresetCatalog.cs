namespace TicketTool.Core.Parsing;

/// <summary>预置领域模式定义。</summary>
public sealed record PresetDefinition(
    string Key,
    string DisplayName,
    IReadOnlyList<string> Aliases,
    string ValuePattern,
    string Description);

/// <summary>通信工单领域的内置预置规则库。</summary>
public static class PresetCatalog
{
    private static readonly IReadOnlyList<PresetDefinition> All = new List<PresetDefinition>
    {
        new("ticket_no", "工单号", new[] { "工单号", "工单编号", "工单号码", "流水号", "单号", "投诉单号" },
            "[A-Za-z0-9][A-Za-z0-9_-]{5,31}", "字母数字组成的工单编号"),
        new("phone", "联系方式", new[] { "联系方式", "联系电话", "电话", "手机", "手机号", "客户电话", "联系号码", "备用电话" },
            @"(?:\+?86[-\s]?)?(?:1[3-9]\d[-\s]?\d{4}[-\s]?\d{4}|0\d{2,3}[-\s]?\d{7,8})", "手机或固定电话"),
        new("address", "投诉地址", new[] { "投诉地址", "地址", "安装地址", "故障地址", "用户地址", "详细地址", "地点", "位置" },
            ".*", "投诉或故障地址原文"),
        new("star", "星级", new[] { "星级", "服务星级", "评价星级" }, @"[1-5]\s*星?", "1 至 5 星"),
        new("rework", "是否重投", new[] { "是否重投", "重投", "是否重复投诉", "重复投诉" }, @"(?:是|否|1|0|Y|N|yes|no)", "是/否或 1/0"),
        new("date", "日期", new[] { "日期", "投诉日期", "受理日期", "发生日期", "派单日期" },
            @"\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?", "日期文本"),
        new("time", "时间", new[] { "时间", "发生时间", "受理时间", "派单时间" },
            @"\d{1,2}[:：]\d{2}(?::\d{2})?", "时间文本"),
        new("ip", "IP 地址", new[] { "IP", "IP地址", "IP地址信息" },
            @"(?:\d{1,3}\.){3}\d{1,3}", "IPv4 地址"),
        new("imei", "IMEI", new[] { "IMEI", "串号", "设备串号" }, @"\d{14,17}", "14 至 17 位设备串号"),
        new("alarm_code", "告警码", new[] { "告警码", "告警编号", "告警代码" }, @"[A-Za-z0-9_-]{2,32}", "告警编号"),
        new("account", "宽带账号", new[] { "宽带账号", "账号", "上网账号", "用户账号" }, @"[A-Za-z0-9@._-]{3,64}", "宽带或用户账号"),
        new("device", "设备型号", new[] { "设备型号", "光猫型号", "终端型号", "型号" }, @".{1,64}", "设备型号文本")
    };

    /// <summary>全部预置模式。</summary>
    public static IReadOnlyList<PresetDefinition> Definitions => All;

    /// <summary>按 Key 查询。</summary>
    public static PresetDefinition? Find(string? key)
        => All.FirstOrDefault(p => string.Equals(p.Key, key, StringComparison.OrdinalIgnoreCase));

    /// <summary>用字段名猜测最合适的预置模式。</summary>
    public static PresetDefinition? Guess(string fieldName)
    {
        var normalized = fieldName.Trim();
        foreach (var preset in All)
        {
            if (preset.Aliases.Any(alias => string.Equals(alias, normalized, StringComparison.Ordinal))
                || normalized.Contains(preset.DisplayName, StringComparison.Ordinal))
            {
                return preset;
            }
        }
        return null;
    }
}
