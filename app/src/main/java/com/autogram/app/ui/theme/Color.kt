package com.autogram.app.ui.theme

import androidx.compose.ui.graphics.Color

// Autogram brand palette — gold on dark brown, locked in v1.20.9 to match
// the launcher icon. The five seed colours come from the icon spec:
//
//   #1A120B  fondo oscuro          → background / surface
//   #2A1B12  degradado fondo       → surfaceVariant / elevated surfaces
//   #D4AF37  dorado principal      → primary
//   #B8962E  dorado secundario     → primaryContainer / secondary
//   #F1D27A  brillo / luz          → tertiary / highlights / "on" text
//
// We deliberately disable Material 3 dynamic-colour (see AutogramTheme) so
// the gold doesn't get re-tinted by the user's wallpaper — the brand is
// the brand on every device.

private val GoldPrimary    = Color(0xFFD4AF37)
private val GoldSecondary  = Color(0xFFB8962E)
private val GoldHighlight  = Color(0xFFF1D27A)
private val BrownDeep      = Color(0xFF1A120B)
private val BrownElevated  = Color(0xFF2A1B12)

// Slightly-lifted brown for cards / containers that need to read above the
// `surface` plane without leaving the brand. Hand-picked between the two
// brown seeds so the elevation hierarchy stays readable.
private val BrownContainer = Color(0xFF3D2A1C)
// Warm off-white for body text on brown. Pure white reads too cold against
// the warm browns; this is white shifted ~6% toward the gold.
private val WarmTextHigh   = Color(0xFFF5ECD9)
private val WarmTextMuted  = Color(0xFFC9B894)
private val WarmOutline    = Color(0xFF6B5535)
private val WarmOutlineDim = Color(0xFF3D2F1F)

// --- Light scheme ---------------------------------------------------------
// AutogramTheme forces dark mode regardless of system, so the light scheme
// only ever runs in @Preview-without-darkTheme or Compose tooling. We keep
// it brand-coherent (warm cream + gold) so previews don't render off-brand,
// but no end user actually sees these tokens at runtime.

val md_theme_light_primary = GoldSecondary
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFFFE7A8)
val md_theme_light_onPrimaryContainer = Color(0xFF2A1B12)
val md_theme_light_secondary = Color(0xFF6B5535)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFFFE0B2)
val md_theme_light_onSecondaryContainer = Color(0xFF2A1B12)
val md_theme_light_tertiary = Color(0xFF8A6E2A)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFFFEFC4)
val md_theme_light_onTertiaryContainer = Color(0xFF2A1B12)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_onErrorContainer = Color(0xFF410002)
val md_theme_light_background = Color(0xFFFFF8E8)
val md_theme_light_onBackground = Color(0xFF1A120B)
val md_theme_light_surface = Color(0xFFFFF8E8)
val md_theme_light_onSurface = Color(0xFF1A120B)
val md_theme_light_surfaceVariant = Color(0xFFF1E4C4)
val md_theme_light_onSurfaceVariant = Color(0xFF4D3F25)
val md_theme_light_outline = Color(0xFF7E6B45)
val md_theme_light_inverseOnSurface = Color(0xFFFFF8E8)
val md_theme_light_inverseSurface = Color(0xFF2A1B12)
val md_theme_light_inversePrimary = GoldHighlight
val md_theme_light_surfaceTint = GoldSecondary
val md_theme_light_outlineVariant = Color(0xFFD4C4A0)
val md_theme_light_scrim = Color(0xFF000000)

// --- Dark scheme ----------------------------------------------------------
// This is what every device renders at runtime.

val md_theme_dark_primary = GoldPrimary
val md_theme_dark_onPrimary = BrownDeep
val md_theme_dark_primaryContainer = GoldSecondary
val md_theme_dark_onPrimaryContainer = BrownDeep
val md_theme_dark_secondary = GoldHighlight
val md_theme_dark_onSecondary = BrownDeep
val md_theme_dark_secondaryContainer = BrownContainer
val md_theme_dark_onSecondaryContainer = GoldHighlight
val md_theme_dark_tertiary = GoldHighlight
val md_theme_dark_onTertiary = BrownDeep
val md_theme_dark_tertiaryContainer = BrownElevated
val md_theme_dark_onTertiaryContainer = GoldHighlight
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = BrownDeep
val md_theme_dark_onBackground = WarmTextHigh
val md_theme_dark_surface = BrownDeep
val md_theme_dark_onSurface = WarmTextHigh
val md_theme_dark_surfaceVariant = BrownElevated
val md_theme_dark_onSurfaceVariant = WarmTextMuted
val md_theme_dark_outline = WarmOutline
val md_theme_dark_inverseOnSurface = BrownDeep
val md_theme_dark_inverseSurface = WarmTextHigh
val md_theme_dark_inversePrimary = GoldSecondary
val md_theme_dark_surfaceTint = GoldPrimary
val md_theme_dark_outlineVariant = WarmOutlineDim
val md_theme_dark_scrim = Color(0xFF000000)
