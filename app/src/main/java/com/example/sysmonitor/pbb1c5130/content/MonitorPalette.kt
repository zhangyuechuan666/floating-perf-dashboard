package com.example.sysmonitor.pbb1c5130.content

import androidx.compose.ui.graphics.Color

/**
 * App-owned accent colours for the performance graphs, modelled on the Windows Task
 * Manager convention of one hue per resource. They are used only for graph strokes,
 * fills, grids and indicator dots; text keeps native PicoTheme label roles.
 */
object MonitorPalette {
    val CpuAccent = Color(0xFF117DBB) // design-style: fixed-figma-color Task Manager-style CPU blue
    val GpuAccent = Color(0xFF4DA60C) // design-style: fixed-figma-color Task Manager-style GPU green
    val NetworkAccent = Color(0xFFA74F01) // design-style: fixed-figma-color Task Manager-style network amber
    val ThermalAccent = Color(0xFFC4314B) // design-style: fixed-figma-color temperature red
    val PowerAccent = Color(0xFF8764B8) // design-style: fixed-figma-color power purple
    val DisplayAccent = Color(0xFF038387) // design-style: fixed-figma-color display teal
}
