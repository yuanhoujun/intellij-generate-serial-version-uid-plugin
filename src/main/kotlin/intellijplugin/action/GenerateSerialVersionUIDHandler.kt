package intellijplugin.action

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteral
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPrefixExpression
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.util.IncorrectOperationException
import intellijplugin.VERSION_UID_PROPERTY_NAME
import intellijplugin.model.SerialVersionUIDState
import intellijplugin.util.ClassUtils
import intellijplugin.util.SerialVersionUIDBuilder
import intellijplugin.util.SerializationUtils
import intellijplugin.util.computeDefaultSUID
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.idea.internal.Location
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.isObjectLiteral
import org.jetbrains.kotlin.psi.psiUtil.isPrivateNestedClassOrObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import javax.swing.SwingUtilities

class GenerateSerialVersionUIDHandler private constructor() : EditorWriteActionHandler() {
    override fun executeWriteAction(
        editor: Editor?,
        dataContext: DataContext?
    ) {
        if (editor == null) {
            LOGGER.debug("editor == null")
            displayMessage("No editor found.")
            return
        }
        if (dataContext == null) {
            LOGGER.debug("dataContext == null")
            displayMessage("No data context.")
            return
        }
        val project = DataKeys.PROJECT.getData(dataContext)
        val virtualFile = DataKeys.VIRTUAL_FILE.getData(dataContext)
        if (project == null) {
            LOGGER.debug("project == null")
            displayMessage("No project found.")
            return
        }
        if (virtualFile == null) {
            LOGGER.debug("virtualFile == null")
            displayMessage("No file found.")
            return
        }
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        if (psiFile is PsiJavaFile) {
            val manager = PsiManager.getInstance(project)
            val psiClass =
                getPsiClass(virtualFile, manager, editor)
            if (psiClass == null) {
                LOGGER.debug("psiClass == null")
                displayMessage("Not a Java class file.")
                return
            }
            val serialVersionUIDValue: Long =
                SerialVersionUIDBuilder.Companion.computeDefaultSUID(psiClass)
            if (needsUIDField(psiClass) && !hasUIDField(
                    psiClass,
                    serialVersionUIDValue
                )
            ) {
                insertSerialVersionUID(
                    project,
                    virtualFile.extension,
                    psiClass,
                    serialVersionUIDValue
                )
            }
        } else if (psiFile is KtFile) {
            val location = Location.fromEditor(editor, project)
            val psiElement = psiFile.findElementAt(location.startOffset)
            var ktClass: KtClass? = null
            if (null != psiElement) {
                ktClass = getKtClassWith(psiElement)
            }
            if (ktClass == null) {
                LOGGER.debug("ktClass == null")
                displayMessage("Not a Kotlin class file.")
                return
            }

            val versionUID = computeDefaultSUID(ktClass)

            if (needGenerateVersionUID(ktClass, versionUID)) {
                createOrReplaceVersionUID(ktClass, versionUID)
            }
        }
    }

