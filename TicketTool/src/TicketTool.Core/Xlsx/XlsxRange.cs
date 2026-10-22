namespace TicketTool.Core.Xlsx;

/// <summary>XLSX 区域引用，如 A1:C3。</summary>
internal readonly record struct XlsxRange(int StartRow, int EndRow, int StartColumn, int EndColumn)
{
    /// <summary>解析区域引用。</summary>
    public static bool TryParse(string? reference, out XlsxRange range)
    {
        range = default;
        if (string.IsNullOrWhiteSpace(reference))
        {
            return false;
        }

        var parts = reference.Split(':');
        if (parts.Length != 2
            || !TryParseCell(parts[0], out var startRow, out var startColumn)
            || !TryParseCell(parts[1], out var endRow, out var endColumn))
        {
            return false;
        }

        if (startRow <= 0 || endRow < startRow || endColumn < startColumn)
        {
            return false;
        }

        range = new XlsxRange(startRow, endRow, startColumn, endColumn);
        return true;
    }

    /// <summary>替换结束行。</summary>
    public XlsxRange WithEndRow(int endRow) => this with { EndRow = endRow };

    /// <summary>输出 A1:C3 形式的引用。</summary>
    public override string ToString()
        => $"{HeaderReader.GetColumnName(StartColumn)}{StartRow}:{HeaderReader.GetColumnName(EndColumn)}{EndRow}";

    private static bool TryParseCell(string text, out int row, out int column)
    {
        row = 0;
        column = 0;
        var value = text.Replace("$", string.Empty, StringComparison.Ordinal).Trim();
        var index = 0;
        var columnNumber = 0;
        while (index < value.Length && char.IsLetter(value[index]))
        {
            columnNumber = columnNumber * 26 + (char.ToUpperInvariant(value[index]) - 'A' + 1);
            index++;
        }

        if (columnNumber == 0 || index >= value.Length)
        {
            return false;
        }

        if (!int.TryParse(value[index..], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out row)
            || row <= 0)
        {
            return false;
        }

        column = columnNumber - 1;
        return true;
    }
}
