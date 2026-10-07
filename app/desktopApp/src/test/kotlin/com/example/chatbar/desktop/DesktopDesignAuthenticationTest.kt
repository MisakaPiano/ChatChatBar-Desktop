package com.example.chatbar.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class DesktopDesignAuthenticationTest {
    @Test fun secureRepositoryHydrationGatesTheRealControllerBeforeTransport() = runBlocking {
        val f = FinalProductDesignFixture()
        try {
            f.initialize()
            val controller = f.container.novelAiStudioController
            val ready = controller.designAuthentication()
            assertTrue(ready.configured)
            assertEquals("fixture.invalid", ready.provider)
            assertFalse(ready.toString().contains("fake-only-key"))
            val model = requireNotNull(f.container.modelRepository.getModel("local-design"))
            assertEquals("fake-only-key", model.apiKey)
            f.container.modelRepository.saveModel(model.copy(apiKey = ""))
            assertFalse(controller.designAuthentication().configured)
            assertFalse(controller.design(newConversation = true))
            assertTrue(f.requests.isEmpty())
            controller.edit { it.copy(aiDesignModelId = "removed-id") }
            assertFalse(controller.designAuthentication().configured)
            assertFalse(controller.design())
            assertTrue(f.requests.isEmpty())
        } finally { f.close() }
    }
}
