using System.Text.Json;
using DingTalkChatExporter.Core.Management;
using DingTalkChatExporter.Core.Models;

namespace DingTalkChatExporter.Tests;

public class ModelSerializationTests
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = false
    };

    [Fact]
    public void RawMessage_RoundTrips_WithUnicodeContent()
    {
        var message = new RawMessage(
            "msg-001",
            "张三",
            new DateTimeOffset(2026, 10, 19, 9, 30, 0, TimeSpan.FromHours(8)),
            "订单 A-1024 已发货，金额 128.50 元",
            "text");

        var json = JsonSerializer.Serialize(message, JsonOptions);
        var restored = JsonSerializer.Deserialize<RawMessage>(json, JsonOptions);

        Assert.NotNull(restored);
        Assert.Equal(message.MessageId, restored!.MessageId);
        Assert.Equal(message.SenderName, restored.SenderName);
        Assert.Equal(message.SentAt, restored.SentAt);
        Assert.Equal(message.Content, restored.Content);
        Assert.Equal(message.MessageType, restored.MessageType);
    }

    [Fact]
    public void AppSnapshot_RoundTrips_PerGroupPreviewsAndPlacement()
    {
        var snapshot = new AppSnapshot
        {
            CurrentGroupIdentifier = "group-a",
            CurrentDataSource = "dws",
            CurrentTemplatePath = @"C:\templates\orders.xlsx",
            CurrentRuleLibraryId = "lib-1",
            LastNavigationPage = "Workbench",
            WindowPlacement = new WindowPlacementState
            {
                NormalLeft = -1920,
                NormalTop = 120,
                NormalRight = -200,
                NormalBottom = 900,
                ShowCommand = 1
            },
            Previews =
            {
                ["group-a"] = new GroupPreviewState
                {
                    GroupIdentifier = "group-a",
                    DataSourceName = "dws",
                    Messages =
                    {
                        new PreviewMessage
                        {
                            Message = new RawMessage("m1", "李四", DateTimeOffset.UtcNow, "hello", "text"),
                            ExtractedFields =
                            {
                                ["OrderNo"] = new ExtractionResult("A-1024", Confidence.High),
                                ["Amount"] = new ExtractionResult(null, Confidence.None)
                            }
                        }
                    }
                }
            },
            IncrementalStates =
            {
                ["group-a"] = new GroupIncrementalState
                {
                    GroupIdentifier = "group-a",
                    LastFetchedMessageId = "m1",
                    LastFetchedAt = DateTimeOffset.UtcNow
                }
            }
        };

        var json = JsonSerializer.Serialize(snapshot, JsonOptions);
        var restored = JsonSerializer.Deserialize<AppSnapshot>(json, JsonOptions);

        Assert.NotNull(restored);
        Assert.Equal(AppSnapshot.CurrentSchemaVersion, restored!.SchemaVersion);
        Assert.Equal("group-a", restored.CurrentGroupIdentifier);
        Assert.True(restored.WindowPlacement!.NormalLeft < 0);
        Assert.Single(restored.Previews);
        Assert.Equal(Confidence.High, restored.Previews["group-a"].Messages[0].ExtractedFields["OrderNo"].Confidence);
        Assert.Equal("m1", snapshot.IncrementalStates["group-a"].LastFetchedMessageId);
        Assert.DoesNotContain("IncrementalStates", json);
        Assert.DoesNotContain("state.json", json);
    }

    [Fact]
    public void FeatureSettings_Defaults_KeepRiskyFeaturesOff()
    {
        var settings = new FeatureSettings();

        Assert.False(settings.AllowFlaUiFallback);
        Assert.False(settings.HideAppDuringFlaUiCapture);
        Assert.False(settings.OnboardingCompleted);
        Assert.True(settings.EnableIncrementalRead);
        Assert.True(settings.BackupBeforeExport);
        Assert.True(settings.SnapshotPreviewResults);
    }

    [Fact]
    public void RemovedTableEntry_RoundTrips_WithOriginalFilePath()
    {
        var entry = new RemovedTableEntry
        {
            TableId = Guid.NewGuid(),
            DisplayName = "订单表",
            FilePath = @"C:\data\orders.xlsx",
            BoundRuleLibraryId = "lib-1"
        };

        var json = JsonSerializer.Serialize(entry, JsonOptions);
        var restored = JsonSerializer.Deserialize<RemovedTableEntry>(json, JsonOptions);

        Assert.NotNull(restored);
        Assert.Equal(entry.Id, restored!.Id);
        Assert.Equal(entry.FilePath, restored.FilePath);
        Assert.Equal("lib-1", restored.BoundRuleLibraryId);
    }

    [Fact]
    public void ManagedTable_DefaultsToNotIndexed_AndActiveFalse()
    {
        var table = new ManagedTable();

        Assert.False(table.IsActive);
        Assert.Equal(IndexStatus.NotBuilt, table.IndexStatus);
        Assert.Null(table.IndexedAt);
        Assert.Null(table.BoundRuleLibraryId);
    }
}
