package com.example.sysmonitor.pbb1c5130

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sysmonitor.pbb1c5130.content.CompactStrip
import com.example.sysmonitor.pbb1c5130.content.DetailsPage
import com.example.sysmonitor.pbb1c5130.content.DetailsTheme
import com.example.sysmonitor.pbb1c5130.content.InstrumentCluster
import com.example.sysmonitor.pbb1c5130.content.rememberAppTheme
import com.example.sysmonitor.pbb1c5130.platform.KeepAlive
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.resize.ContainerResizeType

/** Must match `pico.spatial.windowcontainer.id` of the launcher activity in AndroidManifest.xml. */
const val COMPACT_WINDOW_ID = "ResourceOverlayCompact"
const val DETAILS_WINDOW_ID = "ResourceOverlayDetails"
const val CLUSTER_WINDOW_ID = "ResourceOverlayCluster"

/** Opening with a fixed tag reuses the cluster window instead of stacking new copies. */
const val CLUSTER_WINDOW_TAG = "cluster"

/** Opening with a fixed tag reuses the details window instead of stacking new copies. */
private const val DETAILS_WINDOW_TAG = "details"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        // Main floating window. Classic theme: the compact CPU · GPU · NET strip (size, no window
        // glass and the auto-hiding caption bar are configured on the launcher activity in the
        // manifest). Gauge theme: this window hands over to the 3D cluster and closes itself.
        DefaultWindowContainer {
            PicoTheme {
                val navigator = LocalSpatialNavigator.current
                val context = LocalContext.current
                val theme = rememberAppTheme().theme
                // App is in the foreground here, so the keep-alive service may start.
                LaunchedEffect(Unit) { KeepAlive.onAppVisible(context) }
                LaunchedEffect(theme) {
                    if (theme == DetailsTheme.Gauge) {
                        navigator.openWindowContainer(CLUSTER_WINDOW_ID, CLUSTER_WINDOW_TAG)
                        navigator.closeWindowContainer()
                    }
                }
                val size = Modifier.windowConstraints(width = COMPACT_WIDTH, height = COMPACT_HEIGHT)
                if (theme == DetailsTheme.Classic) {
                    CompactStrip(
                        onOpenDetails = { navigator.openWindowContainer(DETAILS_WINDOW_ID, DETAILS_WINDOW_TAG) },
                        modifier = size,
                    )
                } else {
                    Box(size)
                }
            }
        }

        // Details window, opened from the strip or a cluster dial and placed above it.
        WindowContainer(
            id = DETAILS_WINDOW_ID,
            form = Form.Planar,
            properties = {
                defaultSize = WindowContainerSize(width = 880.dp, height = 500.dp)
                resizeType = ContainerResizeType.ContentMinSize
                enableMaterialBackground = true
                placement = {
                    (containers.firstOrNull { it.id == COMPACT_WINDOW_ID } ?: containers.firstOrNull { it.id == CLUSTER_WINDOW_ID })
                        ?.let { Placement.top(it, offset = DETAILS_GAP) }
                        ?: Placement.none()
                }
            },
        ) {
            PicoTheme {
                // Keep the side list + graph + stats legible when the user resizes the window.
                DetailsPage(Modifier.windowConstraints(minWidth = 760.dp, minHeight = 460.dp))
            }
        }

        // Gauge theme's floating window: the 3D dial cluster, in place of the strip (below the
        // details window when it is open). Switching back to Classic reopens the strip and closes it.
        WindowContainer(
            id = CLUSTER_WINDOW_ID,
            form = Form.Volumetric,
            properties = {
                defaultSize = WindowContainerSize(1.00f, 0.34f, 0.30f, LengthUnit.Meters)
                enableMaterialBackground = false
                placement = {
                    containers.firstOrNull { it.id == DETAILS_WINDOW_ID }
                        ?.let { Placement.bottom(it, offset = DETAILS_GAP) }
                        ?: Placement.none()
                }
            },
        ) {
            PicoTheme {
                val navigator = LocalSpatialNavigator.current
                val context = LocalContext.current
                val theme = rememberAppTheme().theme
                LaunchedEffect(Unit) { KeepAlive.onAppVisible(context) }
                LaunchedEffect(theme) {
                    if (theme == DetailsTheme.Classic) {
                        navigator.openWindowContainer(COMPACT_WINDOW_ID, null)
                        navigator.closeWindowContainer()
                    }
                }
                InstrumentCluster(onOpenDetails = { navigator.openWindowContainer(DETAILS_WINDOW_ID, DETAILS_WINDOW_TAG) })
            }
        }
    }

private val COMPACT_WIDTH = 780.dp
private val COMPACT_HEIGHT = 180.dp // PICO minimum planar window height

/** Gap between the strip window and the details window (system default is 56 dp). */
private val DETAILS_GAP = 12.dp
