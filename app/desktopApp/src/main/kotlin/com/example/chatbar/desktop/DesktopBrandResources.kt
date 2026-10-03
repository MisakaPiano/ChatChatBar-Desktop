package com.example.chatbar.desktop

/** Exact formal upstream 1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8:
 * app/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png. The ICO embeds a bicubic 256px derivative.
 */
internal object DesktopBrandResources {
    const val LOGO_RESOURCE = "brand/ccb.png"
    fun logoBytes(): ByteArray = checkNotNull(javaClass.classLoader.getResourceAsStream(LOGO_RESOURCE)) {
        "Official CCB Desktop brand resource is missing"
    }.use { it.readBytes() }
}
