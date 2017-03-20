/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.js.inline.clean

import org.jetbrains.kotlin.js.backend.ast.*

class LabeledBlockToDoWhileTransformation(private val root: JsNode) {
    private var hasChanges = false

    fun apply(): Boolean {
        perform()
        return hasChanges
    }

    private fun perform() {
        object : JsVisitorWithContextImpl() {
            override fun endVisit(x: JsLabel, ctx: JsContext<JsNode>) {
                //synthetic?
                if (x.statement is JsBlock) {
                    hasChanges = true
                    x.statement = JsDoWhile(JsLiteral.FALSE, x.statement)
                }

                super.endVisit(x, ctx)
            }
        }.accept(root)
    }
}
