using System.Text.Json;
using Serilog;
using TicketTool.Core.Data;
using TicketTool.Core.Models;

namespace TicketTool.Core.Snapshot;

/// <summary>未确认编辑态快照的保存与启动恢复判定。</summary>
public sealed class SnapshotService
{
    private readonly SnapshotRepository _repository;
    private readonly ILogger _logger;

    /// <summary>创建服务。</summary>
    public SnapshotService(SnapshotRepository repository, ILogger? logger = null)
    {
        _repository = repository;
        _logger = logger ?? Log.ForContext<SnapshotService>();
    }

    /// <summary>保存快照。</summary>
    public async Task<long> SaveAsync(SnapshotPayload payload, bool cleanExit, CancellationToken cancellationToken = default)
    {
        payload.SavedAt = DateTimeOffset.Now;
        var json = JsonSerializer.SerializeToUtf8Bytes(payload, JsonDefaults.Options);
        var id = await _repository.SaveAsync(CompressionService.CompressBytes(json), cleanExit, cancellationToken).ConfigureAwait(false);
        _logger.Information("SnapshotSaved {SnapshotId} {CleanExit} {Length} {Stage}", id, cleanExit, payload.RawText.Length, payload.Stage);
        return id;
    }

    /// <summary>判定启动恢复状态。</summary>
    public async Task<SnapshotStartupState> InspectStartupAsync(CancellationToken cancellationToken = default)
    {
        var latest = await _repository.GetLatestAsync(cancellationToken).ConfigureAwait(false);
        if (latest is null)
        {
            return new SnapshotStartupState { Kind = SnapshotStartupKind.None };
        }

        try
        {
            var json = CompressionService.DecompressBytes(latest.Value.Payload);
            var payload = JsonSerializer.Deserialize<SnapshotPayload>(json, JsonDefaults.Options);
            if (payload is null)
            {
                throw new JsonException("快照内容为空。");
            }

            if (latest.Value.CleanExit)
            {
                await _repository.ClearAsync(cancellationToken).ConfigureAwait(false);
                _logger.Information("SnapshotCleanDiscarded {SnapshotId}", latest.Value.Id);
                return new SnapshotStartupState
                {
                    Kind = SnapshotStartupKind.Clean,
                    SnapshotId = latest.Value.Id,
                    CreatedAt = latest.Value.CreatedAt,
                    Payload = payload
                };
            }

            var summary = $"保存时间：{latest.Value.CreatedAt:yyyy-MM-dd HH:mm:ss}\n" +
                          $"原文长度：{payload.RawText.Length} 个字符\n" +
                          $"模板：{payload.TemplateName ?? "未选择"}\n" +
                          $"阶段：{payload.Stage}\n" +
                          $"规则草稿：{(string.IsNullOrWhiteSpace(payload.RuleDraftJson) ? "无" : "有未保存修改")}";
            return new SnapshotStartupState
            {
                Kind = SnapshotStartupKind.Dirty,
                SnapshotId = latest.Value.Id,
                CreatedAt = latest.Value.CreatedAt,
                Summary = summary,
                Payload = payload
            };
        }
        catch (Exception ex) when (ex is JsonException or ZstdSharp.ZstdException or InvalidOperationException)
        {
            _logger.Error(ex, "SnapshotCorrupt {SnapshotId}", latest.Value.Id);
            await _repository.ClearAsync(cancellationToken).ConfigureAwait(false);
            return new SnapshotStartupState
            {
                Kind = SnapshotStartupKind.Corrupt,
                SnapshotId = latest.Value.Id,
                CreatedAt = latest.Value.CreatedAt,
                Error = "无法恢复，已清理损坏的快照。"
            };
        }
    }

    /// <summary>用户确认恢复后清理快照，避免重复弹窗。</summary>
    public Task AcknowledgeAsync(CancellationToken cancellationToken = default)
        => _repository.ClearAsync(cancellationToken);

    /// <summary>丢弃快照。</summary>
    public Task DiscardAsync(CancellationToken cancellationToken = default)
        => _repository.ClearAsync(cancellationToken);
}
