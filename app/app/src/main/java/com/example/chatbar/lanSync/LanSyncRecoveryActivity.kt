package com.example.chatbar.lanSync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.*

/** No business repositories, user-data reads or automatic cleanup are admitted in this process. */
class LanSyncRecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChatBarTheme { RecoveryNotice {
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        } } }
    }
}

@Composable
private fun RecoveryNotice(close: () -> Unit) {
    Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CbText("同步恢复尚未完成")
        CbText("为避免读取一半写入的会话，本次没有打开普通资料。原资料与恢复记录均保留，请不要清除应用数据或卸载。")
        CbText("可先检查手机空间后退出并重新打开。若仍出现此页，请提供诊断代码 CCB_LAN_STARTUP_RECOVERY_REFUSED；无需发送聊天、密钥或备份原文。")
        CbButton("退出应用，稍后重试", close)
    }
}
