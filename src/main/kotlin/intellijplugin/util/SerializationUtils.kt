package intellijplugin.util

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

object SerializationUtils {
    private const val SERIALIZABLE_CLASS_NAME = "java.io.Serializable"
    private const val EXTERNALIZABLE_CLASS_NAME = "java.io.Externalizable"
    fun isSerializable(psiClass: PsiClass?): Boolean {
        if (psiClass == null) {
            return false
        }
        val serializable = ClassUtils.findPsiClass(
            psiClass.manager,
            SERIALIZABLE_CLASS_NAME
        )
        return InheritanceUtil.isInheritorOrSelf(psiClass, serializable, true)
    }

    fun isSerializable(psiClass: KtClass?): Boolean {
        return psiClass?.let { isImplementingSerializable(it) } ?: false
    }

    fun isImplementingSerializable(classOrObject: KtClassOrObject): Boolean {
        val list = classOrObject.superTypeListEntries
        val result = AtomicBoolean(false)
        list.forEach(Consumer { ktSuperTypeListEntry: KtSuperTypeListEntry ->
            if ("Serializable" == ktSuperTypeListEntry.text || "java.io.Serializable" == ktSuperTypeListEntry.text) {
                result.set(true)
            }
        })
        return result.get()
    }

    fun isExternalizable(psiClass: PsiClass): Boolean {
        val serializable = ClassUtils.findPsiClass(
            psiClass.manager,
            EXTERNALIZABLE_CLASS_NAME
        )
        return InheritanceUtil.isInheritorOrSelf(psiClass, serializable, true)
    }

    fun isDirectlySerializable(psiClass: PsiClass): Boolean {
        val implementsList = psiClass.implementsList
        if (implementsList != null) {
            for (aInterfaces in implementsList.referenceElements) {
                val implemented = aInterfaces.resolve() as PsiClass?
                if (implemented != null && SERIALIZABLE_CLASS_NAME == implemented.qualifiedName) {
                    return true
                }
            }
        }
        return false
    }

    fun hasReadObject(psiClass: PsiClass): Boolean {
        for (psiMethod in psiClass.methods) {
            if (isReadObject(psiMethod)) {
                return true
            }
        }
        return false
    }

    fun hasWriteObject(psiClass: PsiClass): Boolean {
        for (psiMethod in psiClass.methods) {
            if (isWriteObject(psiMethod)) {
                return true
            }
        }
        return false
    }

    fun isReadObject(psiMethod: PsiMethod): Boolean {
        return isReadWriteObject(
            psiMethod,
            "readObject",
            "java.io.ObjectInputStream"
        )
    }

    fun isWriteObject(psiMethod: PsiMethod): Boolean {
        return isReadWriteObject(
            psiMethod,
            "writeObject",
            "java.io.ObjectOutputStream"
        )
    }

    fun isReadResolve(method: PsiMethod): Boolean {
        return isReadWriteResolve(method, "readResolve")
    }

    fun isWriteReplace(method: PsiMethod): Boolean {
        return isReadWriteResolve(method, "writeReplace")
    }

    private fun isReadWriteObject(
        psiMethod: PsiMethod,
        methodName: String,
        objectStreamClassName: String
    ): Boolean {
        val psiMethodName = psiMethod.name
        if (methodName != psiMethodName) {
            return false
        }
        val parameterList = psiMethod.parameterList
        val parameters = parameterList.parameters
        if (parameters.size != 1) {
            return false
        }
        val argType = parameters[0].type
        return TypeUtils.typeEquals(objectStreamClassName, argType) &&
            TypeUtils.typeEquals("void", psiMethod.returnType)
    }

    private fun isReadWriteResolve(method: PsiMethod, resolveMethodName: String): Boolean {
        val methodName = method.name
        if (resolveMethodName != methodName) {
            return false
        }
        val parameterList = method.parameterList
        val parameters = parameterList.parameters
        return parameters.size == 0 &&
            TypeUtils.isJavaLangObject(method.returnType)
    }
}