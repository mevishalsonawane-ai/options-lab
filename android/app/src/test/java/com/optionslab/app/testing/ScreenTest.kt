package com.optionslab.app.testing

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.robolectric.RuntimeEnvironment

/**
 * One device set-up for screen tests: size and orientation (Robolectric qualifiers), font scale, theme.
 * [name] is used in test names and screenshot file names.
 */
data class DeviceConfig(val size: String, val qualifiers: String, val fontScale: Float, val dark: Boolean) {
    val name: String get() = "$size-font${"%.1f".format(java.util.Locale.ROOT, fontScale)}-${if (dark) "dark" else "light"}"
    override fun toString() = name

    companion object {
        val SIZES = listOf(
            "small" to "w360dp-h640dp-port",
            "phone" to "w411dp-h891dp-port",
            "landscape" to "w891dp-h411dp-land",
            "tablet" to "w800dp-h1280dp-port",
        )
        val FONTS = listOf(1.0f, 1.3f, 2.0f)

        /** Every size x font scale x theme: 24 set-ups. */
        fun matrix(): List<Array<Any>> = SIZES.flatMap { (n, q) ->
            FONTS.flatMap { f -> listOf(false, true).map { d -> arrayOf<Any>(DeviceConfig(n, "$q-${if (d) "night" else "notnight"}-xhdpi", f, d)) } }
        }
    }
}

/**
 * Base for parameterized screen tests (see src/test/README.md). Subclasses run with
 * `@RunWith(ParameterizedRobolectricTestRunner::class)` over [DeviceConfig.matrix] and
 * `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, and call [checkScreen] for each screen state:
 * it renders the content in that device set-up, captures a Roborazzi screenshot, runs
 * [LayoutLint] and [smokeEveryAction], and fails on any layout error that is not a listed known bug.
 */
abstract class ScreenTest(protected val device: DeviceConfig) {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @Before fun configureDevice() {
        RuntimeEnvironment.setQualifiers(device.qualifiers)
        RuntimeEnvironment.setFontScale(device.fontScale)
    }

    @After fun closeActivity() { scenario?.close() }

    /** Render [content] in the app theme for this device set-up (once per test). */
    protected fun show(content: @Composable () -> Unit) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity {
            it.setContent { IraAlgoTheme(if (device.dark) "dark" else "light") { content() } }
        }
        compose.waitForIdle()
    }

    /**
     * Render, screenshot, lint. [knownBugs]: device name (or "*" for all) -> description of a real
     * layout bug already reported; the test is then skipped (assumption) instead of failing, with
     * the description, so CI stays green until it is fixed.
     */
    protected fun checkScreen(
        name: String,
        knownBugs: Map<String, String> = emptyMap(),
        options: LayoutLint.Options = LayoutLint.Options(),
        content: @Composable () -> Unit,
    ) {
        show(content)
        capture(name)
        lint(name, knownBugs, options)
    }

    /**
     * A screenshot of every window on screen: build/outputs/roborazzi/<name>_<device>.png for the first, then
     * <name>-layer<i>_<device>.png for each window above it (a dialog, a popup). Windows with no size (a popup
     * not shown) are left out: there is nothing to draw.
     */
    protected fun capture(name: String) {
        val roots = compose.onAllNodes(isRoot())
        val sized = roots.fetchSemanticsNodes().withIndex().filter { (_, n) -> n.size.width > 0 && n.size.height > 0 }.map { it.index }
        if (sized.isEmpty()) { compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${device.name}.png"); return }
        sized.forEachIndexed { layer, i ->
            roots[i].captureRoboImage("build/outputs/roborazzi/${name}${if (layer == 0) "" else "-layer$layer"}_${device.name}.png")
        }
    }

    /** [LayoutLint] over every root (dialogs are roots of their own); errors fail, warnings print. */
    protected fun lint(name: String, knownBugs: Map<String, String> = emptyMap(), options: LayoutLint.Options = LayoutLint.Options()) {
        compose.waitForIdle()
        val findings = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { LayoutLint.check(it, compose.density, options) }
        findings.forEach { println("LAYOUT $name ${device.name}: $it") }
        val errors = findings.filter { it.level == LayoutLint.Level.ERROR }
        if (errors.isEmpty()) return
        val known = knownBugs[device.name] ?: knownBugs[device.size] ?: knownBugs["*"]
        if (known != null) assumeTrue("LAYOUT BUG ($name, ${device.name}): $known\n${errors.joinToString("\n")}", false)
        fail("Layout problems on $name (${device.name}):\n" + errors.joinToString("\n"))
    }

    /**
     * Functional smoke: every enabled clickable on screen is clicked once (through its semantics
     * action, as TalkBack would) and must not crash; returns the labels clicked. Real behaviour -
     * what each click must change - is asserted in the screen's own functional tests.
     */
    protected fun smokeEveryAction(skip: Set<String> = emptySet()): List<String> {
        val done = ArrayList<String>()
        val seen = HashSet<String>()
        while (true) {
            val node = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().asSequence()
                .flatMap { r -> generateSequence(listOf(r)) { l -> l.flatMap { it.children }.ifEmpty { null } }.flatten() }
                .firstOrNull { n ->
                    val click = n.config.getOrNull(SemanticsActions.OnClick) ?: return@firstOrNull false
                    val label = labelOf(n)
                    click.action != null && n.config.getOrNull(SemanticsProperties.Disabled) == null && label !in skip && seen.add("${n.id}:$label")
                } ?: break
            done += labelOf(node)
            compose.runOnUiThread { node.config[SemanticsActions.OnClick].action?.invoke() }
            compose.waitForIdle()
            if (done.size > 200) break
        }
        return done
    }

    private fun labelOf(n: androidx.compose.ui.semantics.SemanticsNode): String =
        (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: n.children.firstNotNullOfOrNull { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } }
            ?: "node ${n.id}")
}
