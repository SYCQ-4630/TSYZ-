using System.Globalization;
using System.IO;
using ClosedXML.Excel;
using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Export;

/// <summary>
/// Fills a user-provided .xlsx template. Named ranges are preferred for locating
/// the write origin; otherwise the first column-header row is matched against the
/// configured field names. The template file itself is never modified.
/// </summary>
public sealed class ExcelTemplateExporter
{
    private static readonly string[] StartRangeNames =
    {
        "ExportStart", "DataStart", "Export_Start", "Data_Start", "填充起点", "导出起点"
    };

    private static readonly string[] MetadataSheetNames =
    {
        "_ExportMeta", "导出元数据"
    };

    public IReadOnlyList<string> GetSheetNames(string templatePath)
    {
        using var workbook = OpenTemplate(templatePath);
        return workbook.Worksheets.Select(sheet => sheet.Name).ToList();
    }

    public ExportPreflight Preflight(ExportOptions options, IReadOnlyList<ExportRow> rows)
    {
        using var workbook = OpenTemplate(options.TemplatePath);
        var plan = BuildPlan(workbook, options, rows);
        return new ExportPreflight(
            plan.Worksheet.Name,
            plan.StartRow,
            plan.ExistingRows,
            rows.Count,
            plan.UnmappedFields,
            BuildDescription(plan, options, rows.Count));
    }

