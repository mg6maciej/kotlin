/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.serialization.builtins

import org.jetbrains.kotlin.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.serialization.KotlinSerializerExtensionBase
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.types.KotlinType

class BuiltInsSerializerExtension : KotlinSerializerExtensionBase(BuiltInSerializerProtocol) {
    private val errorTypeRegex = "\\[ERROR : (.+)]".toRegex()

    private val shortNameToFullName = mapOf(
            "IntRange" to "kotlin/ranges/IntRange",
            "LongRange" to "kotlin/ranges/LongRange",
            "CharRange" to "kotlin/ranges/CharRange"
    )

    override fun shouldUseTypeTable(): Boolean = true

    override fun serializeErrorType(type: KotlinType, builder: ProtoBuf.Type.Builder) {
        val (name) = errorTypeRegex.matchEntire(type.toString())?.destructured ?: return super.serializeErrorType(type, builder)
        val fullName = shortNameToFullName[name] ?: return super.serializeErrorType(type, builder)
        builder.className = stringTable.getStringIndex(fullName)
    }
}
