package com.example.phonecamera.ui.theme

import androidx.compose.ui.graphics.Color

// ─── DARK THEME (Slate & Indigo) ───
val Slate900 = Color(0xFF0F172A) // Nền chính
val Slate800 = Color(0xFF1E293B) // Surface/Card
val Slate700 = Color(0xFF334155) // Thành phần phụ
val Indigo400 = Color(0xFF818CF8) // Primary nhấn (Sáng)
val IndigoContainerDark = Color(0xFF312E81) // Container nhấn

// ─── LIGHT THEME (Slate & Indigo) ───
val Slate50 = Color(0xFFF8FAFC)  // Nền chính
val Slate100 = Color(0xFFF1F5F9) // Surface nhẹ
val Indigo600 = Color(0xFF4F46E5) // Primary nhấn (Đậm)
val IndigoContainerLight = Color(0xFFE0E7FF) // Container nhấn

// ─── SHARED & STATUS ───
val Emerald500 = Color(0xFF10B981) // Online/Success
val Rose500 = Color(0xFFF43F5E)    // Error
val RoseContainerDark = Color(0xFF451A22)
val RoseContainerLight = Color(0xFFFFE4E6)
val Amber500 = Color(0xFFF59E0B)   // Warning

// ─── TEXT COLORS ───
val TextPrimaryDark = Color(0xFFF1F5F9) // Slate 100
val TextSecondaryDark = Color(0xFF94A3B8) // Slate 400
val TextPrimaryLight = Color(0xFF0F172A) // Slate 900
val TextSecondaryLight = Color(0xFF475569) // Slate 600

// ─── OVERLAYS & DIVIDERS ───
val DividerDark = Color(0xFF334155) // Slate 700
val DividerLight = Color(0xFFE2E8F0) // Slate 200
val OverlayDark = Color(0xCC000000)

// ─── COMPATIBILITY ALIASES (Để tránh lỗi compile khi chưa refactor hết) ───
val TextPrimary = TextPrimaryDark
val TextSecondary = TextSecondaryDark
val TextHint = TextSecondaryDark.copy(alpha = 0.6f)
val DividerColor = DividerDark
val NavyDeep = Slate900
val NavyCard = Slate800
val CyanNeon = Indigo400
val RedError = Rose500
val RedErrorSurface = RoseContainerDark
val GreenOnline = Emerald500
val AmberWarning = Amber500
