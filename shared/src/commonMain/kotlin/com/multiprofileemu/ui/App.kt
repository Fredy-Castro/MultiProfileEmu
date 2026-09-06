package com.multiprofileemu.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.multiprofileemu.app.AppController
import com.multiprofileemu.input.GamepadAction
import com.multiprofileemu.input.GamepadController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge

private sealed interface Route {
    data object GroupList : Route
    data class GroupDetail(val groupId: String) : Route
}

/**
 * Root composable, shared verbatim between desktop and (future) Android targets.
 * Merges keyboard input (arrow keys/enter/escape, as a stand-in when no physical
 * gamepad is connected) with [gamepad]'s events into a single action stream that
 * each screen interprets for list navigation and shortcuts.
 */
@Composable
fun App(controller: AppController, gamepad: GamepadController) {
    MaterialTheme {
        Surface {
            var route by remember { mutableStateOf<Route>(Route.GroupList) }
            val keyActions = remember { MutableSharedFlow<GamepadAction>(extraBufferCapacity = 8) }
            val actions = remember { merge(gamepad.actions, keyActions) }
            val focusRequester = remember { FocusRequester() }

            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val action = when (event.key) {
                            Key.DirectionUp -> GamepadAction.NAV_UP
                            Key.DirectionDown -> GamepadAction.NAV_DOWN
                            Key.DirectionLeft -> GamepadAction.NAV_LEFT
                            Key.DirectionRight -> GamepadAction.NAV_RIGHT
                            Key.Enter, Key.NumPadEnter -> GamepadAction.CONFIRM
                            Key.Escape -> GamepadAction.BACK
                            Key.PageUp -> GamepadAction.PAGE_UP
                            Key.PageDown -> GamepadAction.PAGE_DOWN
                            else -> null
                        }
                        if (action != null) {
                            keyActions.tryEmit(action)
                            true
                        } else {
                            false
                        }
                    },
            ) {
                when (val current = route) {
                    is Route.GroupList -> GroupListScreen(
                        controller = controller,
                        actions = actions,
                        onOpenGroup = { route = Route.GroupDetail(it) },
                    )
                    is Route.GroupDetail -> {
                        val groups by controller.groups.collectAsState()
                        val group = groups.firstOrNull { it.id == current.groupId }
                        if (group == null) {
                            route = Route.GroupList
                        } else {
                            GroupDetailScreen(
                                controller = controller,
                                group = group,
                                actions = actions,
                                onBack = { route = Route.GroupList },
                            )
                        }
                    }
                }
            }
        }
    }
}
