package com.sleepysoong.autobandselector.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.sleepysoong.autobandselector.R
import com.sleepysoong.autobandselector.data.Carrier
import com.sleepysoong.autobandselector.data.SettingsConfiguration

private val pretendard = FontFamily(Font(R.font.pretendard))

@Composable
fun GlassLogDialog(
    logs: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit
) {
    val tone = Tone(!isSystemInDarkTheme())
    val effects = supportedGlassEffects(Build.VERSION.SDK_INT)
    // Dialogs have their own window: capture a local background, never the dialog contents.
    val backdrop = rememberLayerBackdrop()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.85f).clip(RoundedCornerShape(28.dp))) {
                Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(tone.background))
                GlassCard(backdrop, effects, tone) {
                    SectionTitle("누적 시스템 로그", tone)
                    Spacer(Modifier.height(8.dp))
                    Body("길게 눌러 텍스트를 선택할 수 있습니다.", tone)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Body(logs.ifEmpty { "저장된 로그 기록이 없습니다." }, tone)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            GlassButton("전체 복사", "전체 로그 복사", logs.isNotEmpty(), backdrop, effects, tone, onCopy)
                        }
                        Box(Modifier.weight(1f)) {
                            GlassButton("초기화", "로그 초기화", logs.isNotEmpty(), backdrop, effects, tone, onClear)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            GlassButton("공유", "로그 공유", logs.isNotEmpty(), backdrop, effects, tone, onShare)
                        }
                        Box(Modifier.weight(1f)) {
                            GlassButton("닫기", "로그 닫기", true, backdrop, effects, tone, onDismiss)
                        }
                    }
                }
            }
        }
    }
}

private class Tone(val light: Boolean) {
    val content = if (light) Color(0xFF141417) else Color.White
    val secondary = if (light) Color(0xFF4A4A50) else Color(0xFFE0E0E3)
    val surface = if (light) GlassPalette.LightSurface else GlassPalette.DarkSurface
    val background = Brush.linearGradient(
        if (light) listOf(GlassPalette.LightBackground, GlassPalette.LightBackgroundEnd)
        else listOf(GlassPalette.DarkBackground, GlassPalette.DarkBackgroundEnd)
    )
}

@Composable
fun BandSelectorScreen(
    ui: GlassUiState,
    subscriptionText: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestore: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestPhonePermission: () -> Unit,
    onConfirmSlot: (Int) -> Unit,
    onOpenSimSettings: () -> Unit,
    onShowLogs: () -> Unit,
    configuration: SettingsConfiguration,
    onDeviceCarrier: (Carrier) -> Unit,
    onSimCarrier: (Carrier) -> Unit
) {
    val tone = Tone(!isSystemInDarkTheme())
    val effects = supportedGlassEffects(Build.VERSION.SDK_INT)
    val backdrop = rememberLayerBackdrop()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Capture only the background. Capturing the glass consumers too creates
                // a graphics-layer cycle that can crash Android's RenderThread.
                Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(tone.background))
                if (!wide) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Header(tone)
                        PreflightCard(ui, subscriptionText, backdrop, effects, tone, onOpenAccessibility,
                            onRequestPhonePermission, onConfirmSlot, onOpenSimSettings)
                        CarrierCard(configuration, tone, onDeviceCarrier, onSimCarrier)
                        StatusCard(ui, backdrop, effects, tone)
                        ControlsCard(ui, backdrop, effects, tone, onStart, onStop, onRestore, onShowLogs)
                    }
                } else {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Header(tone)
                            PreflightCard(ui, subscriptionText, backdrop, effects, tone, onOpenAccessibility,
                                onRequestPhonePermission, onConfirmSlot, onOpenSimSettings)
                            ControlsCard(ui, backdrop, effects, tone, onStart, onStop, onRestore, onShowLogs)
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatusCard(ui, backdrop, effects, tone)
                        }
                    }
                }
            }
            BasicText(
                "sleepysoong 제작",
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                style = TextStyle(
                    color = tone.secondary, fontFamily = pretendard,
                    fontSize = 11.sp, textAlign = TextAlign.Center
                )
            )
        }
    }
}