    companion object {
        private val LOGGER = Logger.getInstance(
            GenerateSerialVersionUIDHandler::class.java.name
        )
        val INSTANCE: EditorWriteActionHandler = GenerateSerialVersionUIDHandler()
        private var _showing = false
        private const val m_ignoreSerializableDueToInheritance = false
        fun getPsiClass(
            virtualFile: VirtualFile?,
            manager: PsiManager,
            editor: Editor
        ): PsiClass? {
            val psiFile = (if (virtualFile == null) null else manager.findFile(virtualFile)) ?: return null
            val elementAtCaret = psiFile.findElementAt(editor.caretModel.offset)
            return ClassUtils.findPsiClass(elementAtCaret)
        }

        fun getKtClassWith(psiElement: PsiElement): KtClass? {
            return if (psiElement is KtLightElement<*, *>) {
                val origin: PsiElement? = psiElement.kotlinOrigin
                origin?.let { getKtClassWith(it) }
            } else if (psiElement is KtClass && !psiElement.isEnum() &&
                !psiElement.isInterface() &&
                !psiElement.isAnnotation() &&
                !psiElement.isSealed()
            ) {
                psiElement
            } else {
                val parent = psiElement.parent
                parent?.let { getKtClassWith(it) }
            }
        }

        private fun insertSerialVersionUID(
            project: Project,
            extension: String?,
            psiClass: PsiClass,
            serial: Long
        ) {
            val psiElementFactory = JavaPsiFacade.getInstance(project).elementFactory
            val codeStyleManager = CodeStyleManager.getInstance(project)
            if (psiElementFactory != null && codeStyleManager != null) {
                try {
                    val fullDeclaration: String = SerialVersionUIDBuilder.getFullDeclaration(extension, serial) ?: ""
                    val psiField = psiElementFactory.createFieldFromText(fullDeclaration, null)
                    if (psiField != null) {
                        val oldPsiField = getUIDField(psiClass)
                        codeStyleManager.reformat(psiField)
                        if (oldPsiField != null) {
                            oldPsiField.replace(psiField)
                        } else {
                            psiClass.add(psiField)
                        }
                    }
                } catch (e: IncorrectOperationException) {
                    LOGGER.info("Could not insert field", e)
                }
            }
        }

        private fun createOrReplaceVersionUID(ktClass: KtClass, versionUID: Long) {
            val ktFactory = KtPsiFactory(ktClass.project)

            val classBody = ktClass.body ?: return

            val state = getUIDFieldState(ktClass, versionUID)
            when(state) {
                SerialVersionUIDState.NoCompanionObject -> {
                    val companionObject = ktFactory.createCompanionObject()

                    val propertyBlock = "\n\t\tprivate const val serialVersionUID = ${versionUID}L\n"

                    companionObject.body!!.addAfter(ktFactory.createBlockCodeFragment(propertyBlock, companionObject),
                        companionObject.body!!.firstChild
                    )
                    classBody.addAfter(companionObject, classBody.firstChild)
                }
                SerialVersionUIDState.HasCompanionObjectButNoUID -> {
                    val companionObject = ktClass.companionObjects.first()
                    val property = ktFactory.createProperty(
                        "private const",
                        "serialVersionUID",
                        "Long",
                        false,
                        versionUID.toString() + "L"
                    )
                    companionObject.body!!.addAfter(property, companionObject.body!!.firstChild)
                }
                SerialVersionUIDState.HasInconsistentUID -> {
                    val companionObject = ktClass.companionObjects.first()
                    val oldProperty = getVersionUIDProperty(companionObject.body?.properties)!!

                    val newProperty = ktFactory.createProperty(
                        "private const",
                        "serialVersionUID",
                        "Long",
                        false,
                        versionUID.toString() + "L"
                    )

                    oldProperty.replace(newProperty)
                }
            }
        }

        private fun getVersionUIDProperty(properties: List<KtProperty>?): KtProperty? {
            return properties?.find { it.name == VERSION_UID_PROPERTY_NAME }
        }

        private fun insertSerialVersionUID(
            editor: Editor,
            ktClass: KtClass,
            serialVersionUID: Long
        ) {
            val codeStyleManager = CodeStyleManager.getInstance(ktClass.project)
            val elementFactory = KtPsiFactory(ktClass.project)
            val body = ktClass.body
            if (null != body) {
                val declarations = body.declarations
                val hasSerialVersionUID = AtomicBoolean(false)
                val hasCompanionObject = AtomicBoolean(false)
                if (declarations.size > 0) {
                    declarations.forEach(Consumer { declaration: KtDeclaration? ->
                        if (declaration is KtObjectDeclaration) {
                            val objectDeclaration = declaration
                            if (objectDeclaration.isCompanion()) {
                                val companionBody = objectDeclaration.body
                                if (null != companionBody) {
                                    val properties = companionBody.properties
                                    properties.forEach(Consumer { prop: KtProperty ->
                                        if ("serialVersionUID" == prop.name) {
                                            hasSerialVersionUID.set(true)
                                        }
                                    })
                                    if (!hasSerialVersionUID.get()) {
                                        val block1 =
                                            "\n\t\tconst val serialVersionUID = " + serialVersionUID + "L\n"
                                        val property = elementFactory.createProperty(
                                            "const",
                                            "serialVersionUID",
                                            "Long",
                                            false,
                                            serialVersionUID.toString() + "L"
                                        )
                                        objectDeclaration.body!!
                                            .addAfter(property, objectDeclaration.body!!.firstChild)
                                        codeStyleManager.reformat(objectDeclaration)
                                    }
                                }
                                hasCompanionObject.set(true)
                            }
                        }
                    })
                }
                if (!hasCompanionObject.get()) {
                    val block = StringBuilder("\tcompanion object {\n")
                    block.append("\t\tconst val serialVersionUID = ")
                        .append(serialVersionUID)
                        .append("L\n")
                        .append("\t}\n")
                    val companionObject = elementFactory.createCompanionObject()
                    val block1 = "\n\t\tconst val serialVersionUID = " + serialVersionUID + "L\n"
                    companionObject.body!!.addAfter(
                        elementFactory.createBlockCodeFragment(block1, companionObject),
                        companionObject.body!!.firstChild
                    )
                    ktClass.body!!.addAfter(companionObject, ktClass.body!!.firstChild)
                    codeStyleManager.reformat(companionObject)
                }
            }
        }

        private fun displayMessage(message: String) {
            SwingUtilities.invokeLater {
                try {
                    if (!_showing) {
                        _showing = true
                        Messages.showErrorDialog(message, "Error")
                    }
                } finally {
                    _showing = false
                }
            }
        }

        fun needsUIDField(aClass: PsiClass?): Boolean {
            if (aClass == null) {
                return false
            }
            if (aClass.isInterface || aClass.isAnnotationType || aClass.isEnum) {
                return false
            }
            if (aClass is PsiTypeParameter || aClass is PsiAnonymousClass) {
                return false
            }
            if (m_ignoreSerializableDueToInheritance) {
                if (!SerializationUtils.isDirectlySerializable(aClass)) {
                    return false
                }
            } else if (!SerializationUtils.isSerializable(aClass)) {
                return false
            }
            return true
        }

        fun needsUIDField(aClass: KtClass?): Boolean {
            if (aClass == null) {
                return false
            }

            if (aClass.isInterface() || aClass.isAnnotation() || aClass.isEnum() || aClass.isObjectLiteral()
                || aClass.isPrivateNestedClassOrObject) {
                return false
            }

            if (!SerializationUtils.isSerializable(aClass)) {
                return false
            }

            return true
        }

        fun getUIDField(psiClass: PsiClass?): PsiField? {
            if (psiClass != null) {
                for (field in psiClass.fields) {
                    if (SerialVersionUIDBuilder.Companion.isUIDField(field)) {
                        return field
                    }
                }
            }
            return null
        }

        @JvmOverloads
        fun hasUIDField(
            psiClass: PsiClass?,
            serialVersionUIDValue: Long = SerialVersionUIDBuilder.computeDefaultSUID(psiClass)
        ): Boolean {
            val field = getUIDField(psiClass)
            if (field != null) {
                var initializer = field.initializer
                var sign = 1
                if (initializer is PsiPrefixExpression) {
                    val prefixExpression = initializer
                    if (prefixExpression.operationSign.tokenType === JavaTokenType.MINUS) {
                        sign = -1
                    }
                    initializer = prefixExpression.operand
                }
                val literalValue =
                    if (initializer is PsiLiteral) (initializer as PsiLiteral).value else null
                return literalValue is Long && literalValue * sign == serialVersionUIDValue
            }
            return false
        }

        fun getUIDFieldState(ktClass: KtClass, expectedVersionUID: Long): SerialVersionUIDState {
            val companionObject = ktClass.companionObjects?.firstOrNull()

            var state = SerialVersionUIDState.NoCompanionObject

            if (null != companionObject) {
                state = SerialVersionUIDState.HasCompanionObjectButNoUID

                val companionObjectBody = companionObject.body
                if (null != companionObjectBody) {
                    val properties = companionObjectBody.properties

                    if (properties.isNotEmpty()) {
                        for (prop in properties) {
                            if (prop.name == VERSION_UID_PROPERTY_NAME) {
                                val initializer = prop.initializer

                                var initialValue = initializer!!.text
                                if (initialValue.contains("L")) {
                                    initialValue = initialValue.substring(0, initialValue.length - 1)
                                }

                                state = if (initialValue.toLongOrNull() == expectedVersionUID) {
                                    SerialVersionUIDState.HasConsistentUID
                                } else {
                                    SerialVersionUIDState.HasInconsistentUID
                                }

                                break
                            }
                        }
                    }
                }
            }

            return state
        }

        fun needGenerateVersionUID(ktClass: KtClass, expectedVersionUID: Long): Boolean {
            return needsUIDField(ktClass) && getUIDFieldState(ktClass, expectedVersionUID) != SerialVersionUIDState.HasConsistentUID
        }
    }
}