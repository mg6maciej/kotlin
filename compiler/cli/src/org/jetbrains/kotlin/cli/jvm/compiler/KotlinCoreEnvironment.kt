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

package org.jetbrains.kotlin.cli.jvm.compiler

import com.google.common.collect.Sets
import com.intellij.codeInsight.ContainerProvider
import com.intellij.codeInsight.ExternalAnnotationsManager
import com.intellij.codeInsight.InferredAnnotationsManager
import com.intellij.codeInsight.runner.JavaMainMethodProvider
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.core.CoreJavaFileManager
import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.lang.MetaLanguage
import com.intellij.lang.java.JavaParserDefinition
import com.intellij.mock.MockApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.TransactionGuardImpl
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.fileTypes.FileTypeExtensionPoint
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.PersistentFSConstants
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.impl.ZipHandler
import com.intellij.psi.FileContextProvider
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.augment.TypeAnnotationModifier
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.impl.JavaClassSupersImpl
import com.intellij.psi.impl.PsiElementFinderImpl
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.meta.MetaDataContributor
import com.intellij.psi.stubs.BinaryFileStubBuilders
import com.intellij.psi.util.JavaClassSupers
import com.intellij.util.io.URLUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl
import org.jetbrains.kotlin.cli.common.KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.ERROR
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.STRONG_WARNING
import org.jetbrains.kotlin.cli.common.toBooleanLenient
import org.jetbrains.kotlin.cli.jvm.JvmRuntimeVersionsConsistencyChecker
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndex
import org.jetbrains.kotlin.cli.jvm.index.JvmUpdateableDependenciesIndexFactory
import org.jetbrains.kotlin.codegen.extensions.ClassBuilderInterceptorExtension
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.extensions.DeclarationAttributeAltererExtension
import org.jetbrains.kotlin.extensions.PreprocessedVirtualFileFactoryExtension
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.isValidJavaFqName
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.CodeAnalyzerInitializer
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension
import org.jetbrains.kotlin.resolve.lazy.declarations.CliDeclarationProviderFactoryService
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactoryService
import org.jetbrains.kotlin.script.KotlinScriptDefinitionProvider
import org.jetbrains.kotlin.script.KotlinScriptExternalImportsProvider
import org.jetbrains.kotlin.script.KotlinScriptExternalImportsProviderImpl
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File

