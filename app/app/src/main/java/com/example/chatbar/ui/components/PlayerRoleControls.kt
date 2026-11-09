package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.SessionPlayerRole
import com.example.chatbar.ui.kit.*

@Composable
internal fun PlayerRoleControls(card: CharacterCard?, role: SessionPlayerRole?, onChange: (SessionPlayerRole?) -> Unit) {
    if (card == null) return
    val people = card.characters.filter { person -> card.characters.count { it.id == person.id } == 1 }
    val valid = role?.takeIf { it.cardId == card.id && people.any { person -> person.id == it.personId } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CbField("我扮演的角色", description = "手动关联卡内人物；同名不会自动绑定，也不会改变人物开关。") {
            CbSelect(valid?.personId, listOf<String?>(null) + people.map { it.id },
                { id -> people.firstOrNull { it.id == id }?.name ?: "不关联角色" },
                { id -> onChange(id?.let { SessionPlayerRole(card.id, it) }) })
        }
        if (role != null) {
            if (valid == null) CbText("原关联角色已不可用，请重新选择。", color = ChatBarTheme.colors.warning)
            CbField("姓名跟随角色") { SettingsSwitch(role.followName,
                { onChange(role.copy(followName = it)) }, label = "姓名跟随角色") }
            CbField("头像跟随角色") { SettingsSwitch(role.followAvatar,
                { onChange(role.copy(followAvatar = it)) }, label = "头像跟随角色") }
            CbButton("解除角色关联", { onChange(null) }, modifier = Modifier.heightIn(min = 48.dp), variant = ButtonVariant.Ghost)
            CbText("关闭跟随后恢复会话覆盖或全局默认，不改写原玩家设置。", style = ChatBarTheme.typography.caption)
        }
    }
}