@Composable
private fun Header(tone: Tone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(
            painter = painterResource(id = R.drawable.ic_cat_launcher),
            contentDescription = "auto-band-selector 고양이 아이콘",
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
        )
        BasicText("auto-band-selector",
            style = TextStyle(color = tone.content, fontFamily = pretendard,
                fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.05).sp))
    }
}

@Composable
private fun GlassCard(backdrop: Backdrop, effects: GlassEffects, tone: Tone,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(28f.dp) },
                effects = {
                    colorControls(brightness = if (tone.light) 0.04f else -0.04f, saturation = 1.05f)
                    effects.blurPx?.let { blur(it) }
                    if (effects.lens) lens(20f, 24f, depthEffect = true)
                },
                highlight = { Highlight.Plain },
                onDrawSurface = { drawRect(tone.surface) }
            )
            .padding(20.dp)
    ) { content() }
}

@Composable
private fun SectionTitle(text: String, tone: Tone) = BasicText(
    text, style = TextStyle(color = tone.content, fontFamily = pretendard,
        fontWeight = FontWeight.Bold, fontSize = 16.sp))

@Composable
private fun Body(text: String, tone: Tone, color: Color = tone.secondary) = BasicText(
    text, style = TextStyle(color = color, fontFamily = pretendard, fontSize = 13.sp))

@Composable
private fun GlassButton(
    label: String,
    description: String,
    enabled: Boolean,
    backdrop: Backdrop,
    effects: GlassEffects,
    tone: Tone,
    onClick: () -> Unit,
    primary: Boolean = false
) {
    val surface = if (!enabled) (if (tone.light) Color.Black.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.25f))
        else if (primary) Color.White.copy(alpha = 0.85f) else tone.surface
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description; role = Role.Button }
            .clip(Capsule())
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    colorControls(brightness = if (primary) 0.1f else 0f, saturation = 1.05f)
                    effects.blurPx?.let { blur(it * 0.75f) }
                },
                highlight = { Highlight.Plain },
                onDrawSurface = { drawRect(surface) }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, Modifier,
            style = TextStyle(
                color = if (!enabled) tone.secondary.copy(alpha = 0.5f)
                    else if (primary) Color.Black else tone.content,
                fontFamily = pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp),
        )
    }
}

@Composable
private fun PreflightCard(
    ui: GlassUiState,
    subscriptionText: String?,
    backdrop: Backdrop,
    effects: GlassEffects,
    tone: Tone,
    onOpenAccessibility: () -> Unit,
    onRequestPhonePermission: () -> Unit,
    onConfirmSlot: (Int) -> Unit,
    onOpenSimSettings: () -> Unit
) {
    GlassCard(backdrop, effects, tone) {
        SectionTitle("1. 접근성과 KT eSIM 사전 점검", tone)
        Spacer(Modifier.height(8.dp))
        Body(if (ui.accessibilityActionVisible) "접근성 서비스가 꺼져 있습니다." else "접근성 서비스가 켜져 있습니다.", tone)
        Body(subscriptionText ?: "KT eSIM 상태를 확인할 수 없습니다.", tone,
            if (ui.simSettingsVisible) GlassPalette.Accent else tone.secondary)
        ui.blockedReason?.let { Body("시작이 차단되었습니다: " + it, tone, GlassPalette.Accent) }
        Spacer(Modifier.height(12.dp))
        if (ui.accessibilityActionVisible) {
            GlassButton("접근성 권한 활성화하기", "접근성 권한 활성화", true, backdrop, effects, tone, onOpenAccessibility)
        }
        if (ui.phonePermissionActionVisible) {
            GlassButton("전화 권한 허용", "전화 상태 권한 요청", true, backdrop, effects, tone, onRequestPhonePermission)
        }
        if (ui.slotConfirmationVisible) {
            GlassButton("SIM 1 확인", "삼성 메뉴의 SIM 1 사용", true, backdrop, effects, tone, { onConfirmSlot(0) })
            Spacer(Modifier.height(8.dp))
            GlassButton("SIM 2 확인", "삼성 메뉴의 SIM 2 사용", true, backdrop, effects, tone, { onConfirmSlot(1) })
        }
        if (ui.simSettingsVisible) {
            GlassButton("SIM 설정 열기", "SIM 설정 열기", true, backdrop, effects, tone, onOpenSimSettings)
        }
    }
}

