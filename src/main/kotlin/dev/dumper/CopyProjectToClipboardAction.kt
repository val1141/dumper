package dev.dumper

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.util.Locale

class CopyProjectToClipboardAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private val defaultExclude = listOf(
        ".git/**",
        ".idea/**",
        ".vscode/**",
        ".gradle/**",
        "out/**",
        "build/**",
        "dist/**",
        "node_modules/**",
        "__pycache__/**",
        "output_files/**",
        "venv/**",
        ".venv/**"
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val basePath = project.basePath ?: return
        val root = LocalFileSystem.getInstance()
            .findFileByPath(basePath.replace('\\', '/')) ?: return

        val matchers = buildMatchers(root)

        val files = collectTextFiles(root, matchers)

        val sb = StringBuilder()
        for ((i, vf) in files.withIndex()) {
            val rel = VfsUtilCore.getRelativePath(vf, root, '/') ?: continue
            val text = runCatching { VfsUtilCore.loadText(vf) }.getOrElse { "" }

            sb.append("==== ").append(rel).append(" ====\n")
            sb.append(text)

            if (!text.endsWith("\n")) sb.append("\n")

            if (i != files.lastIndex) sb.append("\n")
        }

        CopyPasteManager.getInstance().setContents(StringSelection(sb.toString()))
    }

    private fun buildMatchers(root: VirtualFile): List<PathMatcher> {
        val ignorePatterns = mutableListOf<String>()
        ignorePatterns += defaultExclude
        ignorePatterns += readIgnoreFile(root, ".dumperignore")

        val fs = FileSystems.getDefault()
        return ignorePatterns
            .mapNotNull { normalizeIgnorePattern(it) }
            .distinct()
            .flatMap { pat ->
                val p1 = pat
                val p2 = pat.replace("/", java.io.File.separator)
                listOfNotNull(
                    runCatching { fs.getPathMatcher("glob:$p1") }.getOrNull(),
                    runCatching { fs.getPathMatcher("glob:$p2") }.getOrNull()
                )
            }
    }

    private fun readIgnoreFile(root: VirtualFile, fileName: String): List<String> {
        val ignore = root.findChild(fileName) ?: return emptyList()
        if (ignore.isDirectory || !ignore.isValid) return emptyList()

        val text = runCatching { VfsUtilCore.loadText(ignore) }.getOrElse { "" }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    private fun normalizeIgnorePattern(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s.startsWith("#")) return null

        s = s.replace("\\", "/")

        if (s.endsWith("/")) s += "**"

        val hasSlash = s.contains("/")
        val hasGlob = s.contains("*") || s.contains("?") || s.contains("[") || s.contains("{")
        if (!hasSlash && !hasGlob) {
            s = "**/$s"
        }

        return s
    }

    private fun isExcludedRel(rel: String, matchers: List<PathMatcher>): Boolean {
        val relNorm = rel.replace("\\", "/")
        val relOs = relNorm.replace("/", java.io.File.separator)

        val path1 = java.nio.file.Paths.get(relNorm)
        val path2 = java.nio.file.Paths.get(relOs)

        return matchers.any { m ->
            runCatching { m.matches(path1) }.getOrDefault(false) ||
                    runCatching { m.matches(path2) }.getOrDefault(false)
        }
    }

    private fun collectTextFiles(root: VirtualFile, matchers: List<PathMatcher>): List<VirtualFile> {
        val out = mutableListOf<VirtualFile>()

        val filter = com.intellij.openapi.vfs.VirtualFileFilter { vf ->
            if (!vf.isValid) return@VirtualFileFilter false

            if (vf == root) return@VirtualFileFilter true

            if (vf.isDirectory) {
                val relDir = VfsUtilCore.getRelativePath(vf, root, '/') ?: return@VirtualFileFilter true

                val excluded =
                    isExcludedRel(relDir, matchers) ||
                            isExcludedRel("$relDir/__dumper_dir__", matchers)

                return@VirtualFileFilter !excluded
            }

            true
        }

        VfsUtilCore.iterateChildrenRecursively(
            root,
            filter,
            { vf ->
                if (vf.isDirectory || !vf.isValid) {
                    return@iterateChildrenRecursively true
                }
                if (vf.fileType.isBinary) {
                    return@iterateChildrenRecursively true
                }

                val rel = VfsUtilCore.getRelativePath(vf, root, '/') ?: return@iterateChildrenRecursively true

                val excluded = isExcludedRel(rel, matchers)
                if (!excluded) {
                    out.add(vf)
                }

                true
            }
        )

        out.sortBy { (VfsUtilCore.getRelativePath(it, root, '/') ?: it.path).lowercase(Locale.ROOT) }
        return out
    }
}
