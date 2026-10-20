using System.Windows;
using DingTalkChatExporter.App.Services;
using DingTalkChatExporter.App.ViewModels;
using DingTalkChatExporter.Core.Management;
using Serilog;

namespace DingTalkChatExporter.App;

public partial class MainWindow : Window
{
    private readonly SnapshotService _snapshots;
    private readonly WindowPlacementService _placement;

    public MainWindow(
        MainViewModel viewModel,
        AppSnapshot snapshot,
        SnapshotService snapshots,
        ILogger logger)
    {
        InitializeComponent();
        DataContext = viewModel;
        _snapshots = snapshots;
        _placement = new WindowPlacementService(
            this,
            logger,
            placement =>
            {
                _snapshots.SetWindowPlacement(placement);
                return _snapshots.SaveAsync();
            });
        _placement.Attach(snapshot.WindowPlacement);
        Closed += OnClosed;
    }

    private void OnClosed(object? sender, EventArgs e)
    {
        _placement.Dispose();
        Closed -= OnClosed;
    }
}