class KotlinCoreEnvironment private constructor(
        parentDisposable: Disposable,
        applicationEnvironment: JavaCoreApplicationEnvironment,
        configuration: CompilerConfiguration,
        configFiles: EnvironmentConfigFiles
) {

    private val projectEnvironment: JavaCoreProjectEnvironment = object : KotlinCoreProjectEnvironment(parentDisposable, applicationEnvironment) {
        override fun preregisterServices() {
            registerProjectExtensionPoints(Extensions.getArea(project))
        }

        override fun registerJavaPsiFacade() {
            with (project) {
                registerService(CoreJavaFileManager::class.java, ServiceManager.getService(this, JavaFileManager::class.java) as CoreJavaFileManager)

                val cliLightClassGenerationSupport = CliLightClassGenerationSupport(this)
                registerService(LightClassGenerationSupport::class.java, cliLightClassGenerationSupport)
                registerService(CliLightClassGenerationSupport::class.java, cliLightClassGenerationSupport)
                registerService(CodeAnalyzerInitializer::class.java, cliLightClassGenerationSupport)

                registerService(ExternalAnnotationsManager::class.java, MockExternalAnnotationsManager())
                registerService(InferredAnnotationsManager::class.java, MockInferredAnnotationsManager())

                val area = Extensions.getArea(this)

                area.getExtensionPoint(PsiElementFinder.EP_NAME).registerExtension(JavaElementFinder(this, cliLightClassGenerationSupport))
                area.getExtensionPoint(PsiElementFinder.EP_NAME).registerExtension(
                        PsiElementFinderImpl(this, ServiceManager.getService(this, JavaFileManager::class.java)))
            }

            super.registerJavaPsiFacade()
        }
    }
    private val sourceFiles = mutableListOf<KtFile>()
    private val rootsIndex: JvmDependenciesDynamicCompoundIndex

    val configuration: CompilerConfiguration = configuration.copy()

    init {
        PersistentFSConstants.setMaxIntellisenseFileSize(FileUtilRt.LARGE_FOR_CONTENT_LOADING)
    }

    init {
        val project = projectEnvironment.project

        ExpressionCodegenExtension.registerExtensionPoint(project)
        SyntheticResolveExtension.registerExtensionPoint(project)
        ClassBuilderInterceptorExtension.registerExtensionPoint(project)
        AnalysisHandlerExtension.registerExtensionPoint(project)
        PackageFragmentProviderExtension.registerExtensionPoint(project)
        StorageComponentContainerContributor.registerExtensionPoint(project)
        DeclarationAttributeAltererExtension.registerExtensionPoint(project)
        PreprocessedVirtualFileFactoryExtension.registerExtensionPoint(project)

        for (registrar in configuration.getList(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS)) {
            registrar.registerProjectComponents(project, configuration)
        }

        project.registerService(DeclarationProviderFactoryService::class.java, CliDeclarationProviderFactoryService(sourceFiles))
        project.registerService(ModuleVisibilityManager::class.java, CliModuleVisibilityManagerImpl(configFiles == EnvironmentConfigFiles.JVM_CONFIG_FILES))

        registerProjectServicesForCLI(projectEnvironment)
        registerProjectServices(projectEnvironment)

        sourceFiles += CompileEnvironmentUtil.getKtFiles(project, getSourceRootsCheckingForDuplicates(), this.configuration, {
            message ->
            report(ERROR, message)
        })
        sourceFiles.sortBy { it.virtualFile.path }

        KotlinScriptDefinitionProvider.getInstance(project)?.let { scriptDefinitionProvider ->
            scriptDefinitionProvider.setScriptDefinitions(
                    configuration.getList(JVMConfigurationKeys.SCRIPT_DEFINITIONS))

            KotlinScriptExternalImportsProvider.getInstance(project)?.run {
                configuration.addJvmClasspathRoots(
                        getCombinedClasspathFor(sourceFiles)
                                .distinctBy { it.absolutePath })
            }
        }

        val initialRoots = configuration.getList(JVMConfigurationKeys.CONTENT_ROOTS).classpathRoots()

        if (!configuration.getBoolean(JVMConfigurationKeys.SKIP_RUNTIME_VERSION_CHECK)) {
            val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)
            if (messageCollector != null) {
                JvmRuntimeVersionsConsistencyChecker.checkCompilerClasspathConsistency(
                        messageCollector,
                        configuration,
                        initialRoots.mapNotNull { (file, type) -> if (type == JavaRoot.RootType.BINARY) file else null }
                )
            }
        }

        // REPL and kapt2 update classpath dynamically
        val indexFactory = JvmUpdateableDependenciesIndexFactory()

        rootsIndex = indexFactory.makeIndexFor(initialRoots)
        updateClasspathFromRootsIndex(rootsIndex)

        (ServiceManager.getService(project, CoreJavaFileManager::class.java) as KotlinCliJavaFileManagerImpl)
                .initialize(rootsIndex, configuration.getBoolean(JVMConfigurationKeys.USE_FAST_CLASS_FILES_READING))

        val finderFactory = CliVirtualFileFinderFactory(rootsIndex)
        project.registerService(MetadataFinderFactory::class.java, finderFactory)
        project.registerService(VirtualFileFinderFactory::class.java, finderFactory)
    }

    private val applicationEnvironment: CoreApplicationEnvironment
        get() = projectEnvironment.environment

    val application: MockApplication
        get() = applicationEnvironment.application

    val project: Project
        get() = projectEnvironment.project

    val sourceLinesOfCode: Int by lazy { countLinesOfCode(sourceFiles) }

    fun countLinesOfCode(sourceFiles: List<KtFile>): Int  =
            sourceFiles.sumBy {
                val text = it.text
                StringUtil.getLineBreakCount(it.text) + (if (StringUtil.endsWithLineBreak(text)) 0 else 1)
            }

    private fun Iterable<ContentRoot>.classpathRoots(): List<JavaRoot> =
            filterIsInstance(JvmContentRoot::class.java).mapNotNull { javaRoot ->
                contentRootToVirtualFile(javaRoot)?.let { virtualFile ->
                    val prefixPackageFqName = (javaRoot as? JavaSourceRoot)?.packagePrefix?.let {
                        if (isValidJavaFqName(it)) {
                            FqName(it)
                        }
                        else {
                            report(STRONG_WARNING, "Invalid package prefix name is ignored: $it")
                            null
                        }
                    }

                    val rootType = when (javaRoot) {
                        is JavaSourceRoot -> JavaRoot.RootType.SOURCE
                        is JvmClasspathRoot -> JavaRoot.RootType.BINARY
                        else -> throw IllegalStateException()
                    }

                    JavaRoot(virtualFile, rootType, prefixPackageFqName)
                }
            }

    private fun updateClasspathFromRootsIndex(index: JvmDependenciesIndex) {
        index.indexedRoots.forEach {
            projectEnvironment.addSourcesToClasspath(it.file)
        }
    }

    val appendJavaSourceRootsHandler = fun(roots: List<File>) {
        updateClasspath(roots.map { JavaSourceRoot(it, null) })
    }

    init {
        project.putUserData(APPEND_JAVA_SOURCE_ROOTS_HANDLER_KEY, appendJavaSourceRootsHandler)
    }

    fun updateClasspath(roots: List<ContentRoot>): List<File>? {
        return rootsIndex.addNewIndexForRoots(roots.classpathRoots())?.let {
            updateClasspathFromRootsIndex(it)
            it.indexedRoots.mapNotNull { File(it.file.path.substringBefore(URLUtil.JAR_SEPARATOR)) }.toList()
        } ?: emptyList()
    }

    @Suppress("unused") // used externally
    @Deprecated("Use updateClasspath() instead.", ReplaceWith("updateClasspath(files)"))
    fun tryUpdateClasspath(files: Iterable<File>): List<File>? = updateClasspath(files.map(::JvmClasspathRoot))

    fun contentRootToVirtualFile(root: JvmContentRoot): VirtualFile? {
        when (root) {
            is JvmClasspathRoot -> {
                return if (root.file.isFile) findJarRoot(root) else findLocalDirectory(root)
            }
            is JavaSourceRoot -> {
                return if (root.file.isDirectory) findLocalDirectory(root) else null
            }
            else -> throw IllegalStateException("Unexpected root: $root")
        }
    }

    private fun findLocalDirectory(root: JvmContentRoot): VirtualFile? {
        val path = root.file
        val localFile = findLocalDirectory(path.absolutePath)
        if (localFile == null) {
            report(STRONG_WARNING, "Classpath entry points to a non-existent location: $path")
            return null
        }
        return localFile
    }

    internal fun findLocalDirectory(absolutePath: String): VirtualFile? =
            applicationEnvironment.localFileSystem.findFileByPath(absolutePath)

    private fun findJarRoot(root: JvmClasspathRoot): VirtualFile? =
            applicationEnvironment.jarFileSystem.findFileByPath("${root.file}${URLUtil.JAR_SEPARATOR}")

    private fun getSourceRootsCheckingForDuplicates(): Collection<String> {
        val uniqueSourceRoots = Sets.newLinkedHashSet<String>()

        configuration.kotlinSourceRoots.forEach { path ->
            if (!uniqueSourceRoots.add(path)) {
                report(STRONG_WARNING, "Duplicate source root: $path")
            }
        }

        return uniqueSourceRoots
    }

    fun getSourceFiles(): List<KtFile> = sourceFiles

    private fun report(severity: CompilerMessageSeverity, message: String) {
        configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY).report(severity, message)
    }

    companion object {
        init {
            setCompatibleBuild()
        }

        private val APPLICATION_LOCK = Object()
        private var ourApplicationEnvironment: JavaCoreApplicationEnvironment? = null
        private var ourProjectCount = 0

        @JvmStatic fun createForProduction(
                parentDisposable: Disposable, configuration: CompilerConfiguration, configFiles: EnvironmentConfigFiles
        ): KotlinCoreEnvironment {
            setCompatibleBuild()
            val appEnv = getOrCreateApplicationEnvironmentForProduction(configuration, configFiles.files)
            // Disposing of the environment is unsafe in production then parallel builds are enabled, but turning it off universally
            // breaks a lot of tests, therefore it is disabled for production and enabled for tests
            if (!(System.getProperty(KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY).toBooleanLenient() ?: false)) {
                // JPS may run many instances of the compiler in parallel (there's an option for compiling independent modules in parallel in IntelliJ)
                // All projects share the same ApplicationEnvironment, and when the last project is disposed, the ApplicationEnvironment is disposed as well
                Disposer.register(parentDisposable, Disposable {
                    synchronized (APPLICATION_LOCK) {
                        if (--ourProjectCount <= 0) {
                            disposeApplicationEnvironment()
                        }
                    }
                })
            }
            val environment = KotlinCoreEnvironment(parentDisposable, appEnv, configuration, configFiles)

            synchronized (APPLICATION_LOCK) {
                ourProjectCount++
            }
            return environment
        }

        @JvmStatic
        private fun setCompatibleBuild() {
            System.getProperties().setProperty("idea.plugins.compatible.build", "171.9999")
        }

        @TestOnly
        @JvmStatic fun createForTests(
                parentDisposable: Disposable, configuration: CompilerConfiguration, extensionConfigs: EnvironmentConfigFiles
        ): KotlinCoreEnvironment {
            // Tests are supposed to create a single project and dispose it right after use
            return KotlinCoreEnvironment(parentDisposable,
                                         createApplicationEnvironment(parentDisposable, configuration, extensionConfigs.files),
                                         configuration,
                                         extensionConfigs)
        }

        // used in the daemon for jar cache cleanup
        val applicationEnvironment: JavaCoreApplicationEnvironment? get() = ourApplicationEnvironment

        private fun getOrCreateApplicationEnvironmentForProduction(configuration: CompilerConfiguration, configFilePaths: List<String>): JavaCoreApplicationEnvironment {
            synchronized (APPLICATION_LOCK) {
                if (ourApplicationEnvironment != null)
                    return ourApplicationEnvironment!!

                val parentDisposable = Disposer.newDisposable()
                ourApplicationEnvironment = createApplicationEnvironment(parentDisposable, configuration, configFilePaths)
                ourProjectCount = 0
                Disposer.register(parentDisposable, Disposable {
                    synchronized (APPLICATION_LOCK) {
                        ourApplicationEnvironment = null
                    }
                })
                return ourApplicationEnvironment!!
            }
        }

        fun disposeApplicationEnvironment() {
            synchronized (APPLICATION_LOCK) {
                val environment = ourApplicationEnvironment ?: return
                ourApplicationEnvironment = null
                Disposer.dispose(environment.parentDisposable)
                ZipHandler.clearFileAccessorCache()
            }
        }

        private fun createApplicationEnvironment(parentDisposable: Disposable, configuration: CompilerConfiguration, configFilePaths: List<String>): JavaCoreApplicationEnvironment {
            Extensions.cleanRootArea(parentDisposable)
            registerAppExtensionPoints()
            val applicationEnvironment = JavaCoreApplicationEnvironment(parentDisposable)

            for (configPath in configFilePaths) {
                registerApplicationExtensionPointsAndExtensionsFrom(configuration, configPath)
            }

            registerApplicationServicesForCLI(applicationEnvironment)
            registerApplicationServices(applicationEnvironment)

            return applicationEnvironment
        }

        private fun registerAppExtensionPoints() {
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), BinaryFileStubBuilders.EP_NAME, FileTypeExtensionPoint::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), FileContextProvider.EP_NAME, FileContextProvider::class.java)
            //
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), MetaDataContributor.EP_NAME, MetaDataContributor::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), PsiAugmentProvider.EP_NAME, PsiAugmentProvider::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), JavaMainMethodProvider.EP_NAME, JavaMainMethodProvider::class.java)
            //
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ContainerProvider.EP_NAME, ContainerProvider::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ClsCustomNavigationPolicy.EP_NAME, ClsCustomNavigationPolicy::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ClassFileDecompilers.EP_NAME, ClassFileDecompilers.Decompiler::class.java)
            //
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), TypeAnnotationModifier.EP_NAME, TypeAnnotationModifier::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), MetaLanguage.EP_NAME, MetaLanguage::class.java)
        }

        private fun registerApplicationExtensionPointsAndExtensionsFrom(configuration: CompilerConfiguration, configFilePath: String) {
            var pluginRoot =
                    configuration.get(CLIConfigurationKeys.INTELLIJ_PLUGIN_ROOT)?.let(::File)
                    ?: configuration.get(CLIConfigurationKeys.COMPILER_JAR_LOCATOR)?.compilerJar
                    ?: PathUtil.getPathUtilJar()

            val app = ApplicationManager.getApplication()
            val parentFile = pluginRoot.parentFile

            if (pluginRoot.isDirectory && app != null && app.isUnitTestMode
                && FileUtil.toCanonicalPath(parentFile.path).endsWith("out/production")) {
                // hack for load extensions when compiler run directly from out directory(e.g. in tests)
                val srcDir = parentFile.parentFile.parentFile
                pluginRoot = File(srcDir, "idea/src")
            }

            CoreApplicationEnvironment.registerExtensionPointAndExtensions(pluginRoot, configFilePath, Extensions.getRootArea())
        }

        private fun registerApplicationServicesForCLI(applicationEnvironment: JavaCoreApplicationEnvironment) {
            // ability to get text from annotations xml files
            applicationEnvironment.registerFileType(PlainTextFileType.INSTANCE, "xml")
            applicationEnvironment.registerParserDefinition(JavaParserDefinition())
        }

        // made public for Upsource
        @JvmStatic fun registerApplicationServices(applicationEnvironment: JavaCoreApplicationEnvironment) {
            with(applicationEnvironment) {
                registerFileType(KotlinFileType.INSTANCE, "kt")
                registerFileType(KotlinFileType.INSTANCE, KotlinParserDefinition.STD_SCRIPT_SUFFIX)
                registerParserDefinition(KotlinParserDefinition())
                application.registerService(KotlinBinaryClassCache::class.java, KotlinBinaryClassCache())
                application.registerService(JavaClassSupers::class.java, JavaClassSupersImpl::class.java)
                application.registerService(TransactionGuard::class.java, TransactionGuardImpl::class.java)
            }
        }

        private fun registerProjectExtensionPoints(area: ExtensionsArea) {
            CoreApplicationEnvironment.registerExtensionPoint(area, PsiTreeChangePreprocessor.EP_NAME, PsiTreeChangePreprocessor::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(area, PsiElementFinder.EP_NAME, PsiElementFinder::class.java)
        }

        // made public for Upsource
        @JvmStatic fun registerProjectServices(projectEnvironment: JavaCoreProjectEnvironment) {
            with (projectEnvironment.project) {
                val kotlinScriptDefinitionProvider = KotlinScriptDefinitionProvider()
                registerService(KotlinScriptDefinitionProvider::class.java, kotlinScriptDefinitionProvider)
                registerService(KotlinScriptExternalImportsProvider::class.java, KotlinScriptExternalImportsProviderImpl(projectEnvironment.project, kotlinScriptDefinitionProvider))
                registerService(KotlinJavaPsiFacade::class.java, KotlinJavaPsiFacade(this))
                registerService(KtLightClassForFacade.FacadeStubCache::class.java, KtLightClassForFacade.FacadeStubCache(this))
            }
        }

        private fun registerProjectServicesForCLI(projectEnvironment: JavaCoreProjectEnvironment) {
            /**
             * Note that Kapt may restart code analysis process, and CLI services should be aware of that.
             * Use PsiManager.getModificationTracker() to ensure that all the data you cached is still valid.
             */

        }
    }
}
