package org.frknkrc44.hma_oss.zygote

import android.util.Log
import com.v7878.unsafe.Reflection
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.EmulatedStackFrame.RETURN_VALUE_IDX
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import org.frknkrc44.hma_oss.common.BuildConfig
import org.frknkrc44.hma_oss.zygote.ZygoteEntry.TAG
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import java.util.regex.Pattern
import java.util.stream.Stream


class BulkHooker private constructor() {
    companion object {
        val instance: BulkHooker by lazy { BulkHooker() }
    }

    private val hooks: MutableMap<String, MutableList<HookElement>> =
        HashMap<String, MutableList<HookElement>>()

    fun findHookElement(clazz: String, methodName: String) = hooks[clazz]?.first {
        it.pattern == String.format("%s\\(.*\\).*", Pattern.quote(methodName))
    }

    private fun addPattern(clazz: String, pattern: String, hookFirst: Boolean, paramCount: Int, impl: HookTransformer) {
        hooks.computeIfAbsent(clazz) { _ -> mutableListOf() }
            .add(HookElement(
                impl = impl,
                pattern = pattern,
                hookFirst = hookFirst,
                paramCount = paramCount,
            ))
    }

    private fun addAll(clazz: String, methodName: String, hookFirst: Boolean, paramCount: Int, impl: HookTransformer) {
        addPattern(
            clazz,
            String.format("%s\\(.*\\).*", Pattern.quote(methodName)),
            hookFirst,
            paramCount,
            impl,
        )
    }

    internal fun hookBefore(
        clazz: String,
        methodName: String,
        dumpArgs: Boolean = true,
        autoApply: Boolean = true,
        hookFirst: Boolean = true,
        paramCount: Int = -1,
        hook: (param: HookParam) -> Unit,
    ) {
        addAll(clazz, methodName, hookFirst, paramCount) { original, frame ->
            val args = if (dumpArgs) Utils4Zygote.dumpArgs(frame) else null
            val value = ReturnValue()

            runCatching {
                hook(HookParam(clazz, original, frame, methodName, args, value))
            }.onFailure {
                logE(TAG, it.message ?: "Unknown error on hook", it)
            }

            if (!value.replace) {
                if (!dumpArgs) {
                    Transformers.invokeExact(original, frame)
                } else {
                    value.value = original.invokeWithArguments(*args!!)
                }
            }

            if (value.replace && frame.type().returnType() != Void::class.java) {
                frame.accessor().setValue(RETURN_VALUE_IDX, value.value)
            }
        }

        if (autoApply) {
            applyHooks()
        }
    }

    internal fun hookAfter(
        clazz: String,
        methodName: String,
        dumpArgs: Boolean = true,
        autoApply: Boolean = true,
        hookFirst: Boolean = true,
        paramCount: Int = -1,
        hook: (param: HookParam) -> Unit,
    ) {
        addAll(clazz, methodName, hookFirst, paramCount) { original, frame ->
            var throwable: Throwable? = null

            runCatching {
                Transformers.invokeExact(original, frame)
            }.onFailure {
                throwable = it
            }

            val args = if (dumpArgs) Utils4Zygote.dumpArgs(frame) else null
            val value = ReturnValue(if (throwable == null) {
                frame.accessor().getValue(RETURN_VALUE_IDX)
            } else null)
            value.throwable = throwable

            runCatching {
                hook(HookParam(clazz, original, frame, methodName, args, value))
            }.onFailure {
                logE(TAG, it.message ?: "Unknown error on hook", it)
            }

            if (throwable != null) {
                throw throwable
            }

            if (value.replace && frame.type().returnType() != Void::class.java) {
                frame.accessor().setValue(RETURN_VALUE_IDX, value.value)
            }
        }

        if (autoApply) {
            applyHooks()
        }
    }

