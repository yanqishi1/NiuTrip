package com.niutrip.app.ui.deeplink

import com.niutrip.app.data.remote.ShareInspectOut
import com.niutrip.app.data.remote.StubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingShareViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `own share link is ignored`() = runTest {
        val api = object : StubApi() {
            override suspend fun inspectShare(token: String) = ShareInspectOut(is_owner = true)
        }
        val viewModel = IncomingShareViewModel("mine", api)
        assertEquals(IncomingShareDecision.IGNORE, viewModel.decision.value)
    }

    @Test fun `another users share link prompts`() = runTest {
        val api = object : StubApi() {
            override suspend fun inspectShare(token: String) = ShareInspectOut(is_owner = false)
        }
        val viewModel = IncomingShareViewModel("theirs", api)
        assertEquals(IncomingShareDecision.PROMPT, viewModel.decision.value)
    }

    @Test fun `already saved clipboard share is ignored`() = runTest {
        val api = object : StubApi() {
            override suspend fun inspectShare(token: String) = ShareInspectOut(is_owner = false, is_saved = true)
        }
        val viewModel = IncomingShareViewModel("saved", api, ignoreAlreadySaved = true)
        assertEquals(IncomingShareDecision.IGNORE, viewModel.decision.value)
    }

    @Test fun `already saved explicit link can still be opened`() = runTest {
        val api = object : StubApi() {
            override suspend fun inspectShare(token: String) = ShareInspectOut(is_owner = false, is_saved = true)
        }
        val viewModel = IncomingShareViewModel("saved", api, ignoreAlreadySaved = false)
        assertEquals(IncomingShareDecision.PROMPT, viewModel.decision.value)
    }

    @Test fun `invalid or unreachable share link does not prompt`() = runTest {
        val api = object : StubApi() {
            override suspend fun inspectShare(token: String): ShareInspectOut = error("offline")
        }
        val viewModel = IncomingShareViewModel("bad", api)
        assertEquals(IncomingShareDecision.IGNORE, viewModel.decision.value)
    }
}
