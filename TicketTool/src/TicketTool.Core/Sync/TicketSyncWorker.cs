using System.Threading.Channels;
using Serilog;
using TicketTool.Core.Data;
using TicketTool.Core.Models;
using TicketTool.Core.Xlsx;

namespace TicketTool.Core.Sync;

/// <summary>
/// 全局唯一、单线程串行的 XLSX 同步 Worker。
/// 新导入只负责唤醒，写表严格由本 Worker 完成。
/// </summary>
public sealed class TicketSyncWorker : IAsyncDisposable
{
    private const int MaxRetry = 3;
    private const int BatchSize = 100;
    private readonly SyncQueueRepository _queue;
    private readonly TicketRepository _tickets;
    private readonly TemplateRepository _templates;
    private readonly XlsxRowAppender _appender;
    private readonly ILogger _logger;
    private readonly Channel<bool> _wakeup = Channel.CreateBounded<bool>(new BoundedChannelOptions(1)
    {
        FullMode = BoundedChannelFullMode.DropWrite,
        SingleReader = true,
        SingleWriter = false
    });

    private CancellationTokenSource? _cts;
    private Task? _loopTask;
    private bool _disposed;

    /// <summary>创建 Worker。</summary>
    public TicketSyncWorker(
        SyncQueueRepository queue,
        TicketRepository tickets,
        TemplateRepository templates,
        XlsxRowAppender appender,
        ILogger? logger = null)
    {
        _queue = queue;
        _tickets = tickets;
        _templates = templates;
        _appender = appender;
        _logger = logger ?? Log.ForContext<TicketSyncWorker>();
    }

    /// <summary>状态变化事件，供 UI 刷新徽标。</summary>
    public event EventHandler? StatusChanged;

    /// <summary>启动循环并执行启动补偿同步。</summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (_loopTask is not null)
        {
            return;
        }

        await _queue.ResetStaleSyncingAsync(cancellationToken).ConfigureAwait(false);
        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _loopTask = Task.Run(() => RunAsync(_cts.Token), CancellationToken.None);
        Notify();
    }

    /// <summary>唤醒 Worker（新数据入库、手动重试）。</summary>
    public void Notify() => _wakeup.Writer.TryWrite(true);

    /// <summary>手动重试失败项。</summary>
    public async Task RetryAsync(long queueId, CancellationToken cancellationToken = default)
    {
        await _queue.RetryFailedAsync(queueId, cancellationToken).ConfigureAwait(false);
        Notify();
        RaiseStatusChanged();
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        var includeFailed = true;
        _logger.Information("SyncWorkerStarted");
        while (!cancellationToken.IsCancellationRequested)
        {
            var processed = 0;
            try
            {
                processed = await ProcessBatchAsync(includeFailed, cancellationToken).ConfigureAwait(false);
                includeFailed = false;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.Error(ex, "SyncWorkerLoopError");
            }

            if (processed >= BatchSize)
            {
                continue;
            }

            try
            {
                using var delayCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                var delay = Task.Delay(TimeSpan.FromSeconds(30), delayCts.Token);
                var signal = _wakeup.Reader.WaitToReadAsync(cancellationToken).AsTask();
                var completed = await Task.WhenAny(delay, signal).ConfigureAwait(false);
                if (completed == signal && await signal.ConfigureAwait(false))
                {
                    while (_wakeup.Reader.TryRead(out _))
                    {
                    }
                }
                else
                {
                    await delayCts.CancelAsync().ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
        _logger.Information("SyncWorkerStopped");
    }

    private async Task<int> ProcessBatchAsync(bool includeFailed, CancellationToken cancellationToken)
    {
        var items = await _queue.GetCandidatesAsync(BatchSize, includeFailed, cancellationToken).ConfigureAwait(false);
        var processed = 0;

        foreach (var item in items)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (item.Status == SyncStatus.Synced)
            {
                continue;
            }

            if (item.Status == SyncStatus.Failed && !includeFailed)
            {
                continue;
            }

            if (!await _queue.TryMarkSyncingAsync(item.Id, cancellationToken).ConfigureAwait(false))
            {
                continue;
            }

            processed++;
            RaiseStatusChanged();
            await ProcessOneAsync(item, cancellationToken).ConfigureAwait(false);
            RaiseStatusChanged();
        }

        return processed;
    }

    private async Task ProcessOneAsync(SyncQueueItem item, CancellationToken cancellationToken)
    {
        var data = await _tickets.GetDataAsync(item.TicketId, cancellationToken).ConfigureAwait(false);
        if (data is null)
        {
            await _queue.MarkFailedAsync(item.Id, "工单记录不存在。", cancellationToken).ConfigureAwait(false);
            return;
        }

        var template = await _templates.GetAsync(data.Value.TemplateId, cancellationToken).ConfigureAwait(false);
        if (template is null)
        {
            await _queue.MarkFailedAsync(item.Id, "目标模板不存在。", cancellationToken).ConfigureAwait(false);
            return;
        }

        var values = TicketRepository.ParseDataJson(data.Value.DataJson);
        var cells = template.Fields
            .Select(field => new XlsxCellValue(field.ColumnIndex,
                values.TryGetValue(field.FieldName, out var value) ? value : null))
            .ToList();

        try
        {
            var result = await _appender.AppendRowAsync(template.FilePath, cells, cancellationToken).ConfigureAwait(false);
            await _queue.MarkSyncedAsync(item.Id, cancellationToken).ConfigureAwait(false);
            _logger.Information(
                "SyncSucceeded {TicketId} {QueueId} {TemplateId} {Row} {Retry}",
                item.TicketId,
                item.Id,
                template.Id,
                result.RowNumber,
                item.Retry);
        }
        catch (XlsxFileLockedException ex)
        {
            await _queue.MarkBlockedAsync(item.Id, ex.Message, cancellationToken).ConfigureAwait(false);
            _logger.Warning("SyncBlocked {TicketId} {QueueId} {Retry}", item.TicketId, item.Id, item.Retry + 1);
        }
        catch (XlsxFileMissingException ex)
        {
            await _queue.MarkFailedAsync(item.Id, ex.Message, cancellationToken).ConfigureAwait(false);
            _logger.Error(ex, "SyncFileMissing {TicketId} {QueueId}", item.TicketId, item.Id);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            var retry = item.Retry + 1;
            if (retry >= MaxRetry)
            {
                await _queue.MarkFailedAsync(item.Id, ex.Message, cancellationToken).ConfigureAwait(false);
                _logger.Error(ex, "SyncFailed {TicketId} {QueueId} {Retry}", item.TicketId, item.Id, retry);
            }
            else
            {
                await _queue.MarkBlockedAsync(item.Id, ex.Message, cancellationToken).ConfigureAwait(false);
                _logger.Warning(ex, "SyncRetryScheduled {TicketId} {QueueId} {Retry}", item.TicketId, item.Id, retry);
            }
        }
    }

    private void RaiseStatusChanged()
    {
        try
        {
            StatusChanged?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception ex)
        {
            _logger.Warning(ex, "SyncStatusChangedHandlerFailed");
        }
    }

    /// <inheritdoc />
    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        if (_cts is not null)
        {
            await _cts.CancelAsync().ConfigureAwait(false);
        }
        if (_loopTask is not null)
        {
            try
            {
                await _loopTask.WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
                _logger.Warning("SyncWorkerStopTimeout");
            }
            catch (OperationCanceledException)
            {
            }
        }
        _cts?.Dispose();
    }
}
