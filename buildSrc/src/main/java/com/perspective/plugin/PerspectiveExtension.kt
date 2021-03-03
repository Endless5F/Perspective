package com.perspective.plugin

const val PERSPECTIVE_CONFIG = "perspectiveConfig"

/**
 * 必须是open
 */
open class PerspectiveExtension {
    // 是否使用此功能
    var enable: Boolean = true

    // 是否开启transform的增量编译
    var enableIncremental: Boolean = true
}