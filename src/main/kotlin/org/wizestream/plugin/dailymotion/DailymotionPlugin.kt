/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.plugin.WizeStreamPlugin
import org.schabi.newpipe.extractor.plugin.WizeStreamPluginContext

/** Native API 2.0 entry point. Public catalog access needs no credentials or profile state. */
class DailymotionPlugin : WizeStreamPlugin {
    override fun createService(serviceId: Int, context: WizeStreamPluginContext): StreamingService =
        DailymotionService(serviceId)
}
