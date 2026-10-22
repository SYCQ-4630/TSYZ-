using System.Diagnostics;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using Serilog;
using TicketTool.Core.Models;

namespace TicketTool.Core.Xlsx;

/// <summary>写入目标表格的一个单元格值。</summary>
public sealed record XlsxCellValue(int ColumnIndex, string? Value);

/// <summary>
/// SAX 流式 XLSX 追加器：逐行读取旧表、写入临时文件、File.Replace 原子替换。
/// 任一时刻内存中只保留单行对象。
/// </summary>
public sealed class XlsxRowAppender
{
    private readonly ILogger _logger;

    /// <summary>创建追加器。</summary>
    public XlsxRowAppender(ILogger? logger = null)
    {
        _logger = logger ?? Log.ForContext<XlsxRowAppender>();
    }

    /// <summary>计算下一次写入的行号与列值预览。</summary>
    public async Task<XlsxWritePreview> BuildPreviewAsync(
        string filePath,
        IReadOnlyList<XlsxCellValue> cells,
        IReadOnlyList<TemplateField> fields,
        CancellationToken cancellationToken = default)
    {
        if (!File.Exists(filePath))
        {
            throw new XlsxFileMissingException(filePath);
        }

        var lastRow = await HeaderReader.GetLastRowNumberAsync(filePath, cancellationToken).ConfigureAwait(false);
        var cellsByColumn = cells.ToDictionary(c => c.ColumnIndex, c => c.Value);
        return new XlsxWritePreview
        {
            FilePath = filePath,
            RowNumber = lastRow + 1,
            Cells = fields
                .OrderBy(f => f.ColumnIndex)
                .Select(f => (f.ColumnIndex, f.FieldName, cellsByColumn.TryGetValue(f.ColumnIndex, out var value) ? value : null))
                .ToList()
        };
    }

    /// <summary>追加一行并原子替换目标文件。</summary>
    public async Task<XlsxAppendResult> AppendRowAsync(
        string filePath,
        IReadOnlyList<XlsxCellValue> cells,
        CancellationToken cancellationToken = default,
        int headerRow = 0)
    {
        if (!File.Exists(filePath))
        {
            throw new XlsxFileMissingException(filePath);
        }

        if (!FileLockDetector.IsWritable(filePath))
        {
            throw new XlsxFileLockedException(filePath);
        }

        var stopwatch = Stopwatch.StartNew();
        var directory = Path.GetDirectoryName(Path.GetFullPath(filePath))
                        ?? throw new XlsxStructureException("无法确定表格所在目录。");
        var tempPath = Path.Combine(directory, $".{Path.GetFileName(filePath)}.{Guid.NewGuid():N}.tmp");
        var backupPath = filePath + ".backup";

        try
        {
            File.Copy(filePath, tempPath, true);
            cancellationToken.ThrowIfCancellationRequested();
            var lastRow = await HeaderReader.GetLastRowNumberAsync(tempPath, cancellationToken).ConfigureAwait(false);
            var result = await Task.Run(() => AppendToFile(tempPath, cells, headerRow, lastRow, cancellationToken), cancellationToken).ConfigureAwait(false);

            cancellationToken.ThrowIfCancellationRequested();
            File.Replace(tempPath, filePath, backupPath, true);
            stopwatch.Stop();
            result.Elapsed = stopwatch.Elapsed;
            _logger.Information(
                "XlsxAppend {Path} {Row} {ElapsedMs}",
                filePath,
                result.RowNumber,
                stopwatch.Elapsed.TotalMilliseconds);
            return result;
        }
        finally
        {
            TryDelete(tempPath);
        }
    }

