package org.frknkrc44.hma_oss.zygote

import android.app.ActivityThread
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ServiceManager
import com.v7878.unsafe.Reflection.getDeclaredField
import com.v7878.unsafe.Reflection.getDeclaredMethod
import com.v7878.unsafe.invoke.EmulatedStackFrame
import icu.nullptr.hidemyapplist.common.Constants
import icu.nullptr.hidemyapplist.common.Utils
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Predicate
import java.util.regex.Pattern


object Utils4Zygote {
    fun dumpArgs(frame: EmulatedStackFrame): Array<Any?> {
        return mutableListOf<Any?>().let {
            for (i in 0 ..< frame.type().parameterCount()) {
                val accessor = frame.accessor()

                it.add(
                    when (accessor.getArgumentShorty(i)) {
                        'L' -> accessor.getReference(i)
                        'Z' -> accessor.getBoolean(i)
                        'B' -> accessor.getByte(i)
                        'C' -> accessor.getChar(i)
                        'S' -> accessor.getShort(i)
                        'I' -> accessor.getInt(i)
                        'J' -> accessor.getLong(i)
                        'F' -> accessor.getFloat(i)
                        'D' -> accessor.getDouble(i)
                        else -> throw Exception("Should not reach here")
                    }
                )
            }

            it.toTypedArray()
        }
    }

    fun filter(pattern: String): Predicate<Executable> {
        val compiledPattern = Pattern.compile(pattern)
        return Predicate { executable: Executable? ->
            compiledPattern.matcher(printExecutable(executable)).matches()
        }
    }

    @Throws(InterruptedException::class)
    fun waitForService(name: String?): IBinder? {
        var service: IBinder? = null

        do {
            Thread.sleep(250)
        } while ((ServiceManager.getService(name).also { service = it }) == null)

        return service
    }

    fun getPackageNameFromPackageSettings(packageSettings: Any): String? {
        return runCatching {
            packageSettings::class.java.getField(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "mName" else "name"
            ).apply { isAccessible = true }.get(packageSettings) as? String
        }.getOrNull()
    }

    fun getPackageManager() = ActivityThread.currentActivityThread().application.packageManager!!

    fun getCallingApps(service: HMAService): Array<String> {
        return getCallingApps(service, Binder.getCallingUid())
    }

    fun getCallingApps(service: HMAService, callingUid: Int): Array<String> {
        if (callingUid == Constants.UID_SYSTEM) return arrayOf()
        return Utils.binderLocalScope {
            service.pms.getPackagesForUid(callingUid)
        } ?: arrayOf()
    }

    fun getStaticIntField(className: String, name: String) = getDeclaredField(
        Class.forName(className),
        name,
    ).getInt(null)

    fun getIntField(obj: Any, name: String) = getDeclaredField(obj.javaClass, name).getInt(obj)

    fun getBooleanField(obj: Any, name: String) = getDeclaredField(obj.javaClass, name).getBoolean(obj)

    fun getObjectField(obj: Any, name: String): Any? = getDeclaredField(obj.javaClass, name).get(obj)

    fun setBooleanField(obj: Any, name: String, value: Boolean) {
        val field = getDeclaredField(obj.javaClass, name).apply { isAccessible = true }
        field.setBoolean(obj, value)
    }

    fun callMethod(obj: Any, name: String, vararg args: Any): Any? {
        return getDeclaredMethod(
            obj.javaClass,
            name,
            *args.map { it.javaClass }.toTypedArray()
        ).apply { isAccessible = true }.invoke(obj, *args)
    }

    fun findConstructor(className: String, paramCount: Int = -1): Constructor<*>? {
        val clazz = Class.forName(className, true, SystemServerHook.classLoader)

        return clazz.constructors.firstOrNull {
            paramCount == -1 || it.parameterCount == paramCount
        }
    }

    fun findMethod(className: String, name: String, isDeclared: Boolean = false, systemClassLoader: Boolean = false, vararg args: Class<*>): Method {
        val clazz = if (systemClassLoader) Class.forName(className, true, SystemServerHook.classLoader) else Class.forName(className)
        return if (isDeclared) {
            clazz.getDeclaredMethod(name, *args)
        } else {
            clazz.getMethod(name, *args)
        }
    }

    private fun getClassName(clazz: Class<*>): String {
        val component = clazz.componentType
        if (component != null) {
            return getClassName(component) + "[]"
        }
        return clazz.name
    }

    private fun getExecName(executable: Executable?): String {
        if (executable is Method) {
            return executable.name
        }
        assert(executable is Constructor<*>)
        return if (Modifier.isStatic(executable!!.modifiers)) "<clinit>" else "<init>"
    }

    private fun getReturnType(executable: Executable?): String {
        if (executable is Method) {
            return getClassName(executable.returnType)
        }
        assert(executable is Constructor<*>)
        return getClassName(Void.TYPE)
    }

    private fun printExecutable(executable: Executable?): String {
        val paramTypes =
            executable?.parameterTypes?.joinToString(separator = ", ") { getClassName(it) }

        return "${getExecName(executable)}(${paramTypes})${getReturnType(executable)}"
    }
}
