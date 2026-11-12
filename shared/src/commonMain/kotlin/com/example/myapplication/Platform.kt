// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform