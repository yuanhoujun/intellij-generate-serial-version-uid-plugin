//package com.youngfeng.ideaplugin
//
//import com.intellij.openapi.actionSystem.AnAction
//import com.intellij.openapi.actionSystem.AnActionEvent
//import com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE
//import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
//import com.intellij.psi.PsiDocumentManager.getInstance
//import com.youngfeng.ideaplugin.psi.generatePsiTreeElements
//import org.jetbrains.kotlin.idea.refactoring.toPsiFile
//
//class GenerateSerialVersionUidAction : AnAction("Generate Kotlin serialVersionUID") {
//    override fun actionPerformed(event: AnActionEvent) {
//        event.dataContext.getData(VIRTUAL_FILE)?.toPsiFile(checkNotNull(event.project))?.let { psiFile ->
//            with(psiFile) {
//                getInstance(project).run {
//                    runWriteCommandAction(project) {
//                        generatePsiTreeElements()
//                    }
//                    doPostponedOperationsAndUnblockDocument(checkNotNull(getDocument(psiFile)))
//                }
//            }
//        }
//    }
//}