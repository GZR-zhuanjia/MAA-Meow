package com.aliothmoon.maameow.presentation.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.manager.MiuiAdbInputEnabler
import com.aliothmoon.maameow.presentation.view.panel.PanelDialogFixAction
import com.aliothmoon.maameow.presentation.view.panel.PanelDialogType
import com.aliothmoon.maameow.presentation.view.panel.PanelDialogUiState
import com.aliothmoon.maameow.utils.i18n.asString
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 面板弹窗统一渲染；带修复动作时按「修复 > 取消 > 仍然启动」排，仍然启动只是文字按钮
 * 点外面始终按取消处理；返回键只有独立窗口的 Dialog 会响应，悬浮窗里不处理
 * [onConfirm] 的参数仅在勾选「不再提醒」并选择「仍然启动」时为 true
 */
@Composable
fun PanelPromptDialog(
    dialog: PanelDialogUiState?,
    onDismiss: () -> Unit,
    onConfirm: (dontRemindAgain: Boolean) -> Unit,
) {
    val onFix = rememberFixAction(dialog, { onConfirm(false) }, onDismiss)
    if (dialog == null) return

    var dontRemindAgain by remember(dialog) { mutableStateOf(false) }
    val confirm = { onConfirm(dialog.showDontRemindAgain && dontRemindAgain) }

    val tint = when (dialog.type) {
        PanelDialogType.SUCCESS -> MaterialTheme.colorScheme.primary
        PanelDialogType.WARNING -> MaterialTheme.colorScheme.tertiary
        PanelDialogType.ERROR -> MaterialTheme.colorScheme.error
    }
    val confirmText = dialog.confirmText.asString().ifBlank { stringResource(R.string.common_confirm) }
    val fix = dialog.fixAction
    AdaptiveTaskPromptDialog(
        visible = true,
        title = dialog.title.asString(),
        onDismissRequest = onDismiss,
        icon = if (dialog.type == PanelDialogType.SUCCESS) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        iconTint = tint,
        confirmColor = if (fix != null) MaterialTheme.colorScheme.primary else tint,
        confirmText = if (fix != null) stringResource(fix.labelRes) else confirmText,
        onConfirm = if (fix != null) onFix else confirm,
        neutralText = if (fix != null) stringResource(R.string.common_cancel) else null,
        onNeutralClick = onDismiss,
        dismissText = if (fix != null) {
            confirmText
        } else {
            dialog.dismissText.asString().ifBlank { stringResource(R.string.common_close) }
        },
        onDismissClick = if (fix != null) confirm else null,
        content = {
            Text(
                text = AnnotatedString(dialog.message.asString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dialog.showDontRemindAgain) {
                CheckBoxWithLabel(
                    checked = dontRemindAgain,
                    onCheckedChange = { dontRemindAgain = it },
                    label = stringResource(R.string.task_start_warning_dont_remind_again),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

/** Root 开启：执行中连点只算一次，跑完时弹窗已换成别的就不再替它确认；跳设置：跳完关弹窗 */
@Composable
private fun rememberFixAction(
    dialog: PanelDialogUiState?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentDialog by rememberUpdatedState(dialog)
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val running = remember { AtomicBoolean(false) }
    return remember(scope, context) {
        fix@{
            val origin = currentDialog ?: return@fix
            val action = origin.fixAction ?: return@fix
            action.settingsAction?.let {
                openSettings(context, it)
                currentOnDismiss()
                return@fix
            }
            if (!running.compareAndSet(false, true)) return@fix
            scope.launch {
                try {
                    when (action) {
                        PanelDialogFixAction.ENABLE_ADB_INPUT_WITH_ROOT -> if (!MiuiAdbInputEnabler.enable()) {
                            Toast.makeText(context, R.string.task_start_enable_adb_input_failed, Toast.LENGTH_LONG)
                                .show()
                        } else if (currentDialog == origin) {
                            currentOnConfirm()
                        }

                        PanelDialogFixAction.OPEN_DEVELOPER_SETTINGS,
                        PanelDialogFixAction.OPEN_DISPLAY_SETTINGS -> Unit
                    }
                } finally {
                    running.set(false)
                }
            }
        }
    }
}

/** 悬浮窗里也能跳，打不开就退到设置首页 */
private fun openSettings(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        .onFailure { Timber.w(it, "open settings failed: %s", action) }
}
