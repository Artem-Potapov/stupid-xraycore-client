package com.justme.xtls_core_proxy.split

enum class SplitTunnelMode(val value: String) {
    ALLOW_ONLY("allow"),
    BLOCK_ALL_EXCEPT_SELECTED("block");

    companion object {
        fun fromValue(value: String?): SplitTunnelMode {
            return when (value) {
                ALLOW_ONLY.value -> ALLOW_ONLY
                else -> BLOCK_ALL_EXCEPT_SELECTED
            }
        }
    }
}
