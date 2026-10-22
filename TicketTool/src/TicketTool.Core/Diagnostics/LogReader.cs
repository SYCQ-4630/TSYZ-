using System.Text.Json;
using TicketTool.Core.Data;
using TicketTool.Core.Models;

namespace TicketTool.Core.Diagnostics;

/// <summary>读取结构化日志文件，供应用内诊断面板过滤。</summary>
public sealed class LogReader
{
    private readonly AppPaths _paths;

    /// <summary>创建读取器。</summary>
    public LogReader(AppPaths paths) => _paths = paths;

    /// <summary>按条件读取最新日志。</summary>
    public Task<List<LogEntry>> ReadAsync(LogFilter filter, CancellationToken cancellationToken = default)
        => Task.Run(() => Read(filter, cancellationToken), cancellationToken);

    /// <summary>日志目录。</summary>
    public string LogsDirectory => _paths.LogsDirectory;

    private List<LogEntry> Read(LogFilter filter, CancellationToken cancellationToken)
    {
        var result = new List<LogEntry>();
        if (!Directory.Exists(_paths.LogsDirectory))
        {
            return result;
        }

        var files = Directory.EnumerateFiles(_paths.LogsDirectory, "ticket-*.log")
            .OrderByDescending(path => path, StringComparer.Ordinal)
            .ToList();

        var limit = Math.Clamp(filter.Limit, 1, 10000);
        foreach (var file in files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            foreach (var line in ReadLinesReverse(file))
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (string.IsNullOrWhiteSpace(line))
                {
                    continue;
                }

                var entry = Parse(line);
                if (entry is null || !Matches(entry, filter))
                {
                    continue;
                }

                result.Add(entry);
                if (result.Count >= limit)
                {
                    return result;
                }
            }
        }
        return result;
    }

    private static LogEntry? Parse(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            var timestamp = root.TryGetProperty("@t", out var timeElement)
                && DateTimeOffset.TryParse(timeElement.GetString(), out var parsed)
                ? parsed
                : DateTimeOffset.MinValue;
            var message = root.TryGetProperty("@m", out var messageElement)
                ? messageElement.GetString() ?? string.Empty
                : root.TryGetProperty("@mt", out var templateElement) ? templateElement.GetString() ?? string.Empty : line;
            var level = root.TryGetProperty("@l", out var levelElement) ? levelElement.GetString() ?? string.Empty : string.Empty;
            var module = root.TryGetProperty("SourceContext", out var sourceElement) ? sourceElement.GetString() ?? string.Empty : string.Empty;
            var exception = root.TryGetProperty("@x", out var exceptionElement) ? exceptionElement.GetString() : null;
            return new LogEntry
            {
                Timestamp = timestamp,
                Level = level,
                Module = module,
                Message = message,
                Exception = exception,
                RawJson = line
            };
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static bool Matches(LogEntry entry, LogFilter filter)
    {
        if (!string.IsNullOrWhiteSpace(filter.Level)
            && !string.Equals(entry.Level, filter.Level, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (!string.IsNullOrWhiteSpace(filter.Module)
            && !entry.Module.Contains(filter.Module, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (!string.IsNullOrWhiteSpace(filter.Keyword)
            && !entry.Message.Contains(filter.Keyword, StringComparison.OrdinalIgnoreCase)
            && !(entry.RawJson?.Contains(filter.Keyword, StringComparison.OrdinalIgnoreCase) ?? false))
        {
            return false;
        }

        if (filter.From is not null && entry.Timestamp < filter.From)
        {
            return false;
        }

        if (filter.To is not null && entry.Timestamp > filter.To)
        {
            return false;
        }

        return true;
    }

    private static IEnumerable<string> ReadLinesReverse(string path)
    {
        var lines = new List<string>();
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var reader = new StreamReader(stream, System.Text.Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
        while (reader.ReadLine() is { } line)
        {
            lines.Add(line);
        }
        lines.Reverse();
        return lines;
    }
}
