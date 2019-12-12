package intellijplugin.util

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import java.util.HashSet

object ClassUtils {
    private var integralTypes: Set<PsiType?>? = null
    private var primitiveNumericTypes: Set<PsiType?>? = null
    private var immutableTypes: Set<String>? = null
    private var numericTypes: Set<String>? = null

    fun isSubclass(psiClass: PsiClass, ancestorName: String): Boolean {
        val ancestorClass = findPsiClass(psiClass.manager, ancestorName)
        return InheritanceUtil.isInheritorOrSelf(psiClass, ancestorClass, true)
    }

    fun findPsiClass(psiManager: PsiManager, className: String): PsiClass? {
        val project = psiManager.project
        val psiFacade = JavaPsiFacade.getInstance(project)
        return psiFacade.findClass(className, GlobalSearchScope.allScope(project))
    }

    fun findPsiClass(element: PsiElement?): PsiClass? {
        var element = element
        while (true) {
            val psiClass =
                if (element is PsiClass) element else PsiTreeUtil.getParentOfType(
                    element,
                    PsiClass::class.java
                )
            if (psiClass == null || psiClass.containingClass !is PsiAnonymousClass) {
                return psiClass
            }
            element = psiClass.parent
        }
    }

    // 用于处理Kotlin问题
    fun findKtClass(leaf: PsiElement): KtClass? {
        if (leaf.containingFile !is KtFile) return null
        val jetFile = leaf.containingFile as KtFile
        return PsiTreeUtil.getParentOfType(leaf, KtClass::class.java, false)
    }

    fun isPrimitive(type: PsiType): Boolean {
        return TypeConversionUtil.isPrimitiveAndNotNull(type)
    }

    fun isIntegral(type: PsiType?): Boolean {
        return type != null && integralTypes!!.contains(type)
    }

    fun isImmutable(type: PsiType): Boolean {
        return TypeConversionUtil.isPrimitiveAndNotNull(type) || immutableTypes!!.contains(
            type.canonicalText
        )
    }

    fun inSamePackage(class1: PsiClass?, class2: PsiClass?): Boolean {
        val className1 = class1?.qualifiedName
        val className2 = class2?.qualifiedName
        return className1 != null && className2 != null && getClassPackageName(
            className1
        ) == getClassPackageName(className2)
    }

    private fun getClassPackageName(className: String): String {
        val lastDotIndex = className.lastIndexOf('.')
        return if (lastDotIndex < 0) {
            ""
        } else className.substring(0, lastDotIndex)
    }

    fun isFieldVisible(field: PsiField?, fromClass: PsiClass): Boolean {
        if (field == null) {
            return false
        }
        val fieldClass = field.containingClass ?: return false
        if (fieldClass == fromClass) {
            return true
        }
        if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
            return false
        }
        return if (field.hasModifierProperty(PsiModifier.PUBLIC) || field.hasModifierProperty(PsiModifier.PROTECTED)) {
            true
        } else inSamePackage(fieldClass, fromClass)
    }

    fun isWrappedNumericType(type: PsiType?): Boolean {
        return type is PsiClassType &&
            numericTypes!!.contains(type.className)
    }

    fun isPrimitiveNumericType(type: PsiType?): Boolean {
        return type != null && primitiveNumericTypes!!.contains(type)
    }

    fun isInnerClass(psiClass: PsiClass?): Boolean {
        return getContainingClass(psiClass) != null
    }

    fun getContainingClass(psiClass: PsiElement?): PsiClass? {
        return PsiTreeUtil.getParentOfType(psiClass, PsiClass::class.java)
    }

    fun getOutermostContainingClass(psiClass: PsiClass?): PsiClass? {
        var outerClass = psiClass
        do {
            val containingClass = getContainingClass(outerClass) ?: return outerClass
            outerClass = containingClass
        } while (true)
    }

    fun isClassVisibleFromClass(baseClass: PsiClass, referencedClass: PsiClass): Boolean {
        return if (referencedClass.hasModifierProperty(PsiModifier.PUBLIC)) {
            true
        } else if (referencedClass.hasModifierProperty(PsiModifier.PRIVATE)) {
            PsiTreeUtil.findCommonParent(baseClass, referencedClass) != null
        } else {
            inSamePackage(baseClass, referencedClass)
        }
    }

    init {
        val integralTypeArray = arrayOf(
            TypeUtils.getPsiType("LONG"), TypeUtils.getPsiType("INT"),
            TypeUtils.getPsiType("SHORT"), TypeUtils.getPsiType("CHAR"),
            TypeUtils.getPsiType("BYTE")
        )
        val primitiveNumericTypeArray = arrayOf(
            TypeUtils.getPsiType("BYTE"), TypeUtils.getPsiType("CHAR"),
            TypeUtils.getPsiType("SHORT"), TypeUtils.getPsiType("INT"),
            TypeUtils.getPsiType("LONG"), TypeUtils.getPsiType("FLOAT"),
            TypeUtils.getPsiType("DOUBLE")
        )
        val immutableTypeArray = arrayOf(
            "java.lang.Boolean", "java.lang.Char", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float",
            "java.lang.Double", "java.lang.Byte", "java.lang.String",
            "java.awt.Font", "java.awt.Color"
        )
        val numericTypeArray = arrayOf(
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
            "java.lang.Long", "java.lang.Float", "java.lang.Double"
        )
        integralTypes = HashSet(integralTypeArray.toList())
        primitiveNumericTypes = HashSet(primitiveNumericTypeArray.toList())
        immutableTypes = HashSet(immutableTypeArray.toList())
        numericTypes = HashSet(numericTypeArray.toList())
    }
}