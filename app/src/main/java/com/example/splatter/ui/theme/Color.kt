package com.example.splatter.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Splatter Studio palette.
 *
 * A single source of truth for the app's colours so every screen shares the
 * same look. The palette is dark-first (the app is a 3D capture tool that
 * wants the viewport to dominate the screen), with a mint primary accent and
 * a periwinkle secondary for highlights.
 */

// Core surfaces (dark)
val Ink = Color(0xFF0A0B0F)          // app background
val InkElevated = Color(0xFF12141A)  // cards / sheets
val InkContainer = Color(0xFF1B1E26) // elevated controls
val InkHairline = Color(0xFF272B36)  // borders / dividers

// Accents
val Mint = Color(0xFF4CE0B3)         // primary action accent
val MintDeep = Color(0xFF0F2C25)     // on-accent container tint
val Periwinkle = Color(0xFF7C8CFF)   // secondary accent
val Coral = Color(0xFFFF6B8A)        // destructive / record
val Amber = Color(0xFFFFC46B)        // warnings
val Sky = Color(0xFF67C7FF)          // informational

// Text
val Snow = Color(0xFFF5F7FA)         // primary text
val Fog = Color(0xFF9AA3B2)          // secondary text
val Mist = Color(0xFF5C6474)         // tertiary / disabled

// On-colour helpers
val OnMint = Color(0xFF04211A)
val OnCoral = Color(0xFF2A0710)

// Legacy aliases kept so any older references still resolve.
val Purple80 = Mint
val PurpleGrey80 = Fog
val Pink80 = Coral
val Purple40 = Mint
val PurpleGrey40 = Fog
val Pink40 = Coral
