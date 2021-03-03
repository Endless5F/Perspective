package com.perspective.plugin

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.google.common.collect.FluentIterable
import com.google.common.io.Files
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry


class PerspectiveTransform(private val project: Project) : Transform() {

    private var userConfig: PerspectiveExtension? = null

    /**
     * 透视眼
     */
    override fun getName(): String {
        return "PerspectiveEye"
    }

    /**
     * 处理的数据类型：
     *     CLASSES：代表处理的 java 的 class 文件
     *     RESOURCES：代表要处理 java 的资源
     */
    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun isIncremental(): Boolean {
        initConfig()
        return userConfig?.enableIncremental ?: false
    }

    /**
     * 操作内容的范围：
     *     EXTERNAL_LIBRARIES ： 只有外部库
     *     PROJECT ： 只有项目内容
     *     PROJECT_LOCAL_DEPS ： 只有项目的本地依赖(本地jar)
     *     PROVIDED_ONLY ： 只提供本地或远程依赖项
     *     SUB_PROJECTS ： 只有子项目
     *     SUB_PROJECTS_LOCAL_DEPS： 只有子项目的本地依赖项(本地jar)
     *     TESTED_CODE ：由当前变量(包括依赖项)测试的代码
     * 处理所有的class字节码，返回TransformManager.SCOPE_FULL_PROJECT
     */
    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    /**
     * 初始化个人配置
     */
    private fun initConfig() {
        if (userConfig != null) {
            return
        }

        if (!project.hasProperty(PERSPECTIVE_CONFIG)) {
            return
        }

        userConfig = project.property(PERSPECTIVE_CONFIG) as PerspectiveExtension?
    }

