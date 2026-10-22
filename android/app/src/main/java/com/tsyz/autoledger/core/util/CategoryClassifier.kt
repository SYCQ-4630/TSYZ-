package com.tsyz.autoledger.core.util

object CategoryClassifier {
    val categories = listOf("餐饮", "交通", "购物", "生活缴费", "转账", "工资", "医疗", "娱乐", "住房", "其他")

    private val keywordMap: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("美团", "饿了么", "餐厅", "饭店", "咖啡", "星巴克", "肯德基", "麦当劳", "奶茶", "食堂", "火锅", "外卖"),
        "交通" to listOf("滴滴", "地铁", "公交", "加油", "中石化", "中石油", "铁路", "12306", "打车", "停车", "高速", "航空", "机票"),
        "购物" to listOf("淘宝", "天猫", "京东", "拼多多", "超市", "便利店", "商场", "百货", "购物", "唯品会", "抖音商城"),
        "生活缴费" to listOf("水费", "电费", "燃气", "话费", "宽带", "物业", "缴费", "充电", "供暖"),
        "转账" to listOf("转账", "红包", "收款", "付款给"),
        "工资" to listOf("工资", "薪资", "奖金", "报销", "劳务", "薪酬"),
        "医疗" to listOf("医院", "药房", "药店", "诊所", "医疗", "体检", "挂号"),
        "娱乐" to listOf("电影", "游戏", "视频", "会员", "音乐", "演出", "门票", "KTV"),
        "住房" to listOf("房租", "租金", "公寓", "中介", "装修", "家具")
    )

    fun classify(merchant: String, text: String, fallback: String = "其他"): String {
        val haystack = "$merchant $text"
        for ((category, keywords) in keywordMap) {
            if (keywords.any { haystack.contains(it, ignoreCase = true) }) return category
        }
        return fallback.takeIf { it in categories } ?: "其他"
    }
}
