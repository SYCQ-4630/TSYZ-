using System.Diagnostics;
using Serilog;
using TicketTool.Core.Models;

namespace TicketTool.Core.Parsing;

/// <summary>工单文本解析入口。一次锚点扫描服务全部字段。</summary>
public sealed class TicketParser
{
    private readonly ILogger _logger;

    /// <summary>创建解析器。</summary>
    public TicketParser(ILogger? logger = null)
    {
        _logger = logger ?? Log.ForContext<TicketParser>();
    }

    /// <summary>解析文本；结果包含字段值与原文偏移。</summary>
    public TicketParseResult Parse(
        string rawText,
        Template? template,
        IProgress<ParseProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        var stopwatch = Stopwatch.StartNew();
        var raw = rawText ?? string.Empty;
        progress?.Report(new ParseProgress { Percent = 5, Message = "正在整理原文" });

        if (template is null)
        {
            return new TicketParseResult { RawText = raw, Elapsed = stopwatch.Elapsed };
        }

        cancellationToken.ThrowIfCancellationRequested();
        progress?.Report(new ParseProgress { Percent = 20, Message = "正在扫描字段位置" });
        var scan = AnchorScanner.Scan(raw, template.Fields);

        var result = new TicketParseResult { RawText = raw };
        var fields = template.Fields.OrderBy(f => f.SortOrder).ThenBy(f => f.Id).ToList();
        for (var i = 0; i < fields.Count; i++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var field = fields[i];
            var parsed = FieldExtractionEngine.Extract(raw, field, scan);
            if (parsed is null)
            {
                result.MissingFields.Add(field.FieldName);
            }
            else
            {
                result.Fields.Add(parsed);
            }

            progress?.Report(new ParseProgress
            {
                Percent = 20 + (int)(70.0 * (i + 1) / Math.Max(1, fields.Count)),
                Message = $"正在识别 {field.FieldName}"
            });
        }

        stopwatch.Stop();
        result.Elapsed = stopwatch.Elapsed;
        progress?.Report(new ParseProgress { Percent = 100, Message = "识别完成" });

        _logger.Information(
            "ParseCompleted {TemplateId} {TextLength} {Recognized} {Missing} {ElapsedMs}",
            template.Id,
            raw.Length,
            result.Fields.Count,
            string.Join(',', result.MissingFields),
            stopwatch.Elapsed.TotalMilliseconds);

        return result;
    }
}
