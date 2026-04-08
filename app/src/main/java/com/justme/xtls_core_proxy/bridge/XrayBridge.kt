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
        return startXray(configJson, tunFd, "")
    }

    fun startXray(configJson: String, tunFd: Int, assetDir: String): Result<Unit> {
        return runCatching {
            val clazz = bridgeClass()
            val method = findStartMethod(clazz)
            val response = invokeStart(method, configJson, tunFd, assetDir)
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

    private fun findStartMethod(clazz: Class<*>): Method {
        val names = listOf("StartXray", "startXray")
        val candidates = clazz.methods
            .filter { method -> names.any { it.equals(method.name, ignoreCase = true) } }
            .sortedByDescending { it.parameterTypes.size }

        return candidates.firstOrNull { method ->
            val params = method.parameterTypes
            when (params.size) {
                3 -> {
                    params[0] == String::class.java &&
                        isFdType(params[1]) &&
                        params[2] == String::class.java
                }
                2 -> {
                    params[0] == String::class.java &&
                        isFdType(params[1])
                }
                else -> false
            }
        } ?: throw IllegalStateException("StartXray method not found in ${clazz.name}")
    }

    private fun isFdType(type: Class<*>): Boolean {
        return type == Int::class.javaPrimitiveType ||
            type == java.lang.Integer::class.java ||
            type == Long::class.javaPrimitiveType ||
            type == java.lang.Long::class.java
    }

    private fun invokeStart(method: Method, configJson: String, tunFd: Int, assetDir: String): String {
        val params = method.parameterTypes
        return when {
            params.size == 3 &&
                params[0] == String::class.java &&
                params[1] == Int::class.javaPrimitiveType &&
                params[2] == String::class.java -> {
                method.invoke(null, configJson, tunFd, assetDir) as? String ?: ""
            }
            params.size == 3 &&
                params[0] == String::class.java &&
                params[1] == java.lang.Integer::class.java &&
                params[2] == String::class.java -> {
                method.invoke(null, configJson, tunFd, assetDir) as? String ?: ""
            }
            params.size == 3 &&
                params[0] == String::class.java &&
                params[1] == Long::class.javaPrimitiveType &&
                params[2] == String::class.java -> {
                method.invoke(null, configJson, tunFd.toLong(), assetDir) as? String ?: ""
            }
            params.size == 3 &&
                params[0] == String::class.java &&
                params[1] == java.lang.Long::class.java &&
                params[2] == String::class.java -> {
                method.invoke(null, configJson, tunFd.toLong(), assetDir) as? String ?: ""
            }
            params.size == 2 && params[0] == String::class.java && params[1] == Int::class.javaPrimitiveType -> {
                method.invoke(null, configJson, tunFd) as? String ?: ""
            }
            params.size == 2 && params[0] == String::class.java && params[1] == java.lang.Integer::class.java -> {
                method.invoke(null, configJson, tunFd) as? String ?: ""
            }
            params.size == 2 && params[0] == String::class.java && params[1] == Long::class.javaPrimitiveType -> {
                method.invoke(null, configJson, tunFd.toLong()) as? String ?: ""
            }
            params.size == 2 && params[0] == String::class.java && params[1] == java.lang.Long::class.java -> {
                method.invoke(null, configJson, tunFd.toLong()) as? String ?: ""
            }
            else -> throw IllegalStateException("Unsupported StartXray signature in bridge class")
        }
    }
}