    private XlsxAppendResult AppendToFile(
        string tempPath,
        IReadOnlyList<XlsxCellValue> cells,
        int headerRow,
        int lastRow,
        CancellationToken cancellationToken)
    {
        var sourcePath = tempPath + ".source.xlsx";
        File.Copy(tempPath, sourcePath, true);
        try
        {
            using var sourceDocument = SpreadsheetDocument.Open(sourcePath, false);
            var sourceWorkbook = sourceDocument.WorkbookPart ?? throw new XlsxStructureException("表格缺少工作簿结构。");
            var sourceWorksheet = GetFirstWorksheetPart(sourceWorkbook);

            using var targetDocument = SpreadsheetDocument.Open(tempPath, true);
            var targetWorkbook = targetDocument.WorkbookPart ?? throw new XlsxStructureException("表格缺少工作簿结构。");
            var targetWorksheet = GetFirstWorksheetPart(targetWorkbook);

            var columnStyles = new Dictionary<int, uint>();
            var mergeProfile = XlsxFormatAccumulator.ReadMergeProfile(sourceWorksheet, headerRow, lastRow, cancellationToken);
            var formatAccumulator = new XlsxFormatAccumulator(headerRow);
            var insertedRow = 0;
            using (var reader = OpenXmlReader.Create(sourceWorksheet))
            using (var writer = OpenXmlWriter.Create(targetWorksheet))
            {
                var rootStarted = false;
                while (reader.Read())
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (reader.IsStartElement && reader.ElementType == typeof(Worksheet))
                    {
                        writer.WriteStartElement(new Worksheet());
                        rootStarted = true;
                        continue;
                    }

                    if (reader.IsEndElement && reader.ElementType == typeof(Worksheet))
                    {
                        writer.WriteEndElement();
                        break;
                    }

                    if (!rootStarted || !reader.IsStartElement)
                    {
                        continue;
                    }

                    if (reader.ElementType == typeof(Columns))
                    {
                        var columns = reader.LoadCurrentElement() as Columns;
                        if (columns is not null)
                        {
                            CollectColumnStyles(columns, columnStyles);
                            writer.WriteElement(columns);
                        }
                        continue;
                    }

                    if (reader.ElementType == typeof(SheetData))
                    {
                        writer.WriteStartElement(new SheetData());
                        var copied = CopyRows(reader, writer, formatAccumulator, cancellationToken);
                        insertedRow = Math.Max(copied.MaxRow, lastRow) + 1;
                        var rowFormat = formatAccumulator.Build();

                        writer.WriteElement(BuildRow(insertedRow, cells, rowFormat, columnStyles, mergeProfile.Spans));
                        writer.WriteEndElement();
                        continue;
                    }

                    if (reader.ElementType == typeof(AutoFilter))
                    {
                        var autoFilter = reader.LoadCurrentElement() as AutoFilter;
                        if (autoFilter is not null)
                        {
                            if (insertedRow > 0
                                && XlsxRange.TryParse(autoFilter.Reference?.Value, out var filterRange)
                                && filterRange.EndRow == insertedRow - 1)
                            {
                                autoFilter.Reference = new StringValue(filterRange.WithEndRow(insertedRow).ToString());
                            }
                            writer.WriteElement(autoFilter);
                            continue;
                        }
                    }

                    if (reader.ElementType == typeof(MergeCells))
                    {
                        var mergeCells = reader.LoadCurrentElement() as MergeCells;
                        if (mergeCells is null)
                        {
                            continue;
                        }

                        if (mergeProfile.Spans.Count > 0)
                        {
                            AppendMerges(mergeCells, insertedRow, mergeProfile.Spans);
                        }
                        writer.WriteElement(mergeCells);
                        continue;
                    }

                    writer.WriteElement(reader.LoadCurrentElement() ?? throw new XlsxStructureException("工作表结构异常。"));
                }
            }

            ExpandTableRanges(targetWorkbook, insertedRow);

            return new XlsxAppendResult
            {
                RowNumber = insertedRow,
                Cells = cells.ToDictionary(c => c.ColumnIndex, c => c.Value)
            };
        }
        finally
        {
            TryDelete(sourcePath);
        }
    }

    private sealed record RowCopyResult(int MaxRow);

    private static RowCopyResult CopyRows(
        OpenXmlReader reader,
        OpenXmlWriter writer,
        XlsxFormatAccumulator formatAccumulator,
        CancellationToken cancellationToken)
    {
        var maxRow = 0;

        while (reader.Read())
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (reader.IsEndElement && reader.ElementType == typeof(SheetData))
            {
                break;
            }

            if (!reader.IsStartElement || reader.ElementType != typeof(Row))
            {
                continue;
            }

            var row = reader.LoadCurrentElement() as Row ?? throw new XlsxStructureException("数据行结构异常。");
            var rowIndex = row.RowIndex?.Value ?? (uint)(maxRow + 1);
            maxRow = Math.Max(maxRow, (int)rowIndex);
            formatAccumulator.Add(row, (int)rowIndex);
            writer.WriteElement(row);
        }

        return new RowCopyResult(maxRow);
    }

    private static void CollectColumnStyles(Columns columns, Dictionary<int, uint> columnStyles)
    {
        foreach (var column in columns.Elements<Column>())
        {
            if (column.Style?.Value is not uint style)
            {
                continue;
            }

            var min = (int)(column.Min?.Value ?? 0);
            var max = (int)(column.Max?.Value ?? (uint)min);
            if (min <= 0 || max < min)
            {
                continue;
            }

            for (var index = min; index <= max && index - min < 16384; index++)
            {
                columnStyles[index - 1] = style;
            }
        }
    }

    private static Row BuildRow(
        int rowNumber,
        IReadOnlyList<XlsxCellValue> cells,
        XlsxRowFormat? format,
        IReadOnlyDictionary<int, uint> columnStyles,
        IReadOnlyList<ColumnMerge> merges)
    {
        var row = new Row { RowIndex = (uint)rowNumber };
        if (format?.RowStyleIndex is uint rowStyle)
        {
            row.StyleIndex = rowStyle;
        }
        if (format?.Height is double height)
        {
            row.Height = height;
            row.CustomHeight = true;
        }

        var values = cells.ToDictionary(c => c.ColumnIndex, c => c.Value);
        var mergedStyles = ApplyMergedValues(values, format, columnStyles, merges);

        var columns = new HashSet<int>(values.Keys);
        if (format is not null)
        {
            columns.UnionWith(format.CellStyles.Keys);
        }

        var mergeAnchors = new HashSet<int>();
        foreach (var merge in merges)
        {
            if (merge.EndColumn <= merge.StartColumn)
            {
                continue;
            }

            mergeAnchors.Add(merge.StartColumn);
            for (var column = merge.StartColumn + 1; column <= merge.EndColumn; column++)
            {
                columns.Remove(column);
            }
        }
        columns.UnionWith(mergedStyles.Keys);
        columns.UnionWith(mergeAnchors);

        foreach (var column in columns.OrderBy(c => c))
        {
            uint? style = null;
            if (mergedStyles.TryGetValue(column, out var mergedStyle))
            {
                style = mergedStyle;
            }
            else if (format is not null && format.CellStyles.TryGetValue(column, out var sourceStyle))
            {
                style = sourceStyle;
            }
            style ??= columnStyles.TryGetValue(column, out var columnStyle) ? columnStyle : null;

            var hasValue = values.TryGetValue(column, out var value) && !string.IsNullOrEmpty(value);
            if (!hasValue && style is null && !mergeAnchors.Contains(column))
            {
                continue;
            }

            var cell = new Cell
            {
                CellReference = HeaderReader.GetColumnName(column) + rowNumber.ToString(System.Globalization.CultureInfo.InvariantCulture)
            };
            if (style is uint cellStyle)
            {
                cell.StyleIndex = cellStyle;
            }
            if (hasValue)
            {
                cell.DataType = CellValues.InlineString;
                cell.InlineString = new InlineString(new Text(value!) { Space = SpaceProcessingModeValues.Preserve });
            }
            row.Append(cell);
        }

        return row;
    }

    private static Dictionary<int, uint> ApplyMergedValues(
        Dictionary<int, string?> values,
        XlsxRowFormat? format,
        IReadOnlyDictionary<int, uint> columnStyles,
        IReadOnlyList<ColumnMerge> merges)
    {
        var mergedStyles = new Dictionary<int, uint>();
        foreach (var merge in merges)
        {
            if (merge.EndColumn <= merge.StartColumn)
            {
                continue;
            }

            if (!HasText(values, merge.StartColumn))
            {
                for (var column = merge.StartColumn + 1; column <= merge.EndColumn; column++)
                {
                    if (HasText(values, column))
                    {
                        values[merge.StartColumn] = values[column];
                        break;
                    }
                }
            }

            for (var column = merge.StartColumn + 1; column <= merge.EndColumn; column++)
            {
                values.Remove(column);
            }

            uint? style = null;
            if (format is not null)
            {
                style = FirstStyle(format.CellStyles, merge);
            }
            style ??= FirstStyle(columnStyles, merge);
            if (style is uint mergedStyle)
            {
                mergedStyles[merge.StartColumn] = mergedStyle;
            }
        }
        return mergedStyles;
    }

    private static bool HasText(IReadOnlyDictionary<int, string?> values, int column)
        => values.TryGetValue(column, out var value) && !string.IsNullOrEmpty(value);

    private static uint? FirstStyle(IReadOnlyDictionary<int, uint> styles, ColumnMerge merge)
    {
        for (var column = merge.StartColumn; column <= merge.EndColumn; column++)
        {
            if (styles.TryGetValue(column, out var style))
            {
                return style;
            }
        }
        return null;
    }

    private static void AppendMerges(MergeCells mergeCells, int rowNumber, IReadOnlyList<ColumnMerge> spans)
    {
        foreach (var span in spans)
        {
            if (span.EndColumn <= span.StartColumn)
            {
                continue;
            }

            var start = $"{HeaderReader.GetColumnName(span.StartColumn)}{rowNumber}";
            var end = $"{HeaderReader.GetColumnName(span.EndColumn)}{rowNumber}";
            mergeCells.Append(new MergeCell { Reference = new StringValue($"{start}:{end}") });
        }
        mergeCells.Count = (uint)mergeCells.ChildElements.Count;
    }

    private static void ExpandTableRanges(WorkbookPart workbookPart, int insertedRow)
    {
        var worksheetPart = GetFirstWorksheetPart(workbookPart);
        foreach (var tablePart in worksheetPart.TableDefinitionParts)
        {
            var table = tablePart.Table;
            if (table is null)
            {
                continue;
            }

            if ((table.TotalsRowCount?.Value ?? 0) > 0 || table.TotalsRowShown?.Value == true)
            {
                continue;
            }

            if (!XlsxRange.TryParse(table.Reference?.Value, out var range)
                || range.EndRow != insertedRow - 1)
            {
                continue;
            }

            table.Reference = new StringValue(range.WithEndRow(insertedRow).ToString());

            var autoFilter = table.GetFirstChild<AutoFilter>();
            if (autoFilter is not null
                && XlsxRange.TryParse(autoFilter.Reference?.Value, out var filterRange)
                && filterRange.EndRow == range.EndRow)
            {
                autoFilter.Reference = new StringValue(filterRange.WithEndRow(insertedRow).ToString());
            }

            table.Save();
        }
    }

    private static WorksheetPart GetFirstWorksheetPart(WorkbookPart workbookPart)
    {
        var sheet = workbookPart.Workbook.Sheets?.Elements<Sheet>().FirstOrDefault()
                    ?? throw new XlsxStructureException("工作簿中没有工作表。");
        return workbookPart.GetPartById(sheet.Id?.Value ?? throw new XlsxStructureException("工作表缺少关系标识。")) as WorksheetPart
               ?? throw new XlsxStructureException("无法打开第一个工作表。");
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
            // 临时文件清理失败不影响主流程，操作系统后续会回收。
        }
    }
}
