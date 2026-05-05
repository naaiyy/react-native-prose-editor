package com.apollohg.editor

import android.content.Context
import android.graphics.Rect
import expo.modules.core.ModuleRegistry
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.ModulesProvider
import expo.modules.kotlin.modules.Module
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeEditorExpoViewTest {
    @Test
    fun `standalone toolbar hit testing normalizes screen coordinates to window coordinates`() {
        val expoContext = testExpoContext(RuntimeEnvironment.getApplication())
        val view = NativeEditorExpoView(expoContext.context, expoContext.appContext)
        val density = expoContext.context.resources.displayMetrics.density
        val visibleWindowFrame = Rect(6, 24, 600, 900)

        view.setToolbarFrameJson("""{"x":20,"y":40,"width":100,"height":32}""")

        assertTrue(
            view.isPointInsideStandaloneToolbarForTesting(
                rawX = 30f * density + visibleWindowFrame.left,
                rawY = 50f * density + visibleWindowFrame.top,
                visibleWindowFrame = visibleWindowFrame
            )
        )
        assertTrue(
            view.isPointInsideStandaloneToolbarForTesting(
                rawX = 30f * density,
                rawY = 50f * density,
                visibleWindowFrame = visibleWindowFrame
            )
        )
        assertFalse(
            view.isPointInsideStandaloneToolbarForTesting(
                rawX = 30f * density + visibleWindowFrame.left,
                rawY = 90f * density + visibleWindowFrame.top,
                visibleWindowFrame = visibleWindowFrame
            )
        )
    }

    @Test
    fun `toolbar focus preservation is inactive until a toolbar touch is recorded`() {
        val expoContext = testExpoContext(RuntimeEnvironment.getApplication())
        val view = NativeEditorExpoView(expoContext.context, expoContext.appContext)

        assertFalse(view.shouldPreserveFocusAfterToolbarTouchForTesting())

        view.markRecentToolbarTouchForTesting()
        assertTrue(view.shouldPreserveFocusAfterToolbarTouchForTesting())

        view.blur()
        assertFalse(view.shouldPreserveFocusAfterToolbarTouchForTesting())
    }

    private data class TestExpoContext(
        val context: Context,
        val appContext: AppContext
    )

    private fun testExpoContext(context: Context): TestExpoContext {
        val reactContext = Class
            .forName("com.facebook.react.bridge.BridgeReactContext")
            .getConstructor(Context::class.java)
            .newInstance(context) as Context

        val modulesProvider = object : ModulesProvider {
            override fun getModulesList(): List<Class<out Module>> = emptyList()
        }
        val constructor = AppContext::class.java.constructors.first { constructor ->
            constructor.parameterTypes.size == 3
        }
        val appContext = constructor.newInstance(
            modulesProvider,
            ModuleRegistry(emptyList(), emptyList()),
            WeakReference(reactContext)
        ) as AppContext
        return TestExpoContext(reactContext, appContext)
    }
}
