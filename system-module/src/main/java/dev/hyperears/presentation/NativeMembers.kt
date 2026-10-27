package dev.hyperears.presentation

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Exact semantic symbols only; inherited members are resolved without signature guessing. */
internal object NativeMembers {
    private val fields = ConcurrentHashMap<Pair<Class<*>, String>, Field>()

    fun field(type: Class<*>, name: String): Field = fields.getOrPut(type to name) {
        generateSequence(type) { it.superclass }.firstNotNullOfOrNull {
            runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        } ?: throw NoSuchFieldException("${type.name}.$name")
    }

    fun get(owner: Any, name: String): Any? = field(owner.javaClass, name).get(owner)
    fun set(owner: Any, name: String, value: Any?) = field(owner.javaClass, name).set(owner, value)

    fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
        generateSequence(type) { it.superclass }.firstNotNullOfOrNull {
            runCatching { it.getDeclaredMethod(name, *args).apply { isAccessible = true } }.getOrNull()
        } ?: throw NoSuchMethodException("${type.name}.$name")

    fun call(owner: Any, name: String): Any? = method(owner.javaClass, name).invoke(owner)
}
