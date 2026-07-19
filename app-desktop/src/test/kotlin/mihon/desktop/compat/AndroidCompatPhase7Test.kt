package mihon.desktop.compat

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 7: AndroidCompat entry point and initialization.
 */
class AndroidCompatPhase7Test {

    @Test
    fun `AndroidCompat context is non-null`() {
        AndroidCompat.context.shouldNotBeNull()
    }

    @Test
    fun `AndroidCompat context is a Context`() {
        AndroidCompat.context.shouldBeInstanceOf<Context>()
    }

    @Test
    fun `AndroidCompat context provides SharedPreferences`() {
        val prefs = AndroidCompat.context.getSharedPreferences("compat_test", 0)
        prefs.shouldNotBeNull()
        prefs.shouldBeInstanceOf<SharedPreferences>()
    }

    @Test
    fun `AndroidCompat initialize does not throw`() {
        AndroidCompat.initialize()
    }

    @Test
    fun `AndroidCompat startApp with custom Application`() {
        val app = object : Application() {
            var onCreateCalled = false
            override fun onCreate() {
                super.onCreate()
                onCreateCalled = true
            }
        }
        AndroidCompat.startApp(app)
        app.onCreateCalled shouldBe true
        app.getBaseContext() shouldBe AndroidCompat.context
    }

    @Test
    fun `AndroidCompat context getApplicationContext returns context`() {
        AndroidCompat.context.getApplicationContext().shouldNotBeNull()
    }

    @Test
    fun `AndroidCompat context getPackageName returns mihon desktop`() {
        AndroidCompat.context.getPackageName() shouldBe "mihon.desktop"
    }
}
