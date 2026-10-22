package com.tsyz.autoledger.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tsyz.autoledger.AppContainer
import com.tsyz.autoledger.capture.ForegroundGuardService
import com.tsyz.autoledger.capture.importer.ImportResult
import com.tsyz.autoledger.core.model.IngestStatus
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.core.util.Sanitizer
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.data.db.BackupManager
import com.tsyz.autoledger.data.db.DatabaseProvider
import com.tsyz.autoledger.data.db.PendingReconciliationEntity
import com.tsyz.autoledger.data.db.TransactionEntity
import com.tsyz.autoledger.data.repo.ManualEntry
import com.tsyz.autoledger.report.CategoryTotal
import com.tsyz.autoledger.report.DailyPoint
import com.tsyz.autoledger.report.HealthLine
import com.tsyz.autoledger.report.MerchantTotal
import com.tsyz.autoledger.snapshot.SnapshotCodec
import com.tsyz.autoledger.snapshot.UiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.ledger
    private val snapshotManager = container.snapshots
    private val draftManager = container.drafts

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _tabIndex = MutableStateFlow(0)
    val tabIndex: StateFlow<Int> = _tabIndex.asStateFlow()

    private val _screenStack = MutableStateFlow<List<String>>(emptyList())
    val screenStack: StateFlow<List<String>> = _screenStack.asStateFlow()

    private val _filters = MutableStateFlow(FiltersState())
    val filters: StateFlow<FiltersState> = _filters.asStateFlow()

    private val _chartMode = MutableStateFlow(ChartMode.BAR)
    val chartMode: StateFlow<ChartMode> = _chartMode.asStateFlow()

    private val _scrollPositions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val scrollPositions: StateFlow<Map<String, Int>> = _scrollPositions.asStateFlow()

    private val _bulkMode = MutableStateFlow(false)
    val bulkMode: StateFlow<Boolean> = _bulkMode.asStateFlow()

    private val _bulkSelection = MutableStateFlow<Set<Long>>(emptySet())
    val bulkSelection: StateFlow<Set<Long>> = _bulkSelection.asStateFlow()

    private val _noteEditingId = MutableStateFlow<Long?>(null)
    val noteEditingId: StateFlow<Long?> = _noteEditingId.asStateFlow()

    private val _noteDraft = MutableStateFlow<NoteDraftState?>(null)
    val noteDraft: StateFlow<NoteDraftState?> = _noteDraft.asStateFlow()

    private val _manualForm = MutableStateFlow(ManualFormState())
    val manualForm: StateFlow<ManualFormState> = _manualForm.asStateFlow()

    private val _logModule = MutableStateFlow<String?>(null)
    val logModule: StateFlow<String?> = _logModule.asStateFlow()

    private val _backups = MutableStateFlow<List<BackupInfo>>(emptyList())
    val backups: StateFlow<List<BackupInfo>> = _backups.asStateFlow()

    private val _ruleInfo = MutableStateFlow(RuleInfo(0, 0))
    val ruleInfo: StateFlow<RuleInfo> = _ruleInfo.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    private val eventChannel = Channel<String>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val transactions: StateFlow<List<TransactionEntity>> = repo.transactions.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val pendingCount: StateFlow<Int> = repo.pendingCount.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), 0
    )

    val pendingList: StateFlow<List<PendingReconciliationEntity>> = repo.pending.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val settings = container.settings.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), com.tsyz.autoledger.data.prefs.AppSettings()
    )

    val healthStatuses = container.health.observe().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val logs = _logModule.flatMapLatest { module ->
        if (module.isNullOrBlank()) container.logger.observeRecent(400)
        else container.logger.observeByModule(module, 400)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredTransactions: StateFlow<List<TransactionEntity>> =
        combine(transactions, _filters) { list, filter ->
            val (from, to) = rangeBounds(filter.range)
            list.filter { tx ->
                tx.occurredAt in from until to &&
                    (filter.category == null || tx.category == filter.category) &&
                    (filter.type == TypeFilter.ALL || tx.type == filter.type.name)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val report: StateFlow<ReportUiState?> =
        combine(transactions, pendingCount, healthStatuses, _filters) { list, pending, health, filter ->
            ReportInput(list, pending, health, filter)
        }.mapLatest { input ->
            buildReport(input)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val stored = container.consumeRestoreCandidate()
            val snapshot = runCatching { snapshotManager.restore(stored) }.getOrNull()
            if (snapshot != null) applySnapshot(snapshot)
            val draftsRestored = restoreDrafts()
            if (snapshot != null || draftsRestored) {
                eventChannel.trySend("已恢复上次编辑内容")
            }
            val noteId = _noteEditingId.value
            if (noteId != null && _noteDraft.value?.txId != noteId) {
                loadNoteState(noteId)
            }
            val current = container.settings.current()
            if (!current.onboardingDone && _screenStack.value.isEmpty()) {
                _screenStack.value = listOf(ONBOARDING)
            }
            _ready.value = true
            loadBackups()
            loadRuleInfo()
        }
    }

    fun selectTab(index: Int) {
        _tabIndex.value = index.coerceIn(0, 3)
        persistSnapshot()
    }

    fun setRange(range: ReportRange) {
        _filters.update { it.copy(range = range) }
        persistSnapshot()
    }

    fun setCategory(category: String?) {
        _filters.update { it.copy(category = category) }
        persistSnapshot()
    }

    fun setTypeFilter(type: TypeFilter) {
        _filters.update { it.copy(type = type) }
        persistSnapshot()
    }

    fun setChartMode(mode: ChartMode) {
        _chartMode.value = mode
        persistSnapshot()
    }

    fun onScroll(section: String, index: Int) {
        _scrollPositions.update { it + (section to index.coerceAtLeast(0)) }
        persistSnapshot()
    }

    fun pushScreen(route: String) {
        if (_screenStack.value.lastOrNull() == route) return
        _screenStack.update { it + route }
        when (route) {
            ROUTE_BACKUP -> loadBackups()
            ROUTE_RULES -> loadRuleInfo()
        }
        persistSnapshot()
    }

    fun popScreen() {
        val removed = _screenStack.value.lastOrNull() ?: return
        _screenStack.update { it.dropLast(1) }
        if (removed == ROUTE_MANUAL) {
            viewModelScope.launch {
                draftManager.consume(SCENE_MANUAL)
                _manualForm.value = ManualFormState()
            }
        }
        persistSnapshot()
    }

    fun openOnboarding() {
        if (_screenStack.value.contains(ONBOARDING)) return
        pushScreen(ONBOARDING)
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            container.settings.setOnboardingDone(true)
            _screenStack.value = _screenStack.value.filterNot { it == ONBOARDING }
            eventChannel.trySend("引导已完成，可随时在管理页重新打开")
            persistSnapshot()
        }
    }

    fun openNote(txId: Long) {
        viewModelScope.launch { loadNoteState(txId) }
    }

    fun updateNoteDraft(note: String) {
        val current = _noteDraft.value ?: return
        val updated = current.copy(note = note.take(500))
        _noteDraft.value = updated
        saveNoteDraft(updated)
    }

    fun updateNoteCategory(category: String) {
        val current = _noteDraft.value ?: return
        val updated = current.copy(category = category)
        _noteDraft.value = updated
        saveNoteDraft(updated)
    }

    fun saveNote() {
        val draft = _noteDraft.value ?: return
        viewModelScope.launch {
            repo.updateNote(draft.txId, draft.note)
            repo.updateCategory(draft.txId, draft.category)
            draftManager.consume(sceneNote(draft.txId))
            _noteEditingId.value = null
            _noteDraft.value = null
            eventChannel.trySend("备注与分类已保存，未新增流水")
            persistSnapshot()
        }
    }

    fun cancelNote() {
        val id = _noteEditingId.value
        viewModelScope.launch {
            if (id != null) draftManager.consume(sceneNote(id))
            _noteEditingId.value = null
            _noteDraft.value = null
            persistSnapshot()
        }
    }

    fun openManual() {
        viewModelScope.launch {
            draftManager.load(SCENE_MANUAL)?.let { draft ->
                runCatching { parseManual(draft.payloadJson) }.getOrNull()?.let {
                    _manualForm.value = it
                }
            }
            pushScreen(ROUTE_MANUAL)
        }
    }

    fun updateManualForm(form: ManualFormState) {
        _manualForm.value = form
        draftManager.requestSave(SCENE_MANUAL, encodeManual(form))
    }

    fun saveManual() {
        val form = _manualForm.value
        val cents = Money.parseToCents(form.amount)
        if (cents == null || cents <= 0) {
            eventChannel.trySend("请输入有效金额")
            return
        }
        viewModelScope.launch {
            val result = repo.addManual(
                ManualEntry(
                    amountCents = cents,
                    merchant = form.merchant,
                    category = form.category,
                    type = form.type,
                    occurredAt = TimeUtils.parseDateOrNow(form.date),
                    note = form.note
                )
            )
            result.onSuccess {
                draftManager.consume(SCENE_MANUAL)
                _manualForm.value = ManualFormState()
                _screenStack.update { stack -> stack.dropLastWhile { it == ROUTE_MANUAL } }
                eventChannel.trySend("已补录，重复提交将被幂等去重")
                persistSnapshot()
            }.onFailure {
                eventChannel.trySend(it.message ?: "补录失败")
            }
        }
    }

    fun setBulkMode(enabled: Boolean) {
        _bulkMode.value = enabled
        if (!enabled) {
            _bulkSelection.value = emptySet()
            viewModelScope.launch { draftManager.consume(SCENE_BULK) }
        }
        persistSnapshot()
    }

    fun toggleSelection(id: Long) {
        _bulkSelection.update { if (id in it) it - id else it + id }
        saveBulkDraft()
        persistSnapshot()
    }

    fun applyBulkCategory(category: String) {
        val ids = _bulkSelection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repo.updateCategories(ids, category)
            draftManager.consume(SCENE_BULK)
            _bulkMode.value = false
            _bulkSelection.value = emptySet()
            eventChannel.trySend("已批量更新 ${ids.size} 笔分类")
            persistSnapshot()
        }
    }

    fun confirmPending(id: Long) {
        viewModelScope.launch {
            val pending = container.db.pendingDao().byId(id)
            if (pending == null) {
                eventChannel.trySend("待核对记录不存在")
                return@launch
            }
            val result = container.pipeline.promotePending(pending, maxOf(pending.confidence, 0.9))
            when (result.status) {
                IngestStatus.POSTED -> eventChannel.trySend("已确认入账")
                IngestStatus.DUPLICATE -> eventChannel.trySend("该笔已存在，未重复入账")
                else -> eventChannel.trySend("处理失败，请查看日志")
            }
        }
    }

    fun setLogModule(module: String?) {
        _logModule.value = module
    }

    fun setOcrEnabled(enabled: Boolean) = updateSetting {
        container.settings.setOcrEnabled(enabled)
    }

    fun setNotificationCapture(enabled: Boolean) = updateSetting {
        container.settings.setNotificationCaptureEnabled(enabled)
    }

    fun setMonthlyReportEnabled(enabled: Boolean) = updateSetting {
        container.settings.setMonthlyReportEnabled(enabled)
        container.scheduler.apply(container.settings.current())
    }

    fun setForegroundEnabled(enabled: Boolean) = updateSetting {
        container.settings.setForegroundServiceEnabled(enabled)
        if (enabled) ForegroundGuardService.start(container.app) else ForegroundGuardService.stop(container.app)
    }

    fun setAutoPostThreshold(value: Float) = updateSetting {
        container.settings.setAutoPostThreshold(value)
    }

    fun setLogRetention(days: Int) = updateSetting {
        container.settings.setLogRetentionDays(days)
    }

    fun createBackupNow() {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                val passphrase = DatabaseProvider.passphrase(container.app)
                val file = BackupManager(container.app).createSnapshot(passphrase)
                if (file != null) "已生成加密快照 ${file.name}" else "快照失败，请查看日志"
            }
            loadBackups()
            eventChannel.trySend(message)
        }
    }

    fun importBill(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            _importResult.value = null
            val result = runCatching { container.importer.importCsv(uri) }
            _importing.value = false
            result.onSuccess {
                _importResult.value = it
                eventChannel.trySend("导入完成：入账 ${it.posted}，待核对 ${it.pending}，重复 ${it.duplicate}")
            }.onFailure {
                eventChannel.trySend("导入失败：${it.message ?: "未知错误"}")
            }
        }
    }

    fun importRules(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    container.app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalArgumentException("无法读取规则文件")
                }
                container.rules.importJson(text)
                container.rules.reload()
                val reparse = container.reparse.reparseRecentFailures()
                loadRuleInfo()
                "规则已更新，回溯 ${reparse.attempted} 条，修复入账 ${reparse.posted}，待核对 ${reparse.pending}"
            }
            result.onSuccess { eventChannel.trySend(it) }
                .onFailure { eventChannel.trySend("规则导入失败：${it.message}") }
        }
    }

    fun reparseNow() {
        viewModelScope.launch {
            val result = runCatching { container.reparse.reparseRecentFailures() }
            result.onSuccess {
                eventChannel.trySend("回溯完成：尝试 ${it.attempted}，入账 ${it.posted}，待核对 ${it.pending}")
            }.onFailure {
                eventChannel.trySend("回溯失败：${it.message}")
            }
        }
    }

    fun generateReportNow() {
        viewModelScope.launch {
            val result = runCatching {
                val month = YearMonth.now(TimeUtils.zone()).minusMonths(1)
                container.reportGenerator.generate(month, notify = true)
            }
            result.onSuccess {
                eventChannel.trySend("上月月报已生成并尝试推送通知")
            }.onFailure {
                eventChannel.trySend("月报生成失败：${it.message}")
            }
        }
    }

    fun exportLogs(uri: Uri, module: String?) {
        viewModelScope.launch {
            val result = runCatching {
                val rows = container.logger.queryForExport(0L, System.currentTimeMillis(), module)
                withContext(Dispatchers.IO) {
                    container.app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write("time,level,module,message\n")
                        rows.forEach { row ->
                            writer.write(
                                listOf(
                                    TimeUtils.formatDateTime(row.ts),
                                    row.level,
                                    row.module,
                                    Sanitizer.sanitize(row.message, 500)
                                ).joinToString(",") { csvEscape(it) }
                            )
                            writer.newLine()
                        }
                    } ?: throw IllegalStateException("无法写入文件")
                }
                rows.size
            }
            result.onSuccess { eventChannel.trySend("已导出 $it 条脱敏日志") }
                .onFailure { eventChannel.trySend("导出失败：${it.message}") }
        }
    }

    fun loadBackups() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                BackupManager(container.app).listSnapshots().map {
                    BackupInfo(it.name, it.length(), it.lastModified())
                }
            }
            _backups.value = list
        }
    }

    fun loadRuleInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                RuleInfo(container.rules.currentVersion(), container.rules.ruleCount())
            }
            _ruleInfo.value = info
        }
    }

    private fun loadNoteState(txId: Long) {
        viewModelScope.launch {
            val tx = repo.transaction(txId) ?: return@launch
            _noteEditingId.value = txId
            _noteDraft.value = NoteDraftState(txId, tx.note.orEmpty(), tx.category)
            persistSnapshot()
        }
    }

    private fun saveNoteDraft(draft: NoteDraftState) {
        val json = JSONObject()
            .put("note", draft.note)
            .put("category", draft.category)
            .toString()
        draftManager.requestSave(sceneNote(draft.txId), json)
    }

    private fun saveBulkDraft() {
        val payload = JSONObject().put("ids", JSONArray(_bulkSelection.value.toList())).toString()
        draftManager.requestSave(SCENE_BULK, payload)
    }

    private suspend fun restoreDrafts(): Boolean {
        val drafts = runCatching { draftManager.loadUnconsumed() }.getOrDefault(emptyList())
        var restored = false
        for (draft in drafts) {
            when {
                draft.scene.startsWith("$SCENE_NOTE_PREFIX:") -> {
                    val txId = draft.scene.substringAfter(":").toLongOrNull() ?: continue
                    val json = runCatching { JSONObject(draft.payloadJson) }.getOrNull() ?: continue
                    _noteEditingId.value = txId
                    _noteDraft.value = NoteDraftState(
                        txId = txId,
                        note = json.optString("note"),
                        category = json.optString("category", "其他")
                    )
                    restored = true
                }
                draft.scene == SCENE_MANUAL -> {
                    runCatching { parseManual(draft.payloadJson) }.getOrNull()?.let { form ->
                        _manualForm.value = form
                        if (_screenStack.value.lastOrNull() != ROUTE_MANUAL) {
                            _screenStack.update { it + ROUTE_MANUAL }
                        }
                        restored = true
                    }
                }
                draft.scene == SCENE_BULK -> {
                    val json = runCatching { JSONObject(draft.payloadJson) }.getOrNull() ?: continue
                    val array = json.optJSONArray("ids") ?: JSONArray()
                    _bulkSelection.value = (0 until array.length()).mapNotNull {
                        val id = array.optLong(it, -1L)
                        id.takeIf { value -> value > 0 }
                    }.toSet()
                    _bulkMode.value = _bulkSelection.value.isNotEmpty()
                    restored = true
                }
            }
        }
        return restored
    }

    private fun applySnapshot(snapshot: UiSnapshot) {
        _tabIndex.value = snapshot.tabIndex
        _screenStack.value = snapshot.screenStack
        _filters.value = FiltersState(
            range = ReportRange.from(snapshot.range),
            category = snapshot.category,
            type = TypeFilter.from(snapshot.typeFilter)
        )
        _chartMode.value = ChartMode.from(snapshot.chartType)
        _scrollPositions.value = mapOf(
            "transactions" to snapshot.transactionScroll,
            "report" to snapshot.reportScroll,
            "trend" to snapshot.trendScroll
        )
        _bulkMode.value = snapshot.bulkMode
        _bulkSelection.value = snapshot.bulkSelection.toSet()
        _noteEditingId.value = snapshot.noteEditingTxId
    }

    private fun persistSnapshot() {
        if (!_ready.value) return
        val scroll = _scrollPositions.value
        snapshotManager.requestSave(
            UiSnapshot(
                tabIndex = _tabIndex.value,
                screenStack = _screenStack.value,
                range = _filters.value.range.name,
                category = _filters.value.category,
                typeFilter = _filters.value.type.name,
                chartType = _chartMode.value.name,
                transactionScroll = scroll["transactions"] ?: 0,
                reportScroll = scroll["report"] ?: 0,
                trendScroll = scroll["trend"] ?: 0,
                bulkMode = _bulkMode.value,
                bulkSelection = _bulkSelection.value.toList(),
                noteEditingTxId = _noteEditingId.value,
                savedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun buildReport(input: ReportInput): ReportUiState {
        val filter = input.filters
        val (from, to) = rangeBounds(filter.range)
        val anchor = anchorMonth(filter.range)
        val summary = runCatching { container.reportGenerator.liveSummary(anchor) }.getOrNull()
        val scoped = input.transactions.filter { tx ->
            tx.occurredAt in from until to &&
                (filter.category == null || tx.category == filter.category) &&
                (filter.type == TypeFilter.ALL || tx.type == filter.type.name)
        }
        val expenses = scoped.filter { it.type == TxType.EXPENSE.name }
        val incomes = scoped.filter { it.type == TxType.INCOME.name }
        val incomeCents = incomes.sumOf { it.amountCents }
        val expenseCents = expenses.sumOf { it.amountCents }
        val categories = expenses.groupBy { it.category }
            .map { (category, rows) ->
                val total = rows.sumOf { it.amountCents }
                CategoryTotal(category, total, if (expenseCents > 0) total.toDouble() / expenseCents else 0.0)
            }
            .sortedByDescending { it.amountCents }
        val topMerchants = expenses.groupBy { it.merchant }
            .map { (merchant, rows) -> MerchantTotal(merchant, rows.sumOf { it.amountCents }, rows.size) }
            .sortedByDescending { it.amountCents }
            .take(5)
        return ReportUiState(
            rangeLabel = filter.range.label,
            monthLabel = summary?.month ?: anchor.toString(),
            incomeCents = incomeCents,
            expenseCents = expenseCents,
            balanceCents = incomeCents - expenseCents,
            categories = categories,
            daily = buildDaily(scoped, from, to, filter.range),
            topMerchants = topMerchants,
            trend = summary?.monthlyTrend.orEmpty(),
            momRatio = summary?.momExpenseRatio,
            yoyRatio = summary?.yoyExpenseRatio,
            anomalies = summary?.anomalies.orEmpty(),
            pendingCount = input.pendingCount,
            captureCount = summary?.captureCount ?: 0,
            autoPostedCount = summary?.autoPostedCount ?: 0,
            health = input.health.map { HealthLine(it.label, it.healthy, it.lastBeatAt) }
        )
    }

    private fun buildDaily(
        rows: List<TransactionEntity>,
        from: Long,
        to: Long,
        range: ReportRange
    ): List<DailyPoint> {
        val grouped = rows.groupBy { TimeUtils.dayKey(it.occurredAt) }
        fun point(key: String, dayRows: List<TransactionEntity>): DailyPoint = DailyPoint(
            day = key,
            expenseCents = dayRows.filter { it.type == TxType.EXPENSE.name }.sumOf { it.amountCents },
            incomeCents = dayRows.filter { it.type == TxType.INCOME.name }.sumOf { it.amountCents }
        )
        if (range == ReportRange.THIS_YEAR || range == ReportRange.ALL) {
            return grouped.toSortedMap().map { (key, dayRows) -> point(key, dayRows) }
        }
        val start = Instant.ofEpochMilli(from).atZone(TimeUtils.zone()).toLocalDate()
        val end = Instant.ofEpochMilli(to).atZone(TimeUtils.zone()).toLocalDate()
        val days = ChronoUnit.DAYS.between(start, end).toInt().coerceIn(1, 120)
        return (0 until days).map { offset ->
            val date = start.plusDays(offset.toLong())
            val key = date.toString()
            point(key, grouped[key].orEmpty())
        }
    }

    private fun rangeBounds(range: ReportRange): Pair<Long, Long> {
        val now = YearMonth.now(TimeUtils.zone())
        return when (range) {
            ReportRange.THIS_MONTH -> TimeUtils.monthStart(now) to TimeUtils.monthEndExclusive(now)
            ReportRange.LAST_MONTH -> {
                val month = now.minusMonths(1)
                TimeUtils.monthStart(month) to TimeUtils.monthEndExclusive(month)
            }
            ReportRange.LAST_3_MONTHS ->
                TimeUtils.monthStart(now.minusMonths(2)) to TimeUtils.monthEndExclusive(now)
            ReportRange.THIS_YEAR -> {
                val start = LocalDate.of(now.year, 1, 1).atStartOfDay(TimeUtils.zone()).toInstant().toEpochMilli()
                val end = LocalDate.of(now.year + 1, 1, 1).atStartOfDay(TimeUtils.zone()).toInstant().toEpochMilli()
                start to end
            }
            ReportRange.ALL -> 0L to Long.MAX_VALUE
        }
    }

    private fun anchorMonth(range: ReportRange): YearMonth {
        val now = YearMonth.now(TimeUtils.zone())
        return if (range == ReportRange.LAST_MONTH) now.minusMonths(1) else now
    }

    private fun parseManual(json: String): ManualFormState {
        val root = JSONObject(json)
        return ManualFormState(
            amount = root.optString("amount"),
            merchant = root.optString("merchant"),
            category = root.optString("category", "其他"),
            type = TxType.fromName(root.optString("type")),
            date = root.optString("date", TimeUtils.formatDate(System.currentTimeMillis())),
            note = root.optString("note")
        )
    }

    private fun encodeManual(form: ManualFormState): String = JSONObject()
        .put("amount", form.amount)
        .put("merchant", form.merchant)
        .put("category", form.category)
        .put("type", form.type.name)
        .put("date", form.date)
        .put("note", form.note)
        .toString()

    private fun updateSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { eventChannel.trySend("设置保存失败：${it.message}") }
        }
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun sceneNote(txId: Long) = "$SCENE_NOTE_PREFIX:$txId"

    private data class ReportInput(
        val transactions: List<TransactionEntity>,
        val pendingCount: Int,
        val health: List<com.tsyz.autoledger.core.model.ChannelStatus>,
        val filters: FiltersState
    )

    companion object {
        const val ROUTE_MANUAL = "manual"
        const val ROUTE_BACKUP = "backup"
        const val ROUTE_RULES = "rules"
        const val ROUTE_LOGS = "logs"
        const val ROUTE_IMPORT = "import"
        const val ROUTE_PENDING = "pending"
        const val ONBOARDING = "onboarding"
        const val SCENE_NOTE_PREFIX = "note"
        const val SCENE_MANUAL = "manual_add"
        const val SCENE_BULK = "bulk_select"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(container) as T
            }
    }
}
