package com.youngfeng.ideaplugin.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil.findChildOfType
import com.youngfeng.ideaplugin.hash.computeHashCode
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtPsiFactory

private const val MODIFIERS = "private const"
private const val SERIAL_VERSION_UID = "serialVersionUID"

fun PsiFile.generatePsiTreeElements() {
    findChild<KtClass>()?.let { ktClass ->
        with(psiFactory(project)) {
            getOrCreateKtClassBody(ktClass).run {
                (findCompanionObjectElement() ?: ktClass.addDeclaration(createCompanionObject()))
                    .addDeclaration(generateSerialVersionUidField(computeHashCode(ktClass, this)))
            }
        }
    }
}

private fun psiFactory(project: Project) = KtPsiFactory(project, false)

private fun KtPsiFactory.getOrCreateKtClassBody(ktClass: KtClass) = ktClass.findChild() ?: createEmptyClassBody()

private fun KtPsiFactory.generateSerialVersionUidField(serialVersionUid: Long) =
    createProperty(MODIFIERS, SERIAL_VERSION_UID, null, false, "${serialVersionUid}L")

private fun KtClassBody.findCompanionObjectElement() = allCompanionObjects.firstOrNull()

private inline fun <reified T : PsiElement> PsiElement.findChild() = findChildOfType(this, T::class.java)
