package com.perspective.plugin

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class PerspectivePlugin: Plugin<Project> {

    override fun apply(project: Project) {
        println("************start apply PerspectivePlugin************")
        project.extensions.create(PERSPECTIVE_CONFIG, PerspectiveExtension::class.java)

        val android = project.extensions.getByType(AppExtension::class.java)
        if (android is AppExtension) {
            print("registerTransform")
            android.registerTransform(PerspectiveTransform(project))
        }
    }
}