@Composable
private fun StatusCard(ui: GlassUiState, backdrop: Backdrop, effects: GlassEffects, tone: Tone) {
    GlassCard(backdrop, effects, tone) {
        SectionTitle("2. 실행 상태와 결과", tone)
        Spacer(Modifier.height(8.dp))
        Body("한 번 실행하면 최대 27MB 통신을 사용합니다.", tone)
        ui.phase?.let { phase ->
            Body("현재 단계: " + phase.name + (ui.candidateBand?.let { " / LTE B" + it } ?: ""), tone, GlassPalette.Accent)
        }
        ui.winnerBand?.let { Body("최적 대역 적용 완료: LTE B" + it, tone, GlassPalette.Accent) }
        ui.failureReason?.let { Body("오류: " + it, tone, GlassPalette.Accent) }
        ui.recoveryText?.let { Body("복구 상태: " + it, tone) }
        if (ui.cancelled) Body("중지되었습니다.", tone)
        if (ui.rows.isNotEmpty()) Spacer(Modifier.height(8.dp))
        ui.rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body("LTE B" + row.band, tone)
                Body(row.medianText?.let { it + " Mbps" } ?: (row.failureText ?: row.status), tone)
            }
        }
    }
}

@Composable
private fun ControlsCard(
    ui: GlassUiState,
    backdrop: Backdrop,
    effects: GlassEffects,
    tone: Tone,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestore: () -> Unit,
    onShowLogs: () -> Unit
) {
    GlassCard(backdrop, effects, tone) {
        SectionTitle("3. 실행 및 제어", tone)
        Spacer(Modifier.height(12.dp))
        GlassButton("자동 주파수 스캔 시작", "스캔 시작", ui.startEnabled, backdrop, effects, tone, onStart, primary = true)
        Spacer(Modifier.height(10.dp))
        if (ui.stopVisible) {
            GlassButton("중지", "실행 중인 작업 중지", true, backdrop, effects, tone, onStop)
            Spacer(Modifier.height(10.dp))
        }
        GlassButton("자동 모드로 복구", "자동 모드 복구", ui.restoreEnabled, backdrop, effects, tone, onRestore)
        Spacer(Modifier.height(10.dp))
        GlassButton("누적 로그 확인하기", "로그 확인", true, backdrop, effects, tone, onShowLogs)
    }
}

@Composable
private fun CarrierCard(
    configuration: SettingsConfiguration,
    tone: Tone,
    onDeviceCarrier: (Carrier) -> Unit,
    onSimCarrier: (Carrier) -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(tone.surface)
            .padding(20.dp)
    ) {
        SectionTitle("기기 및 유심 설정", tone)
        Spacer(Modifier.height(4.dp))
        Body("기기 원통신사", tone)
        CarrierRow(listOf(Carrier.SKT to "SKT 기기", Carrier.KT to "KT 기기", Carrier.LGU_PLUS to "LGU+ 기기"),
            configuration.deviceCarrier, tone) { onDeviceCarrier(it) }
        Spacer(Modifier.height(10.dp))
        Body("장착된 유심 통신사", tone)
        CarrierRow(listOf(Carrier.SKT to "SKT 유심", Carrier.KT to "KT 유심", Carrier.LGU_PLUS to "LGU+ 유심"),
            configuration.simCarrier, tone) { onSimCarrier(it) }
    }
}

@Composable
private fun CarrierRow(
    options: List<Pair<Carrier, String>>,
    selected: Carrier,
    tone: Tone,
    onSelect: (Carrier) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (carrier, label) ->
            val active = carrier == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(Capsule())
                    .background(if (active) tone.content else tone.surface)
                    .semantics { role = Role.RadioButton; contentDescription = label }
                    .clickable { onSelect(carrier) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(label, Modifier,
                    style = TextStyle(
                        color = if (active) {
                            if (tone.light) Color.White else Color(0xFF0B0B0D)
                        } else tone.secondary,
                        fontFamily = pretendard, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
