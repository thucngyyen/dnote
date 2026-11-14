package dev.dnote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import dev.dnote.app.ui.DNoteTheme
import dev.dnote.app.ui.FolderScreen
import dev.dnote.app.ui.HomeScreen
import dev.dnote.app.ui.ImageScreen
import dev.dnote.app.ui.NoteScreen
import dev.dnote.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val model: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DNoteTheme(model.theme) { DNoteApp(model) } }
    }

    override fun onStart() {
        super.onStart()
        model.appBecameActive()
    }

    override fun onStop() {
        model.flushSave()
        super.onStop()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalRouteScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

private data class Screen(val route: Route, val depth: Int)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DNoteApp(model: NotesViewModel) {
    BackHandler(enabled = model.stack.size > 1) { model.pop() }
    SharedTransitionLayout {
        AnimatedContent(
            targetState = Screen(model.stack.last(), model.stack.size),
            transitionSpec = {
                val push = targetState.depth > initialState.depth
                val morph = targetState.route is Route.Note || initialState.route is Route.Note ||
                    targetState.route is Route.Image || initialState.route is Route.Image
                when {
                    // Note/image screens morph out of the tapped row (shared bounds); just cross-fade the rest.
                    morph -> fadeIn(tween(260, delayMillis = 40)) togetherWith fadeOut(tween(200))
                    push -> (slideInHorizontally(tween(320)) { it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(200)))
                    else -> (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(200)))
                }.using(SizeTransform(clip = false))
            },
            label = "route",
        ) { screen ->
            CompositionLocalProvider(
                LocalSharedScope provides this@SharedTransitionLayout,
                LocalRouteScope provides this,
            ) {
                when (val route = screen.route) {
                    is Route.Folder -> if (route.path.isEmpty()) HomeScreen(model) else FolderScreen(model, route.path)
                    is Route.Note -> NoteScreen(model, route.path)
                    is Route.Image -> ImageScreen(model, route.path)
                    Route.Settings -> SettingsScreen(model)
                }
            }
        }
    }
    model.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { model.message = null },
            confirmButton = { TextButton(onClick = { model.message = null }) { Text("OK") } },
            title = { Text("DNote") },
            text = { Text(msg) },
        )
    }
}
