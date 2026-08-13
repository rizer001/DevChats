package devchats.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProviderAtPosition
import androidx.compose.ui.window.PopupProperties
import devchats.desktop.ui.theme.DevChatsColors
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Якорь контекстного меню: цель (канал или null = пустое место) и позиция в окне. */
private data class MenuAnchor(
    val channel: ConvEntry?,
    val position: Offset,
)

/**
 * Вторая колонка: шапка (сервер или ЛС) и беседы выбранного представления.
 *
 * В сервере ПКМ по каналу открывает меню (переименовать/тип/настроить/удалить),
 * ПКМ по пустому месту — «Создать канал». Дополнительно у каналов есть кнопка
 * «⋯» при наведении — тот же путь к меню без ПКМ.
 *
 * ПКМ перехватывается на уровне AWT ([Toolkit.addAWTEventListener]) в обход
 * Compose pointer-пайплайна. Каждое событие пишется в devchats-debug.log рядом
 * с БД — если ПКМ не работает, по логу сразу видно, доходят ли события.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChannelList(
    headerTitle: String,
    headerSubtitle: String,
    isServerView: Boolean,
    entries: List<ConvEntry>,
    selectedConvKey: String?,
    onSelectConversation: (String) -> Unit,
    onServerSettings: (() -> Unit)?,
    onCreateChannel: (() -> Unit)?,
    onConnectNode: (() -> Unit)?,
    onRenameChannel: ((ConvEntry) -> Unit)?,
    onChannelSettings: ((ConvEntry) -> Unit)?,
    onDeleteChannel: ((ConvEntry) -> Unit)?,
) {
    var menuAnchor by remember { mutableStateOf<MenuAnchor?>(null) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }

    // Актуальные значения для слушателя (живёт вне композиции).
    val currentEntries by rememberUpdatedState(entries)
    val currentIsServerView by rememberUpdatedState(isServerView)
    val currentOnCreateChannel by rememberUpdatedState(onCreateChannel)
    val currentOnRenameChannel by rememberUpdatedState(onRenameChannel)

    DisposableEffect(Unit) {
        val listener = object : java.awt.event.AWTEventListener {
            override fun eventDispatched(event: AWTEvent) {
                if (event !is MouseEvent) return
                if (event.id != MouseEvent.MOUSE_PRESSED) return
                if (event.button != MouseEvent.BUTTON3) return
                val source = event.source as? Component ?: return
                val window = SwingUtilities.getWindowAncestor(source) ?: return
                val content = (window as? JFrame)?.contentPane ?: window
                val pt = SwingUtilities.convertPoint(source, event.point, content)
                val windowPoint = Offset(pt.x.toFloat(), pt.y.toFloat())

                val list = listBounds
                if (list.isEmpty || !list.contains(windowPoint)) {
                    debugLog("ПКМ: мимо списка каналов ($windowPoint, bounds=$list)")
                    return
                }

                val hitKey = rowBounds.entries.firstOrNull { it.value.contains(windowPoint) }?.key
                val target: MenuAnchor? = if (hitKey != null) {
                    val conv = currentEntries.firstOrNull { it.key == hitKey }
                    if (conv != null && currentIsServerView &&
                        conv.key.startsWith("ch:") && !conv.key.contains('@') &&
                        currentOnRenameChannel != null
                    ) {
                        MenuAnchor(conv, windowPoint)
                    } else if (currentIsServerView && currentOnCreateChannel != null) {
                        MenuAnchor(null, windowPoint)
                    } else {
                        null
                    }
                } else if (currentIsServerView && currentOnCreateChannel != null) {
                    MenuAnchor(null, windowPoint)
                } else {
                    null
                }

                if (target != null) {
                    event.consume()
                    debugLog("ПКМ: ОТКРЫВАЮ меню в ($windowPoint) цель=${hitKey ?: "пустое место"}")
                    menuAnchor = target
                } else {
                    debugLog("ПКМ: в списке, но меню не для чего ($windowPoint, hit=$hitKey)")
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        debugLog("ПКМ: слушатель зарегистрирован")
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            debugLog("ПКМ: слушатель снят")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(240.dp)
            .background(DevChatsColors.ChannelsBg),
    ) {
        // Шапка
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable { onServerSettings?.invoke() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = headerTitle,
                color = DevChatsColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onServerSettings != null) {
                Spacer(Modifier.width(8.dp))
                Text("⚙️", fontSize = 14.sp, color = DevChatsColors.TextMuted)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DevChatsColors.ServersBg)
        )

        // Список бесед (границы используются для ПКМ по пустому месту)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { listBounds = it.boundsInWindow() },
        ) {
            if (entries.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (isServerView) "Пусто — ПКМ, чтобы создать канал" else "Пока пусто",
                    color = DevChatsColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    entries.groupBy { it.section }.forEach { (section, convs) ->
                        SectionHeader(section)
                        convs.forEach { conv ->
                            val isLocalChannel = isServerView &&
                                conv.key.startsWith("ch:") && !conv.key.contains('@')
                            ConversationRow(
                                entry = conv,
                                selected = conv.key == selectedConvKey,
                                onClick = { onSelectConversation(conv.key) },
                                onBounds = { rect -> rowBounds[conv.key] = rect },
                                onMore = if (isLocalChannel && onRenameChannel != null) {
                                    {
                                        val rect = rowBounds[conv.key]
                                        val pos = if (rect != null) {
                                            Offset(rect.right - 4f, rect.top)
                                        } else {
                                            Offset.Zero
                                        }
                                        debugLog("⋯: меню для ${conv.title} в $pos")
                                        menuAnchor = MenuAnchor(conv, pos)
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }

        // Низ
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DevChatsColors.ServersBg)
        )
        if (isServerView) {
            if (onCreateChannel != null) {
                TextButton(
                    onClick = onCreateChannel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("＋ Создать канал", color = DevChatsColors.TextMuted, fontSize = 13.sp)
                }
            }
        } else if (onConnectNode != null) {
            TextButton(
                onClick = onConnectNode,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("＋ Подключиться к узлу", color = DevChatsColors.TextMuted, fontSize = 13.sp)
            }
        }
    }

    // Контекстное меню — в точке клика (координаты окна).
    menuAnchor?.let { anchor ->
        val windowMargin = with(LocalDensity.current) { 4.dp.roundToPx() }
        val channel = anchor.channel
        Popup(
            popupPositionProvider = PopupPositionProviderAtPosition(
                positionPx = anchor.position,
                isRelativeToAnchor = false,
                offsetPx = Offset.Zero,
                alignment = Alignment.TopStart,
                windowMarginPx = windowMargin,
            ),
            onDismissRequest = {
                debugLog("Меню: закрыто")
                menuAnchor = null
            },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = DevChatsColors.ChannelsBg,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (channel != null) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            onClick = {
                                debugLog("Меню: переименовать ${channel.title}")
                                menuAnchor = null
                                onRenameChannel?.invoke(channel)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Изменить тип") },
                            onClick = {
                                debugLog("Меню: тип ${channel.title}")
                                menuAnchor = null
                                onChannelSettings?.invoke(channel)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Настроить") },
                            onClick = {
                                debugLog("Меню: настройки ${channel.title}")
                                menuAnchor = null
                                onChannelSettings?.invoke(channel)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить", color = DevChatsColors.Danger) },
                            onClick = {
                                debugLog("Меню: удалить ${channel.title}")
                                menuAnchor = null
                                onDeleteChannel?.invoke(channel)
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Создать канал") },
                            onClick = {
                                debugLog("Меню: создать канал")
                                menuAnchor = null
                                onCreateChannel?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = DevChatsColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ConversationRow(
    entry: ConvEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
    onMore: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> DevChatsColors.Active
        hovered -> DevChatsColors.Hover
        else -> Color.Transparent
    }
    val textColor = if (selected) DevChatsColors.TextPrimary else DevChatsColors.TextMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.prefix, color = DevChatsColors.TextMuted, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.title,
            color = textColor,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.unread > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(DevChatsColors.Accent)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = entry.unread.coerceAtMost(99).toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (onMore != null && hovered) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "⋯",
                color = DevChatsColors.TextPrimary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onMore() }
                    .padding(horizontal = 4.dp, vertical = 0.dp),
            )
        }
    }
}
