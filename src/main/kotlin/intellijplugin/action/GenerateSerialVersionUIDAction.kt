package intellijplugin.action

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import intellijplugin.action.GenerateSerialVersionUIDHandler.Companion.hasUIDField
import intellijplugin.action.GenerateSerialVersionUIDHandler.Companion.needGenerateVersionUID
import intellijplugin.util.computeDefaultSUID
import org.jetbrains.kotlin.idea.internal.Location
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

class GenerateSerialVersionUIDAction : EditorAction(GenerateSerialVersionUIDHandler.Companion.INSTANCE) {
    override fun update(
        editor: Editor,
        presentation: Presentation,
        dataContext: DataContext
    ) {
        val project = PlatformDataKeys.PROJECT.getData(dataContext)
        var visible = false
        var enabled = false
        if (project != null) {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile is KtFile) {
                val location =
                    Location.fromEditor(editor, project)
                val psiElement = psiFile.findElementAt(location.startOffset)
                if (null != psiElement) {
                    val ktClass: KtClass? = GenerateSerialVersionUIDHandler.getKtClassWith(psiElement)

                    if (null != ktClass) {
                        val needGenerateVersionUID = needGenerateVersionUID(ktClass, computeDefaultSUID(ktClass))
                        visible = needGenerateVersionUID
                        enabled = needGenerateVersionUID
                    }
                }
            } else if (psiFile is PsiJavaFile) {
                val virtualFile = PlatformDataKeys.VIRTUAL_FILE.getData(dataContext)
                val psiManager = PsiManager.getInstance(project)
                val psiClass: PsiClass? = GenerateSerialVersionUIDHandler.getPsiClass(virtualFile, psiManager, editor)

                if (null != psiClass) {
                    visible = GenerateSerialVersionUIDHandler.Companion.needsUIDField(psiClass)
                    enabled = visible && !hasUIDField(psiClass)
                }
            }
        }
        presentation.isVisible = visible
        presentation.isEnabled = enabled
    }
}