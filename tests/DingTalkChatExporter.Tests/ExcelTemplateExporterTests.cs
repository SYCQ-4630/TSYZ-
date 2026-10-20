using ClosedXML.Excel;
using DingTalkChatExporter.Core.Models;
using DingTalkChatExporter.Export;

namespace DingTalkChatExporter.Tests;

public sealed class ExcelTemplateExporterTests : IDisposable
{
    private readonly string _directory = Path.Combine(
        Path.GetTempPath(),
        "dtce-export-" + Guid.NewGuid().ToString("N"));

    private readonly ExcelTemplateExporter _exporter = new();

    public ExcelTemplateExporterTests()
    {
        Directory.CreateDirectory(_directory);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    [Fact]
    public void Preflight_AppendMode_StartsAfterLastUsedRow()
    {
        var template = CreateTemplate();
        var options = CreateOptions(template, ExportWriteMode.Append);

        var preflight = _exporter.Preflight(options, CreateRows());

        Assert.Equal("数据", preflight.SheetName);
        Assert.Equal(3, preflight.StartRow);
        Assert.Equal(0, preflight.ExistingRowsToOverwrite);
        Assert.Equal(2, preflight.RowsToWrite);
        Assert.Empty(preflight.UnmappedFields);
    }

    [Fact]
    public void Export_AppendMode_WritesRowsAndLeavesTemplateUntouched()
    {
        var template = CreateTemplate();
        var output = Path.Combine(_directory, "out.xlsx");
        var options = CreateOptions(template, ExportWriteMode.Append) with { OutputPath = output };

        var result = _exporter.Export(options, CreateRows());

        Assert.Equal(3, result.StartRow);
        Assert.Equal(2, result.WrittenRows);
        Assert.Equal(1, result.LowConfidenceCells);

        using (var workbook = new XLWorkbook(output))
        {
            var sheet = workbook.Worksheet("数据");
            Assert.Equal("订单号", sheet.Cell("A1").GetString());
            Assert.Equal("D-100", sheet.Cell("A3").GetString());
            Assert.Equal("100", sheet.Cell("B3").GetString());
            Assert.Equal("D-101", sheet.Cell("A4").GetString());
            Assert.Equal("88", sheet.Cell("B4").GetString());
            Assert.Equal(XLColor.FromHtml("#FFF4CE"), sheet.Cell("B4").Style.Fill.BackgroundColor);

            var metadata = workbook.Worksheet("_ExportMeta");
            Assert.Equal(XLWorksheetVisibility.Hidden, metadata.Visibility);
            Assert.Equal("D-100", sheet.Cell("A3").GetString());
        }

        using (var templateWorkbook = new XLWorkbook(template))
        {
            var sheet = templateWorkbook.Worksheet("数据");
            Assert.Equal("旧数据", sheet.Cell("A2").GetString());
            Assert.True(string.IsNullOrEmpty(sheet.Cell("A3").GetString()));
        }
    }

    [Fact]
    public void Export_OverwriteMode_StartsBelowHeader()
    {
        var template = CreateTemplate();
        var output = Path.Combine(_directory, "overwrite.xlsx");
        var options = CreateOptions(template, ExportWriteMode.Overwrite) with { OutputPath = output };

        var result = _exporter.Export(options, CreateRows());

        Assert.Equal(2, result.StartRow);
        using var workbook = new XLWorkbook(output);
        Assert.Equal("D-100", workbook.Worksheet("数据").Cell("A2").GetString());
    }

    [Fact]
    public void Export_SelectsWorksheetWithMatchingHeaders()
    {
        var template = Path.Combine(_directory, "multi.xlsx");
        using (var workbook = new XLWorkbook())
        {
            var cover = workbook.Worksheets.Add("说明");
            cover.Cell("A1").Value = "请勿修改模板结构";
            var data = workbook.Worksheets.Add("数据");
            data.Cell("A1").Value = "订单号";
            data.Cell("B1").Value = "金额";
            workbook.SaveAs(template);
        }

        var output = Path.Combine(_directory, "multi-out.xlsx");
        var options = CreateOptions(template, ExportWriteMode.Append) with { OutputPath = output };

        var result = _exporter.Export(options, CreateRows());

        Assert.Equal("数据", result.SheetName);
        using var outputWorkbook = new XLWorkbook(output);
        Assert.Equal("D-100", outputWorkbook.Worksheet("数据").Cell("A2").GetString());
        Assert.True(string.IsNullOrEmpty(outputWorkbook.Worksheet("说明").Cell("A2").GetString()));
    }

    [Fact]
    public void Export_NoMatchingHeaders_ThrowsClearError()
    {
        var template = Path.Combine(_directory, "bad.xlsx");
        using (var workbook = new XLWorkbook())
        {
            workbook.Worksheets.Add("数据").Cell("A1").Value = "无关列";
            workbook.SaveAs(template);
        }

        var options = CreateOptions(template, ExportWriteMode.Append);

        var exception = Assert.Throws<InvalidOperationException>(
            () => _exporter.Export(options, CreateRows()));
        Assert.Contains("没有匹配到任何规则字段", exception.Message);
    }

    [Fact]
    public void Export_OutputEqualsTemplate_IsRejected()
    {
        var template = CreateTemplate();
        var options = CreateOptions(template, ExportWriteMode.Append) with { OutputPath = template };

        var exception = Assert.Throws<InvalidOperationException>(
            () => _exporter.Export(options, CreateRows()));
        Assert.Contains("不能与模板文件相同", exception.Message);
    }

    [Fact]
    public void Export_EmptyRows_IsRejected()
    {
        var template = CreateTemplate();
        var options = CreateOptions(template, ExportWriteMode.Append);

        Assert.Throws<InvalidOperationException>(() => _exporter.Export(options, Array.Empty<ExportRow>()));
    }

    private string CreateTemplate()
    {
        var path = Path.Combine(_directory, "template-" + Guid.NewGuid().ToString("N") + ".xlsx");
        using var workbook = new XLWorkbook();
        var sheet = workbook.Worksheets.Add("数据");
        sheet.Cell("A1").Value = "订单号";
        sheet.Cell("B1").Value = "金额";
        sheet.Cell("A2").Value = "旧数据";
        sheet.Cell("B2").Value = "1";
        workbook.SaveAs(path);
        return path;
    }

    private ExportOptions CreateOptions(string template, ExportWriteMode mode) => new(
        TemplatePath: template,
        OutputPath: Path.Combine(_directory, "result-" + Guid.NewGuid().ToString("N") + ".xlsx"),
        SheetName: null,
        Mode: mode,
        FieldNames: new[] { "订单号", "金额" },
        HighlightLowConfidence: true,
        BackupExisting: false,
        SourceName: "测试群",
        FetchedAt: DateTimeOffset.UtcNow,
        RawCount: 3,
        DedupedCount: 2,
        RunId: Guid.NewGuid().ToString("N"));

    private static IReadOnlyList<ExportRow> CreateRows() => new[]
    {
        new ExportRow(
            new RawMessage("m1", "张三", DateTimeOffset.UtcNow, "订单 D-100 金额 100", "text"),
            new Dictionary<string, ExtractionResult>(StringComparer.Ordinal)
            {
                ["订单号"] = new("D-100", Confidence.High),
                ["金额"] = new("100", Confidence.High)
            }),
        new ExportRow(
            new RawMessage("m2", "李四", DateTimeOffset.UtcNow, "订单 D-101 大概 88", "text"),
            new Dictionary<string, ExtractionResult>(StringComparer.Ordinal)
            {
                ["订单号"] = new("D-101", Confidence.High),
                ["金额"] = new("88", Confidence.Low)
            })
    };
}
