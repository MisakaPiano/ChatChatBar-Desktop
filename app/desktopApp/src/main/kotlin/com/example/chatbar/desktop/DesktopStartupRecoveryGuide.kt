package com.example.chatbar.desktop

/** Fixed pre-container guidance. No Compose, preferences, files, exception text or repair actions. */
internal enum class DesktopStartupRecoveryGuide(val explanation: String, val nextStep: String) {
    INDEX_DOMAIN(
        "发现当前启动规则不支持直接接管的索引事务目录。\nAn index transaction directory cannot be admitted by the current startup rules.",
        "保留索引目录与原资料，复制诊断代码供维护者核对；此窗口不删除或重建索引。\nRetain the index directory and original data. Copy the diagnostic code for review; this window does not delete or rebuild indexes."),
    STARTUP_CHECK(
        "启动检查或恢复准备没有通过；这不等于已确认资料损坏。\nStartup checks or recovery preparation did not pass. This does not establish damaged data.",
        "保留原目录与恢复记录，复制诊断代码后正常关闭。再次启动仍由原检查流程处理；若再次失败，请提供代码。\nRetain the directory and recovery records, copy the diagnostic code and close normally. The original checks still apply on the next start; report the code if it fails again."),
    ROOT_SELECTION(
        "程序或资料位置的选择没有通过启动核对。\nThe selected application or data location did not pass startup checks.",
        "核对原数据盘与安装位置是否可用，保留目录选择记录并复制诊断代码。可正常启动后，再从数据目录页更换位置。\nCheck availability of the original drive and installation. Retain location-selection records and copy the code. Change locations through Data directory once normal startup is available."),
    PORTABLE(
        "Portable 目录启用或探针核对没有完成。\nPortable location activation or probe verification did not complete.",
        "保留原 Portable 目录、资料和可能残留的探针。核对可用空间与写入权限后，可从原入口重试；仍失败时提供诊断代码。\nRetain the Portable location, data and any remaining probe. Check free space and write permissions before retrying the original entry; report the code if it fails again."),
    OWNERSHIP(
        "未取得资料目录的独占使用权；尚未准入普通业务。\nExclusive ownership of the data directory was not obtained; normal business was not admitted.",
        "核对原盘与目录是否可用，以及其他窗口、权限或占用；先正常关闭仍使用该资料的程序。保留锁文件和原目录，重试仍失败时提供诊断代码。\nCheck the original drive and directory, other windows, permissions or file use. Close any app using the data normally. Retain lock files and the original directory; report the code if retrying fails."),
    CANCELLED(
        "启动准备被取消。\nStartup preparation was cancelled.",
        "保留原文件，关闭此窗口；需要时从原安装入口重新启动。此处不会代选其他资料目录。\nRetain original files and close this window. Start again from the original installation when needed; this window does not select another data directory."),
    EXTERNAL(
        "外部文件交接没有通过准入；未提交导入。\nExternal-file activation was not admitted; import was not submitted.",
        "保留原文件。先从原安装入口打开程序，再用对应导入入口手动选择文件；若仍失败，复制诊断代码核对。\nRetain the original file. Open the original installation and select the file through the matching import entry; copy the code for review if it still fails."),
}
