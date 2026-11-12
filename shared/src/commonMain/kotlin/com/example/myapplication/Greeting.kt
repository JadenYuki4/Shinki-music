// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

class Greeting {
    private val platform = getPlatform()

    fun greet(): String {
        return sayHello(platform.name)
    }
}