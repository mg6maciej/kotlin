/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.js.config;

import org.jetbrains.kotlin.config.CompilerConfigurationKey;
import org.jetbrains.kotlin.serialization.js.ModuleKind;

import java.util.List;

public class JSConfigurationKeys {
    public static final CompilerConfigurationKey<List<String>> LIBRARIES =
            CompilerConfigurationKey.create("library file paths");

    public static final CompilerConfigurationKey<Boolean> SOURCE_MAP =
            CompilerConfigurationKey.create("generate source map");

    public static final CompilerConfigurationKey<Boolean> META_INFO =
            CompilerConfigurationKey.create("generate .meta.js and .kjsm files");

    public static final CompilerConfigurationKey<EcmaVersion> TARGET =
            CompilerConfigurationKey.create("ECMA version target");

    public static final CompilerConfigurationKey<ModuleKind> MODULE_KIND =
            CompilerConfigurationKey.create("module kind");

    public static final CompilerConfigurationKey<Boolean> TYPED_ARRAYS_ENABLED =
            CompilerConfigurationKey.create("TypedArrays enabled");
}
