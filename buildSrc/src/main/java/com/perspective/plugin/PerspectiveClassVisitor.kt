package com.perspective.plugin

import org.objectweb.asm.*
import org.objectweb.asm.commons.AdviceAdapter

const val METHOD_NAME_ON_CREATE = "onCreate"

class PerspectiveClassVisitor(writer: ClassWriter) : ClassVisitor(Opcodes.ASM5, writer) {

    private var className: String = ""

    override fun visit(
        version: Int, access: Int, name: String?,
        signature: String?, superName: String?, interfaces: Array<out String>?
    ) {
        className = name!!
        super.visit(version, access, name, signature, superName, interfaces)
    }


    override fun visitMethod(
        access: Int,
        name: String?,
        desc: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val methodVisitor = super.visitMethod(access, name, desc, signature, exceptions)
        return when(name) {
            METHOD_NAME_ON_CREATE -> {
                PerspectiveMethodVisitor(api, methodVisitor, access, name, desc!!, className)
            }
            else -> methodVisitor
        }
    }
}

class PerspectiveMethodVisitor(
    api: Int,
    mv: MethodVisitor,
    access: Int,
    methodName: String,
    descriptor: String,
    private val className: String
) : AdviceAdapter(api, mv, access, methodName, descriptor) {

    private var timeLocalIndex = 0

    override fun onMethodEnter() {
        super.onMethodEnter()
        log("onMethodEnter This is the $METHOD_NAME_ON_CREATE of $className page")
        timeLocalIndex = newLocal(Type.LONG_TYPE)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        //将结果保存在变量表中
        mv.visitVarInsn(LSTORE, timeLocalIndex)
    }

    override fun onMethodExit(opcode: Int) {
        super.onMethodExit(opcode)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        //将方法进入是的记录的时间入栈
        mv.visitVarInsn(LLOAD, timeLocalIndex)
        //相减
        mv.visitInsn(LSUB)
        //将相减的结果保存在变量表中
        mv.visitVarInsn(LSTORE, timeLocalIndex)

        mv.visitLdcInsn("jcy")
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        mv.visitLdcInsn(className + " : ")
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        mv.visitLdcInsn("$METHOD_NAME_ON_CREATE: ")
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        mv.visitVarInsn(LLOAD, timeLocalIndex)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", false)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        mv.visitMethodInsn(INVOKESTATIC, "android/util/Log", "d", "(Ljava/lang/String;Ljava/lang/String;)I", false)
        mv.visitInsn(POP)
    }
}