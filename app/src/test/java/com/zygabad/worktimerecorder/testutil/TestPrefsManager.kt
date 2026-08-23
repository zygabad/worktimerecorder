package com.zygabad.worktimerecorder.testutil

import android.content.Context
import com.zygabad.worktimerecorder.data.PrefsManager
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Builds a real (not mocked) PrefsManager so its actual getter/setter code runs in tests,
 * backed by a fake SharedPreferences. Only android.content.Context is mocked here — it's an
 * abstract class, so a plain Mockito mock works without needing final-class support.
 */
fun testPrefsManager(): PrefsManager {
    val backing = FakeSharedPreferences()
    val context = mock(Context::class.java)
    `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(backing)
    return PrefsManager(context)
}
