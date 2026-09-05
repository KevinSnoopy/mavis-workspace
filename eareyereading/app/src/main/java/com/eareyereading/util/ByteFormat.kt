package com.eareyereading.util

/**
 * 人类可读字节数：B → KB → MB。
 *
 * 此前 Reader/Settings/Dictionary 三个页面各自维护一份实现（DRY 违规），
 * 收敛为单一出口；两档显示口径以参数显式区分，避免统一时改变既有展示：
 *
 * @param kbDecimals KB 段小数位。模型/备份等大文件口径用 1（"488.3 KB"）；
 *   词典等小文件口径用 0（"488 KB"）
 * @param mbDecimals MB 段小数位。大文件口径取整（"63 MB"），
 *   小文件口径保留一位（"1.5 MB"）
 */
fun formatBytes(bytes: Long, kbDecimals: Int = 1, mbDecimals: Int = 0): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.${kbDecimals}f KB".format(kb)
    return "%.${mbDecimals}f MB".format(kb / 1024.0)
}