    override fun transform(transformInvocation: TransformInvocation?) {
        super.transform(transformInvocation)
        transformInvocation ?: return
        val startTime = System.currentTimeMillis()
        initConfig()

        val isIncremental = transformInvocation.isIncremental
        val outputProvider = transformInvocation.outputProvider

        if (!isIncremental) {
            outputProvider?.deleteAll()
        }

        log("transform start incremental: $isIncremental")
        val futures = mutableListOf<Future<*>>()
        val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        transformInvocation.inputs?.forEach { transformInput ->
            // 本地 project 编译成的多个 class ⽂件存放的目录
            transformInput.directoryInputs.forEach { directoryInput ->
                val dest = outputProvider.getContentLocation(
                    directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
                FileUtils.copyDirectory(directoryInput.file, dest)
                if (isIncremental) {
                    directoryInput.changedFiles.forEach { (file, status) ->
                        when(status) {
                            Status.ADDED, Status.CHANGED -> {
                                transformFile(file)
                                FileUtils.copyDirectory(directoryInput.file, dest)
                            }
                            Status.REMOVED -> if (dest.exists()) {
                                FileUtils.forceDelete(dest)
                            }
                            else -> {
                                // nothing
                            }
                        }
                    }
                } else {
                    futures.add(executor.submit {
                        if (directoryInput.file.isDirectory) {
                            getAllFiles(directoryInput.file).forEach {
                                transformFile(it)
                            }
                            FileUtils.copyDirectory(directoryInput.file, dest)
                        }
                    })
                }
            }

            // 各个依赖所编译成的 jar(aar) 文件
            transformInput.jarInputs.forEach { jarInput ->
                val dest = outputProvider.getContentLocation(
                    jarInput.name, jarInput.contentTypes, jarInput.scopes, Format.JAR)
                FileUtils.copyFile(jarInput.file, dest)
                if (isIncremental) {
                    when (jarInput.status) {
                        Status.ADDED, Status.CHANGED -> {
                            futures.add(executor.submit {
                                processJar(jarInput, dest)
                            })
                        }
                        Status.REMOVED -> if (dest.exists()) {
                            FileUtils.forceDelete(dest)
                        }
                        else -> {
                            // nothing
                        }
                    }
                } else {
                    futures.add(executor.submit {
                        processJar(jarInput, dest)
                    })
                }
            }
        }
        futures.forEach {
            it.get()
        }
        executor.shutdown()
        log("########transform cost ${System.currentTimeMillis() - startTime}ms##########")
    }

    private fun transformFile(file: File) {
        val fileName = file.name
        if (fileName.endsWith(".class")
            && !fileName.endsWith("R.class")
            && !fileName.endsWith("BuildConfig.class")
            && !fileName.contains("R$")) {
            val reader = ClassReader(file.readBytes())
            // ClassWriter的构造函数需要传入一个 flag，其含义为：
            //     ClassWriter(0)：表示 ASM 不会自动自动帮你计算栈帧和局部变量表和操作数栈大小。
            //     ClassWriter(ClassWriter.COMPUTE_MAXS)：表示 ASM 会自动帮你计算局部变量表和操作数栈的大小，但是你还是需要调用visitMaxs方法，但是可以使用任意参数，因为它们会被忽略。带有这个标识，对于栈帧大小，还是需要你手动计算。
            //     ClassWriter(ClassWriter.COMPUTE_FRAMES)：表示 ASM 会自动帮你计算所有的内容。你不必去调用visitFrame，但是你还是需要调用visitMaxs方法（参数可任意设置，同样会被忽略）。
            //     使用这些标识很方便，但是会带来一些性能上的损失：COMPUTE_MAXS标识会使ClassWriter慢10%，COMPUTE_FRAMES标识会使ClassWriter慢2倍。
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
            val visitor = PerspectiveClassVisitor(writer)
            reader.accept(visitor, ClassReader.EXPAND_FRAMES)
            val fos = FileOutputStream(file)
            fos.write(writer.toByteArray())
            fos.flush()
            fos.close()
        }
    }

    private fun processJar(jarInput: JarInput, destFile: File) {
        val md5Name = DigestUtils.md5Hex(jarInput.file.absolutePath)
        log("jarName=${jarInput.file.name} md5Name=${md5Name}")

        var tempFile = jarInput.file
        if (tempFile.absolutePath.endsWith(".jar")) {
            tempFile = File(jarInput.file.parent + File.separator + "${md5Name}.jar")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            transformJar(jarInput, tempFile)
        }

        FileUtils.copyFile(tempFile, destFile)

        tempFile.delete()
    }

    private fun transformJar(jarInput: JarInput, tempJar: File) {
        val jarFile = JarFile(jarInput.file)
        val enumeration = jarFile.entries()

        val jarOutputStream = JarOutputStream(FileOutputStream(tempJar))

        while (enumeration.hasMoreElements()) {
            val jarEntry = enumeration.nextElement()
            val jarInputStream = jarFile.getInputStream(jarEntry)
            val zipEntry = ZipEntry(jarEntry.name)

            jarOutputStream.putNextEntry(zipEntry)
            if (jarEntry.name.endsWith(".class")
                && !jarEntry.name.endsWith("R.class")
                && !jarEntry.name.endsWith("BuildConfig.class")
                && !jarEntry.name.contains("R$")) {
                val reader = ClassReader(IOUtils.toByteArray(jarInputStream))
                val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
                val visitor = PerspectiveClassVisitor(writer)

                // ClassReader.accept(ClassVisitor classVisitor, int parsingOptions)中，第二个参数parsingOptions的取值有以下选项：
                //     ClassReader.SKIP_DEBUG：表示不遍历调试内容，即跳过源文件，源码调试扩展，局部变量表，局部变量类型表和行号表属性，即以下方法既不会被解析也不会被访问（ClassVisitor.visitSource，MethodVisitor.visitLocalVariable，MethodVisitor.visitLineNumber）。使用此标识后，类文件调试信息会被去除，请警记。
                //     ClassReader.SKIP_CODE：设置该标识，则代码属性将不会被转换和访问，例如方法体代码不会进行解析和访问。
                //     ClassReader.SKIP_FRAMES：设置该标识，表示跳过栈图（StackMap）和栈图表（StackMapTable）属性，即MethodVisitor.visitFrame方法不会被转换和访问。当设置了ClassWriter.COMPUTE_FRAMES时，设置该标识会很有用，因为他避免了访问帧内容（这些内容会被忽略和重新计算，无需访问）。
                //     ClassReader.EXPAND_FRAMES：该标识用于设置扩展栈帧图。默认栈图以它们原始格式（V1_6以下使用扩展格式，其他使用压缩格式）被访问。如果设置该标识，栈图则始终以扩展格式进行访问（此标识在ClassReader和ClassWriter中增加了解压/压缩步骤，会大幅度降低性能）。
                reader.accept(visitor, ClassReader.EXPAND_FRAMES)
                jarOutputStream.write(writer.toByteArray())
            } else {
                jarOutputStream.write(IOUtils.toByteArray(jarInputStream))
            }

            jarInputStream.close()
            jarOutputStream.closeEntry()
        }

        jarFile.close()
        jarOutputStream.close()
    }

    private fun getAllFiles(dir: File): FluentIterable<File> {
        return Files.fileTreeTraverser().preOrderTraversal(dir).filter(Files.isFile())
    }
}