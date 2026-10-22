package com.tsyz.autoledger.snapshot

data class UiSnapshot(
    val tabIndex: Int = 0,
    val screenStack: List<String> = emptyList(),
    val range: String = "THIS_MONTH",
    val category: String? = null,
    val typeFilter: String = "ALL",
    val chartType: String = "BAR",
    val transactionScroll: Int = 0,
    val reportScroll: Int = 0,
    val trendScroll: Int = 0,
    val bulkMode: Boolean = false,
    val bulkSelection: List<Long> = emptyList(),
    val noteEditingTxId: Long? = null,
    val savedAt: Long = 0L
)