    internal fun applyHooks(loader: ClassLoader? = SystemServerHook.classLoader) {
        for (entry in hooks.entries) {
            var curClazz: Class<*>?
            try {
                curClazz = Class.forName(entry.key, true, loader)
            } catch (ex: ClassNotFoundException) {
                Log.e(TAG, java.lang.String.format("Class %s not found", entry.key), ex)
                continue
            }

            fun apply(clazz: Class<*>?) {
                val executables = Reflection.getHiddenExecutables(clazz).sortedWith { v1, v2 ->
                    v1.parameterCount.compareTo(v2.parameterCount)
                }.toTypedArray()
                for (element in entry.value) {
                    Stream.of<Executable>(*executables)
                        .filter(Utils4Zygote.filter(element.pattern))
                        .forEach { executable: Executable ->
                            if (element.paramCount != -1 && executable.parameterCount != element.paramCount) {
                                return@forEach
                            }

                            if (!element.hookFirst || element.applyCount < 1) {
                                if (BuildConfig.DEBUG) {
                                    logI(TAG, "Hooked: $executable")
                                }
                                val invoker = Hooks.hook(
                                    executable, Hooks.EntryPointType.DIRECT,
                                    element.impl, Hooks.EntryPointType.DIRECT
                                )

                                element.unhooks.add(Unhook(executable, invoker))
                                element.applyCount++
                            }
                        }
                }
            }

            while (
                curClazz != null &&
                entry.value.any { it.applyCount < 1 } &&
                curClazz.javaClass.simpleName != "Object"
            ) {
                apply(curClazz)
                curClazz = curClazz.superclass
            }

            // remove invalid entries
            entry.value.removeIf {
                (it.applyCount < 1).apply {
                    if (this@apply) {
                        logI(TAG, "Invalid hook removed: ${it.pattern}")
                    }
                }
            }
        }
    }

    fun unhook(element: HookElement) {
        for (unhook in element.unhooks) {
            unhook.unhook()
        }

        element.unhooks.clear()
    }

    fun unhookAll() {
        // TODO: Not working yet, find a way to implement unhook properly
        /*
        for (entry in hooks.entries) {
            for (element in entry.value) {
                unhook(element)
            }
        }

        hooks.clear()
         */
    }

    data class HookElement(
        val impl: HookTransformer,
        val unhooks: MutableList<Unhook> = mutableListOf(),
        val pattern: String,
        val hookFirst: Boolean,
        val paramCount: Int = -1,
        var applyCount: Int = 0,
    )

    class Unhook(private val backup: Executable, private val invoker: Executable) {
        fun unhook() {
            // TODO: Not working yet, find a way to implement unhook properly
            Hooks.hookSwap(
                backup,
                Hooks.EntryPointType.DIRECT,
                invoker,
                Hooks.EntryPointType.DIRECT,
            )
        }
    }

    class ReturnValue(initialValue: Any? = null) {
        var replace: Boolean = false
            private set

        var value: Any? = initialValue
            set(value) {
                field = value
                replace = true
            }

        var throwable: Throwable? = null
    }

    data class HookParam(
        val clazz: String,
        val original: MethodHandle,
        val frame: EmulatedStackFrame,
        val methodName: String,

        /**
         * - `args[0] == thisObject`
         * - `args[1:] == function args`
         *
         * Note that this variable is null when `dumpArgs == false`
         */
        val args: Array<Any?>?,
        val returnValue: ReturnValue,
    ) {
        var result: Any?
            get() = returnValue.value
            set(newValue) { returnValue.value = newValue }

        /**
         * Returns the first argument, can throw an exception when `dumpArgs == false`
         */
        val thisObject get() = args?.first()!!

        var throwable: Throwable?
            get() = returnValue.throwable
            set(newValue) { returnValue.throwable = newValue }

        // -- BEGIN OF GENERATED FUNCTIONS --
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as HookParam

            if (clazz != other.clazz) return false
            if (original != other.original) return false
            if (frame != other.frame) return false
            if (methodName != other.methodName) return false
            if (!args.contentEquals(other.args)) return false
            if (returnValue != other.returnValue) return false

            return true
        }

        override fun hashCode(): Int {
            var result1 = clazz.hashCode()
            result1 = 31 * result1 + original.hashCode()
            result1 = 31 * result1 + frame.hashCode()
            result1 = 31 * result1 + methodName.hashCode()
            result1 = 31 * result1 + (args?.contentHashCode() ?: 0)
            result1 = 31 * result1 + returnValue.hashCode()
            return result1
        }
        // -- END OF GENERATED FUNCTIONS --
    }
}
