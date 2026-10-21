package com.aiaixyz.minemic

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform