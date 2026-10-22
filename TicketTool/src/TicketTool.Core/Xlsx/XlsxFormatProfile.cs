using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;

namespace TicketTool.Core.Xlsx;

/// <summary>需要在新行中复刻的横向合并区域。</summary>
internal sealed record ColumnMerge(int StartColumn, int EndColumn);

/// <summary>现有数据行的合并区域检测结果。</summary>
internal sealed record MergeProfile(IReadOnlyList<ColumnMerge> Spans)
{
    /// <summary>没有可复刻的合并区域。</summary>
    public static readonly MergeProfile Empty = new(Array.Empty<ColumnMerge>());
}

/// <summary>从现有数据行汇总出的格式模板。</summary>
internal sealed record XlsxRowFormat(
    uint? RowStyleIndex,
    double? Height,
    IReadOnlyDictionary<int, uint> CellStyles);

/// <summary>
/// 统计数据行的行高、行样式与每列单元格样式，取出现次数最多的格式作为新行模板。
/// 表头行不参与统计，避免把标题的加粗和背景色带到数据行。
/// </summary>
internal sealed class XlsxFormatAccumulator
{
    private const int MergeLookbackRows = 12;

    private readonly int _headerRow;
    private readonly Dictionary<int, Frequency<uint>> _columnStyles = new();
    private readonly Frequency<uint> _rowStyles = new();
    private readonly Frequency<double> _heights = new();
    private int _dataRows;
    private int _order;

    public XlsxFormatAccumulator(int headerRow) => _headerRow = headerRow;

    /// <summary>收集一行数据。</summary>
    public void Add(Row row, int rowIndex)
    {
        if (rowIndex <= 0 || (_headerRow > 0 && rowIndex <= _headerRow))
        {
            return;
        }

        _dataRows++;
        _order++;

        if (row.StyleIndex?.Value is uint rowStyle && rowStyle != 0)
        {
            _rowStyles.Add(rowStyle, _order);
        }

        if (row.Height?.Value is double height && height > 0)
        {
            _heights.Add(height, _order);
        }

        var fallbackColumn = 0;
        foreach (var cell in row.Elements<Cell>())
        {
            var column = HeaderReader.ParseColumnIndex(cell.CellReference?.Value) ?? fallbackColumn;
            fallbackColumn = column + 1;
            if (cell.StyleIndex?.Value is not uint style || style == 0)
            {
                continue;
            }

            if (!_columnStyles.TryGetValue(column, out var frequency))
            {
                frequency = new Frequency<uint>();
                _columnStyles[column] = frequency;
            }
            frequency.Add(style, _order);
        }
    }

    /// <summary>生成行格式模板；没有数据行时返回 null。</summary>
    public XlsxRowFormat? Build()
    {
        if (_dataRows == 0)
        {
            return null;
        }

        var cellStyles = new Dictionary<int, uint>();
        foreach (var pair in _columnStyles)
        {
            if (pair.Value.TryGetBest(out var style))
            {
                cellStyles[pair.Key] = style;
            }
        }

        uint? rowStyle = _rowStyles.TryGetBest(out var rowStyleValue) ? rowStyleValue : null;
        double? height = _heights.TryGetBest(out var heightValue) ? heightValue : null;
        if (cellStyles.Count == 0 && rowStyle is null && height is null)
        {
            return null;
        }

        return new XlsxRowFormat(rowStyle, height, cellStyles);
    }

    /// <summary>找到最近数据行使用的横向合并区域，用于复刻到新行。</summary>
    public static MergeProfile ReadMergeProfile(
        WorksheetPart worksheetPart,
        int headerRow,
        int lastRow,
        CancellationToken cancellationToken)
    {
        if (lastRow <= 0)
        {
            return MergeProfile.Empty;
        }

        var candidates = new List<(int Row, int StartColumn, int EndColumn)>();
        using var reader = OpenXmlReader.Create(worksheetPart);
        while (reader.Read())
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!reader.IsStartElement)
            {
                continue;
            }

            if (reader.ElementType != typeof(MergeCells))
            {
                continue;
            }

            var mergeCells = reader.LoadCurrentElement() as MergeCells;
            if (mergeCells is null)
            {
                continue;
            }

            foreach (var mergeCell in mergeCells.Elements<MergeCell>())
            {
                if (!XlsxRange.TryParse(mergeCell.Reference?.Value, out var range)
                    || range.StartRow != range.EndRow
                    || range.EndRow <= headerRow
                    || range.EndRow > lastRow)
                {
                    continue;
                }

                candidates.Add((range.EndRow, range.StartColumn, range.EndColumn));
            }
        }

        if (candidates.Count == 0)
        {
            return MergeProfile.Empty;
        }

        var sourceRow = candidates.Max(item => item.Row);
        if (sourceRow < lastRow - MergeLookbackRows)
        {
            return MergeProfile.Empty;
        }

        var spans = candidates
            .Where(item => item.Row == sourceRow)
            .GroupBy(item => (item.StartColumn, item.EndColumn))
            .Select(group => new ColumnMerge(group.Key.StartColumn, group.Key.EndColumn))
            .OrderBy(item => item.StartColumn)
            .ToList();
        return new MergeProfile(spans);
    }

    private sealed class Frequency<T> where T : notnull
    {
        private readonly Dictionary<T, Entry> _entries = new();

        public void Add(T value, int order)
        {
            if (_entries.TryGetValue(value, out var entry))
            {
                _entries[value] = new Entry(entry.Count + 1, order);
            }
            else
            {
                _entries[value] = new Entry(1, order);
            }
        }

        public bool TryGetBest(out T value)
        {
            var found = false;
            var bestCount = 0;
            var bestOrder = -1;
            value = default!;
            foreach (var pair in _entries)
            {
                if (pair.Value.Count > bestCount
                    || (pair.Value.Count == bestCount && pair.Value.LastOrder > bestOrder))
                {
                    found = true;
                    bestCount = pair.Value.Count;
                    bestOrder = pair.Value.LastOrder;
                    value = pair.Key;
                }
            }
            return found;
        }

        private readonly record struct Entry(int Count, int LastOrder);
    }
}
