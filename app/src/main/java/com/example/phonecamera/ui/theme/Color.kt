package com.example.phonecamera.ui.theme

import androidx.compose.ui.graphics.Color

// ─── DARK THEME ─────────────────────────────────────────────────────────────
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Indigo400 = Color(0xFF818CF8)
val IndigoContainerDark = Color(0xFF312E81)

// ─── LIGHT THEME ────────────────────────────────────────────────────────────
val Slate50  = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Indigo600 = Color(0xFF4F46E5)
val IndigoContainerLight = Color(0xFFE0E7FF)

// ─── TRẠNG THÁI / STATUS ────────────────────────────────────────────────────
val Emerald500 = Color(0xFF10B981)
val Rose500    = Color(0xFFF43F5E)
val RoseContainerDark  = Color(0xFF451A22)
val RoseContainerLight = Color(0xFFFFE4E6)
val Amber500   = Color(0xFFF59E0B)

// ─── TEXT ────────────────────────────────────────────────────────────────────
val TextPrimaryDark   = Color(0xFFF1F5F9)
val TextSecondaryDark = Color(0xFF94A3B8)
val TextPrimaryLight   = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF475569)

// ─── OVERLAY / DIVIDER ───────────────────────────────────────────────────────
val DividerDark  = Color(0xFF334155)
val DividerLight = Color(0xFFE2E8F0)
val OverlayDark  = Color(0xCC000000)

// ─── ALIASES (dùng trong UI, tự động theo theme) ─────────────────────────────
// Lưu ý: Các alias này LUÔN dùng dark-mode colors vì chúng được hardcode trong
// các component overlay (camera preview, top-bar) — luôn nền tối, không đổi.
val TextPrimary   = TextPrimaryDark            // Alias dùng trong Type.kt
val TextSecondary = TextSecondaryDark          // Dùng cho overlay trên camera
val TextHint      = TextSecondaryDark.copy(alpha = 0.6f)
val OverlayText   = Color.White                // Text trên camera overlay

// ─── SEMANTIC ALIASES ────────────────────────────────────────────────────────
val CyanNeon     = Indigo400
val RedError     = Rose500
val GreenOnline  = Emerald500
val AmberWarning = Amber500
