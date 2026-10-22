using TicketTool.Core.Data;
using TicketTool.Core.Models;
using TicketTool.Core.Snapshot;

namespace TicketTool.Tests;

public sealed class SnapshotRecoveryTests
{
    private static (Database Db, SnapshotRepository Repo, SnapshotService Service, TempWorkspace Workspace) Create()
    {
        var workspace = new TempWorkspace();
        var paths = new AppPaths(workspace.Root);
        var db = new Database(paths);
        new DatabaseInitializer(db).InitializeAsync().GetAwaiter().GetResult();
        var repo = new SnapshotRepository(db);
        return (db, repo, new SnapshotService(repo), workspace);
    }

    [Fact]
    public async Task DirtySnapshotIsOfferedForRecovery()
    {
        var (db, _, service, workspace) = Create();
        await using var _db = db;
        using var __ = workspace;

        await service.SaveAsync(new SnapshotPayload
        {
            RawText = "工单号：GD1",
            TemplateName = "通信工单",
            Stage = "预览确认",
            RuleDraftJson = "{}"
        }, cleanExit: false);

        var state = await service.InspectStartupAsync();
        Assert.Equal(SnapshotStartupKind.Dirty, state.Kind);
        Assert.Equal("工单号：GD1", state.Payload!.RawText);
        Assert.Contains("通信工单", state.Summary);
    }

    [Fact]
    public async Task CleanSnapshotIsSilentlyDiscarded()
    {
        var (db, _, service, workspace) = Create();
        await using var _db = db;
        using var __ = workspace;

        await service.SaveAsync(new SnapshotPayload { RawText = "done" }, cleanExit: true);
        var state = await service.InspectStartupAsync();
        Assert.Equal(SnapshotStartupKind.Clean, state.Kind);

        var next = await service.InspectStartupAsync();
        Assert.Equal(SnapshotStartupKind.None, next.Kind);
    }

    [Fact]
    public async Task CorruptSnapshotDoesNotBlockStartup()
    {
        var (db, repo, service, workspace) = Create();
        await using var _db = db;
        using var __ = workspace;

        await repo.SaveAsync(new byte[] { 1, 2, 3, 4 }, cleanExit: false);
        var state = await service.InspectStartupAsync();

        Assert.Equal(SnapshotStartupKind.Corrupt, state.Kind);
        Assert.Equal(SnapshotStartupKind.None, (await service.InspectStartupAsync()).Kind);
    }

    [Fact]
    public async Task SnapshotRetentionKeepsLatestThirty()
    {
        var (db, _, service, workspace) = Create();
        await using var _db = db;
        using var __ = workspace;

        for (var i = 0; i < 35; i++)
        {
            await service.SaveAsync(new SnapshotPayload { RawText = $"text-{i}" }, cleanExit: false);
        }

        var latest = await service.InspectStartupAsync();
        Assert.Equal("text-34", latest.Payload!.RawText);
    }
}
