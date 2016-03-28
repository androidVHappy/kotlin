package org.jetbrains.kotlin.gradle

import org.jetbrains.kotlin.gradle.util.getFileByName
import org.jetbrains.kotlin.gradle.util.modify
import org.junit.Test
import java.io.File
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CacheVersionChangedIT : BaseGradleIT() {
    @Test
    fun testGradleCacheVersionChanged() {
        compileIncrementallyWithChangedVersion("gradle-format-version.txt")
    }

    @Test
    fun testNormalCacheVersionChanged() {
        compileIncrementallyWithChangedVersion("format-version.txt")
    }

    @Test
    fun testExperimentalCacheVersionChanged() {
        compileIncrementallyWithChangedVersion("experimental-format-version.txt")
    }

    @Test
    fun testDataContainerCacheVersionChanged() {
        compileIncrementallyWithChangedVersion("data-container-format-version.txt")
    }

    private fun compileIncrementallyWithChangedVersion(versionFileName: String) {
        val project = Project("kotlinProject", "2.10")
        val options = defaultBuildOptions().copy(incremental = true)

        fun File.projectPath() = relativeTo(project.projectDir).path

        project.build("build", options = options) {
            assertSuccessful()
        }

        val versionFile = File(project.projectDir, "build/kotlin/compileKotlin/$versionFileName")
        assertTrue(versionFile.exists(), "${versionFile.projectPath()} does not exist!")
        val modifiedVersion = "777"
        versionFile.writeText(modifiedVersion)

        // gradle won't call kotlin task if no file is changed
        val dummyKtFile = project.projectDir.getFileByName("Dummy.kt")
        dummyKtFile.modify { it + " " }

        project.build("build", options = options) {
            assertSuccessful()
            assertNotEquals(modifiedVersion, versionFile.readText(), "${versionFile.projectPath()} was not rewritten by build")

            val mainDir = File(project.projectDir, "src/main")
            val mainKotlinFiles = mainDir.walk().filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }
            val mainKotlinRelativePaths = mainKotlinFiles.map { it.projectPath() }.toList()
            assertCompiledKotlinSources(mainKotlinRelativePaths)
        }

        dummyKtFile.modify { it + " " }

        project.build("build", options = options) {
            assertSuccessful()
            assertCompiledKotlinSources(listOf(dummyKtFile.projectPath()))
        }
    }
}