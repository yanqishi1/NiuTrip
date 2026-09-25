package com.niutrip.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TokenStoreTest {
    private lateinit var context: Application

    @Before fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("niutrip_auth", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `avatar snapshot persists and clear removes the whole session`() {
        val store = TokenStore(context)
        assertFalse(store.hasAvatarSnapshot)

        store.save("token", "user-1")
        store.saveAvatarUrl("/media/avatar.jpg")

        val restored = TokenStore(context)
        assertTrue(restored.hasAvatarSnapshot)
        assertEquals("user-1", restored.userId)
        assertEquals("/media/avatar.jpg", restored.avatarUrl.value)

        restored.clear()
        assertNull(restored.token)
        assertNull(restored.userId)
        assertNull(restored.avatarUrl.value)
        assertFalse(restored.hasAvatarSnapshot)
    }

    @Test fun `missing avatar is still a completed snapshot`() {
        val store = TokenStore(context)
        store.saveAvatarUrl(null)

        assertTrue(store.hasAvatarSnapshot)
        assertNull(store.avatarUrl.value)
    }
}