    public ExportResult Export(ExportOptions options, IReadOnlyList<ExportRow> rows)
    {
        if (rows.Count == 0)
        {
            throw new InvalidOperationException("没有可导出的数据。");
        }

        if (!File.Exists(options.TemplatePath))
        {
            throw new FileNotFoundException("Excel 模板不存在。", options.TemplatePath);
        }

        if (PathsEqual(options.TemplatePath, options.OutputPath))
        {
            throw new InvalidOperationException("导出目标不能与模板文件相同，请选择另一个输出路径。");
        }

        var started = DateTimeOffset.UtcNow;
        var outputDirectory = Path.GetDirectoryName(Path.GetFullPath(options.OutputPath))
                              ?? throw new InvalidOperationException("输出路径缺少目录。");
        Directory.CreateDirectory(outputDirectory);

        var tempPath = Path.Combine(
            outputDirectory,
            $".{Path.GetFileNameWithoutExtension(options.OutputPath)}_temp_{Guid.NewGuid():N}.xlsx");

        try
        {
            File.Copy(options.TemplatePath, tempPath, overwrite: true);
            TemplatePlan plan;
            int lowConfidenceCells;
            using (var workbook = new XLWorkbook(tempPath, new LoadOptions { RecalculateAllFormulas = false }))
            {
                plan = BuildPlan(workbook, options, rows);
                lowConfidenceCells = WriteRows(workbook, plan, options, rows);
                WriteMetadata(workbook, plan, options, rows, lowConfidenceCells);
                workbook.Save();
            }

            string? backupPath = null;
            if (options.BackupExisting && File.Exists(options.OutputPath))
            {
                backupPath = options.OutputPath + "." + DateTimeOffset.Now.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture) + ".bak.xlsx";
                File.Copy(options.OutputPath, backupPath, overwrite: true);
            }

            Replace(tempPath, options.OutputPath);

            return new ExportResult(
                options.OutputPath,
                plan.Worksheet.Name,
                plan.StartRow,
                rows.Count,
                lowConfidenceCells,
                backupPath,
                DateTimeOffset.UtcNow - started);
        }
        finally
        {
            try
            {
                if (File.Exists(tempPath))
                {
                    File.Delete(tempPath);
                }
            }
            catch (IOException)
            {
            }
        }
    }

    private static int WriteRows(
        XLWorkbook workbook,
        TemplatePlan plan,
        ExportOptions options,
        IReadOnlyList<ExportRow> rows)
    {
        var worksheet = plan.Worksheet;
        var lowConfidenceCells = 0;
        var lastUsedBefore = MaxUsedRow(worksheet);

        for (var index = 0; index < rows.Count; index++)
        {
            var targetRow = plan.StartRow + index;
            if (targetRow > lastUsedBefore)
            {
                var styleSourceRow = Math.Max(plan.HeaderRow + 1, targetRow - 1);
                if (styleSourceRow < targetRow && styleSourceRow <= lastUsedBefore)
                {
                    worksheet.Row(targetRow).Style = worksheet.Row(styleSourceRow).Style;
                }
            }

            foreach (var field in options.FieldNames)
            {
                if (!plan.FieldColumns.TryGetValue(field, out var column))
                {
                    continue;
                }

                if (!rows[index].Fields.TryGetValue(field, out var result) || result.Value is null)
                {
                    continue;
                }

                var cell = worksheet.Cell(targetRow, column);
                cell.SetValue(result.Value);

                if (options.HighlightLowConfidence && result.Confidence == Confidence.Low)
                {
                    cell.Style.Fill.PatternType = XLFillPatternValues.Solid;
                    cell.Style.Fill.BackgroundColor = XLColor.FromHtml("#FFF4CE");
                    cell.Style.Font.FontColor = XLColor.FromHtml("#6B4E00");
                    lowConfidenceCells++;
                }
            }
        }

        return lowConfidenceCells;
    }

    private static void WriteMetadata(
        XLWorkbook workbook,
        TemplatePlan plan,
        ExportOptions options,
        IReadOnlyList<ExportRow> rows,
        int lowConfidenceCells)
    {
        var existing = workbook.Worksheets.FirstOrDefault(sheet =>
            MetadataSheetNames.Any(name => string.Equals(name, sheet.Name, StringComparison.OrdinalIgnoreCase)));
        var sheet = existing ?? workbook.Worksheets.Add(MetadataSheetNames[0]);

        sheet.Clear();
        sheet.Cell(1, 1).Value = "Key";
        sheet.Cell(1, 2).Value = "Value";

        var values = new (string Key, string Value)[]
        {
            ("RunId", options.RunId),
            ("ExportedAt", DateTimeOffset.UtcNow.ToString("O", CultureInfo.InvariantCulture)),
            ("Source", options.SourceName),
            ("FetchedAt", options.FetchedAt?.ToString("O", CultureInfo.InvariantCulture) ?? string.Empty),
            ("RawCount", options.RawCount.ToString(CultureInfo.InvariantCulture)),
            ("DedupedCount", options.DedupedCount.ToString(CultureInfo.InvariantCulture)),
            ("ExportedCount", rows.Count.ToString(CultureInfo.InvariantCulture)),
            ("LowConfidenceCells", lowConfidenceCells.ToString(CultureInfo.InvariantCulture)),
            ("Template", options.TemplatePath),
            ("Sheet", plan.Worksheet.Name),
            ("StartRow", plan.StartRow.ToString(CultureInfo.InvariantCulture)),
            ("Mode", options.Mode.ToString())
        };

        for (var index = 0; index < values.Length; index++)
        {
            sheet.Cell(index + 2, 1).Value = values[index].Key;
            sheet.Cell(index + 2, 2).Value = values[index].Value;
        }

        try
        {
            sheet.Hide();
        }
        catch (InvalidOperationException)
        {
            // Excel requires at least one visible sheet; metadata stays visible if hiding fails.
        }
    }

    private static TemplatePlan BuildPlan(
        XLWorkbook workbook,
        ExportOptions options,
        IReadOnlyList<ExportRow> rows)
    {
        var fieldNames = options.FieldNames.Count > 0
            ? options.FieldNames.ToList()
            : rows.SelectMany(row => row.Fields.Keys).Distinct(StringComparer.Ordinal).ToList();

        var namedRange = FindStartRange(workbook, options.SheetName);
        IXLWorksheet worksheet;
        int headerRow;
        int startRow;
        int firstColumn;
        Dictionary<string, int> fieldColumns;
        List<string> unmapped;

        if (namedRange is not null)
        {
            worksheet = namedRange.Worksheet;
            var address = namedRange.Range.RangeAddress.FirstAddress;
            headerRow = Math.Max(1, address.RowNumber - 1);
            startRow = address.RowNumber;
            firstColumn = address.ColumnNumber;

            fieldColumns = new Dictionary<string, int>(StringComparer.Ordinal);
            for (var index = 0; index < fieldNames.Count; index++)
            {
                fieldColumns[fieldNames[index]] = firstColumn + index;
            }

            unmapped = new List<string>();
        }
        else
        {
            worksheet = SelectWorksheet(workbook, options.SheetName, fieldNames);
            headerRow = worksheet.FirstRowUsed()?.RowNumber() ?? 1;
            startRow = headerRow + 1;
            fieldColumns = MapColumnsByHeader(worksheet, headerRow, fieldNames);
            unmapped = fieldNames
                .Where(field => !fieldColumns.ContainsKey(field))
                .ToList();
        }

        if (fieldColumns.Count == 0)
        {
            throw new InvalidOperationException(
                "模板中没有匹配到任何规则字段。请检查模板第一行列标题，或配置一个名为 ExportStart 的命名区域。");
        }

        var lastUsed = MaxUsedRow(worksheet, fieldColumns.Values);
        var existingRows = Math.Max(0, lastUsed - startRow + 1);
        if (options.Mode == ExportWriteMode.Append && lastUsed >= headerRow + 1)
        {
            startRow = lastUsed + 1;
            existingRows = 0;
        }

        return new TemplatePlan(
            worksheet,
            headerRow,
            startRow,
            fieldColumns,
            unmapped,
            existingRows);
    }

    private static IXLWorksheet SelectWorksheet(
        XLWorkbook workbook,
        string? requestedSheet,
        IReadOnlyList<string> fieldNames)
    {
        if (!string.IsNullOrWhiteSpace(requestedSheet))
        {
            if (!workbook.Worksheets.TryGetWorksheet(requestedSheet.Trim(), out var requested) || requested is null)
            {
                throw new InvalidOperationException($"工作表“{requestedSheet}”不存在。");
            }

            return requested;
        }

        var best = workbook.Worksheets
            .Select(sheet =>
            {
                var headerRow = sheet.FirstRowUsed()?.RowNumber();
                if (headerRow is null)
                {
                    return (Sheet: sheet, Score: 0);
                }

                var headers = sheet.Row(headerRow.Value).CellsUsed()
                    .Select(cell => cell.GetString().Trim())
                    .ToHashSet(StringComparer.OrdinalIgnoreCase);
                return (Sheet: sheet, Score: fieldNames.Count(field => headers.Contains(field)));
            })
            .OrderByDescending(pair => pair.Score)
            .FirstOrDefault();

        return best.Sheet ?? workbook.Worksheets.First();
    }

    private static Dictionary<string, int> MapColumnsByHeader(
        IXLWorksheet worksheet,
        int headerRow,
        IReadOnlyList<string> fieldNames)
    {
        var map = new Dictionary<string, int>(StringComparer.Ordinal);
        foreach (var cell in worksheet.Row(headerRow).CellsUsed())
        {
            var header = cell.GetString().Trim();
            if (header.Length == 0)
            {
                continue;
            }

            var match = fieldNames.FirstOrDefault(field =>
                string.Equals(field, header, StringComparison.OrdinalIgnoreCase));
            if (match is not null && !map.ContainsKey(match))
            {
                map[match] = cell.Address.ColumnNumber;
            }
        }

        return map;
    }

    private static NamedRangeMatch? FindStartRange(XLWorkbook workbook, string? sheetName)
    {
        foreach (var namedRange in workbook.DefinedNames.ValidNamedRanges())
        {
            if (!StartRangeNames.Any(name =>
                    string.Equals(name, namedRange.Name, StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }

            var range = namedRange.Ranges.FirstOrDefault();
            if (range is null)
            {
                continue;
            }

            if (!string.IsNullOrWhiteSpace(sheetName) &&
                !string.Equals(range.Worksheet.Name, sheetName.Trim(), StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            return new NamedRangeMatch(range.Worksheet, range);
        }

        return null;
    }

    private static int MaxUsedRow(IXLWorksheet worksheet)
    {
        return worksheet.LastRowUsed()?.RowNumber() ?? 1;
    }

    private static int MaxUsedRow(IXLWorksheet worksheet, IEnumerable<int> columns)
    {
        var max = 0;
        foreach (var column in columns.Distinct())
        {
            try
            {
                var lastCell = worksheet.Column(column).LastCellUsed();
                if (lastCell is not null)
                {
                    max = Math.Max(max, lastCell.Address.RowNumber);
                }
            }
            catch (ArgumentException)
            {
                // A template can declare a column outside the used range.
            }
        }

        return max;
    }

    private static string BuildDescription(TemplatePlan plan, ExportOptions options, int rowCount)
    {
        var modeText = options.Mode == ExportWriteMode.Append ? "追加" : "覆盖";
        return $"工作表“{plan.Worksheet.Name}”，将从第 {plan.StartRow} 行开始写入；{modeText} {rowCount} 行" +
               (plan.ExistingRows > 0 ? $"，当前区域已有 {plan.ExistingRows} 行将被覆盖。" : "。");
    }

    private static XLWorkbook OpenTemplate(string templatePath)
    {
        if (!File.Exists(templatePath))
        {
            throw new FileNotFoundException("Excel 模板不存在。", templatePath);
        }

        return new XLWorkbook(templatePath, new LoadOptions { RecalculateAllFormulas = false });
    }

    private static bool PathsEqual(string first, string second)
    {
        return string.Equals(
            Path.GetFullPath(first),
            Path.GetFullPath(second),
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
    }

    private static void Replace(string tempPath, string destination)
    {
        if (!File.Exists(destination))
        {
            File.Move(tempPath, destination);
            return;
        }

        try
        {
            File.Replace(tempPath, destination, destinationBackupFileName: null, ignoreMetadataErrors: true);
        }
        catch (Exception ex) when (ex is IOException or PlatformNotSupportedException or UnauthorizedAccessException)
        {
            File.Move(tempPath, destination, overwrite: true);
        }
    }

    private sealed record TemplatePlan(
        IXLWorksheet Worksheet,
        int HeaderRow,
        int StartRow,
        Dictionary<string, int> FieldColumns,
        List<string> UnmappedFields,
        int ExistingRows);

    private sealed record NamedRangeMatch(IXLWorksheet Worksheet, IXLRange Range);
}
