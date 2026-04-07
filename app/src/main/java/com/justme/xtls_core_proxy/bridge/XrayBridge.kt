package com.justme.xtls_core_proxy.bridge

import java.lang.reflect.Method

object XrayBridge {
    private val classNames = listOf(
        "xraybridge.Xraybridge",
        "go.xraybridge.Xraybridge",
        "xraybridge.XrayBridge",
        "go.xraybridge.XrayBridge"
    )

    fun startXray(configJson: String, tunFd: Int): Result<Unit> {
        return runCatching {
            val clazz = bridgeClass()
            val method = findMethod(clazz, listOf("StartXray", "startXray"), 2)
            val response = invokeStart(method, configJson, tunFd)
            if (response.isNotBlank()) {
                throw IllegalStateException(response)
            }
        }
    }

    fun stopXray(): Result<Unit> {
        return runCatching {
            val clazz = bridgeClass()
            val method = findMethod(clazz, listOf("StopXray", "stopXray"), 0)
            val response = method.invoke(null)
            if (response is String && response.isNotBlank()) {
                throw IllegalStateException(response)
            }
        }
    }

    private fun bridgeClass(): Class<*> {
        for (name in classNames) {
            try {
                return Class.forName(name)
            } catch (_: ClassNotFoundException) {
                // Try next candidate
            }
        }
        throw IllegalStateException(
            "Xray bridge class not found. Generate app/libs/xray.aar with scripts/build-xray-aar.ps1 first."
        )
    }

    private fun findMethod(clazz: Class<*>, names: List<String>, parameterCount: Int): Method {
        val methods = clazz.methods.filter { method ->
            names.any { it.equals(method.name, ignoreCase = true) } &&
                method.parameterTypes.size == parameterCount
        }
        return methods.firstOrNull()
            ?: throw IllegalStateException("Method ${names.first()} not found in ${clazz.name}")
    }

    private fun invokeStart(method: Method, configJson: String, tunFd: Int): String {
        val params = method.parameterTypes
        return when {
            params[0] == String::class.java && params[1] == Int::class.javaPrimitiveType -> {
                method.invoke(null, configJson, tunFd) as? String ?: ""
            }
            params[0] == String::class.java && params[1] == Int -> {
                method.invoke(null, configJson, tunFd) as? String ?: ""
            }
            params[0] == String::class.java && params[1] == Long::class.javaPrimitiveType -> {
                method.invoke(null, configJson, tunFd.toLong()) as? String ?: ""
            }
            params[0] == String::class.java && params[1] == Long -> {
                method.invoke(null, configJson, tunFd.toLong()) as? String ?: ""
            }
            else -> throw IllegalStateException("Unsupported StartXray signature in bridge class")
        }
    }
}
