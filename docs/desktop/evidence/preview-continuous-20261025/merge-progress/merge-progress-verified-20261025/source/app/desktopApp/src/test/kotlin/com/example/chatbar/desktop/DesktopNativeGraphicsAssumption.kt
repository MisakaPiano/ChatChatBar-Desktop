package com.example.chatbar.desktop

/** Offscreen scenes still run headlessly; only real OS window/input cases use this prerequisite. */
internal fun requireDesktopGraphicsForNativeTest() {
    org.junit.Assume.assumeFalse("Native graphical desktop required; headless is not native acceptance",
        java.awt.GraphicsEnvironment.isHeadless())
}
