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

package org.jetbrains.kotlin.kapt3

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.kapt3.diagnostic.ErrorsKapt3
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisCompletedHandlerExtension
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.FieldNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import java.io.File
import java.net.URLClassLoader
import java.util.*
import javax.annotation.processing.Processor

class Kapt3AnalysisCompletedHandlerExtension(
        val annotationProcessingClasspath: List<File>,
        val javaSourceRoots: List<File>,
        val sourcesOutputDir: File,
        val classesOutputDir: File,
        val options: Map<String, String>, //TODO
        val aptOnly: Boolean,
        val logger: Logger
) : AnalysisCompletedHandlerExtension {
    private var annotationProcessingComplete = false

    override fun analysisCompleted(
            project: Project,
            module: ModuleDescriptor,
            bindingTrace: BindingTrace,
            files: Collection<KtFile>
    ): AnalysisResult? {
        if (annotationProcessingComplete) {
            return null
        }

        fun doNotGenerateCode() = AnalysisResult.Companion.success(BindingContext.EMPTY, module, shouldGenerateCode = false)

        if (files.isEmpty()) {
            logger.info("No Kotlin source files, aborting")
            return if (aptOnly) doNotGenerateCode() else null
        }

        logger.info { "Kotlin files: " + files.map { it.virtualFile?.name ?: "<in memory ${it.hashCode()}>" } }

        val processors = loadProcessors(annotationProcessingClasspath)
        if (processors.isEmpty()) {
            logger.info("No annotation processors available, aborting")
            return if (aptOnly) doNotGenerateCode() else null
        }

        logger.info { "Annotation processors: " + processors.joinToString { it.javaClass.canonicalName } }

        val builderFactory = Kapt3BuilderFactory()

        val generationState = GenerationState(
                project,
                builderFactory,
                module,
                bindingTrace.bindingContext,
                files.toList(),
                disableCallAssertions = false,
                disableParamAssertions = false)

        try {
            KotlinCodegenFacade.compileCorrectFiles(generationState, CompilationErrorHandler.THROW_EXCEPTION)
            val compiledClasses = builderFactory.compiledClasses
            val origins = builderFactory.origins
            logger.info { "Compiled classes: " + compiledClasses.joinToString { it.name } }

            val javaFilesFromJavaSourceRoots = javaSourceRoots.flatMap {
                root -> root.walk().filter { it.isFile && it.extension == "java" }.toList()
            }
            logger.info { "Java source files: " + javaFilesFromJavaSourceRoots.joinToString { it.canonicalPath } }

            val kaptRunner = KaptRunner(logger)
            val treeConverter = JCTreeConverter(kaptRunner.context, generationState.typeMapper, compiledClasses, origins)
            val jcCompilationUnitsForKotlinClasses = treeConverter.convert()
            logger.info { "Stubs for Kotlin classes: " + jcCompilationUnitsForKotlinClasses.joinToString { it.sourcefile.name } }

            kaptRunner.doAnnotationProcessing(
                    javaFilesFromJavaSourceRoots, processors,
                    annotationProcessingClasspath, sourcesOutputDir, classesOutputDir, jcCompilationUnitsForKotlinClasses)
        } catch (thr: Throwable) {
            if (thr !is KaptError || thr.kind != KaptError.Kind.ERROR_RAISED) {
                logger.exception(thr)
            }
            bindingTrace.report(ErrorsKapt3.KAPT3_PROCESSING_ERROR.on(files.first()))
            return null // Compilation will be aborted anyway because of the error above
        } finally {
            generationState.destroy()
        }

        return if (aptOnly) {
            doNotGenerateCode()
        } else {
            AnalysisResult.RetryWithAdditionalJavaRoots(
                    bindingTrace.bindingContext,
                    module,
                    listOf(sourcesOutputDir),
                    addToEnvironment = true)
        }
    }

    private fun loadProcessors(classpath: List<File>): List<Processor> {
        val classLoader = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray())
        return ServiceLoader.load(Processor::class.java, classLoader).toList()
    }
}

class Kapt3BuilderFactory : ClassBuilderFactory {
    val compiledClasses = mutableListOf<ClassNode>()
    val origins = mutableMapOf<Any, JvmDeclarationOrigin>()

    override fun getClassBuilderMode(): ClassBuilderMode = ClassBuilderMode.KAPT3

    override fun newClassBuilder(origin: JvmDeclarationOrigin): AbstractClassBuilder.Concrete {
        val classNode = ClassNode()
        compiledClasses += classNode
        origins.put(classNode, origin)

        return object : AbstractClassBuilder.Concrete(classNode) {
            override fun newField(
                    origin: JvmDeclarationOrigin,
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    value: Any?
            ): FieldVisitor {
                val fieldNode = super.newField(origin, access, name, desc, signature, value) as FieldNode
                origins.put(fieldNode, origin)
                return fieldNode
            }

            override fun newMethod(
                    origin: JvmDeclarationOrigin,
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<out String>?
            ): MethodVisitor {
                val methodNode = super.newMethod(origin, access, name, desc, signature, exceptions) as MethodNode
                origins.put(methodNode, origin)
                return methodNode
            }
        }
    }

    override fun asText(builder: ClassBuilder) = throw UnsupportedOperationException()
    override fun asBytes(builder: ClassBuilder) = throw UnsupportedOperationException()
    override fun close() {}
}