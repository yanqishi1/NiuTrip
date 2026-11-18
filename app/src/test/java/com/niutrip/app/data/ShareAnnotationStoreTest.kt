package com.niutrip.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ShareAnnotationStoreTest {
    @Test fun `flags default false survive reopen and remain isolated across accounts and tracks`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("share_annotations", 0).edit().clear().commit()
        var owner: String? = "account-a"
        val store = ShareAnnotationStore(context) { owner }
        assertTrue(store.selected("track").isEmpty())
        store.set("track", "point", true)
        assertEquals(setOf("point"), ShareAnnotationStore(context) { owner }.selected("track"))
        assertTrue(store.selected("other-track").isEmpty())
        owner = "account-b"
        assertTrue(store.selected("track").isEmpty())
        store.set("track", "other-point", true)
        owner = "account-a"
        assertEquals(setOf("point"), store.selected("track"))
        store.set("track", "point", false)
        assertTrue(store.selected("track").isEmpty())
        owner = null
        store.set("track", "point", true)
        assertTrue(store.selected("track").isEmpty())
    }
}
