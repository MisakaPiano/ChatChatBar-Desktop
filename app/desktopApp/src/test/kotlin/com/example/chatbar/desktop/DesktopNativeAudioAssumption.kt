package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import org.junit.Assume.assumeTrue

internal interface NativeTestAudioInventory : StdCallLibrary {
    fun waveOutGetNumDevs(): Int
}

/** Device absence is NOT_TESTED; a present device still executes every native assertion. */
internal fun requireWindowsAudioOutputForNativeTest() {
    assumeTrue("Windows audio device required: NOT_TESTED", System.getProperty("os.name").startsWith("Windows"))
    val count = Native.load("winmm", NativeTestAudioInventory::class.java).waveOutGetNumDevs()
    assumeTrue("Native wave output devices=$count: NOT_TESTED", count > 0)
}
