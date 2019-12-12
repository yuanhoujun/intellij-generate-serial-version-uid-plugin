package intellijplugin.util

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import gnu.trove.THashMap
import java.lang.reflect.Field

object TypeUtils {
    var PRIMITIVE_TYPES: MutableMap<PsiType?, String>? = null

    fun expressionHasType(typeName: String?, expression: PsiExpression?): Boolean {
        return expression != null && typeEquals(typeName, expression.type)
    }

    fun typeEquals(typeName: String?, targetType: PsiType?): Boolean {
        return targetType != null && targetType.equalsToText(typeName!!)
    }

    fun isJavaLangObject(targetType: PsiType?): Boolean {
        return typeEquals("java.lang.Object", targetType)
    }

    fun isJavaLangString(targetType: PsiType?): Boolean {
        return typeEquals("java.lang.String", targetType)
    }

    fun expressionHasTypeOrSubtype(typeName: String?, expression: PsiExpression?): Boolean {
        if (expression == null || typeName == null) {
            return false
        }
        val type = expression.type
        if (type == null || type !is PsiClassType) {
            return false
        }
        val psiClass = type.resolve()
        return psiClass != null && ClassUtils.isSubclass(psiClass, typeName)
    }

    /**
     * LONG, INT, SHORT, ... constants are moved from PsiType to PsiPrimitiveType
     * between IDEA 9 and IDEA 10. This method is used to encapsulate their access.
     * @param typeName the name of the constant field (e.g. "LONG")
     * @return the integral type instance
     */
    fun getPsiType(typeName: String?): PsiType? {
        var typeField: Field? = null
        try {
            typeField = PsiPrimitiveType::class.java.getField(typeName)
        } catch (e: NoSuchFieldException) { /* Ignore */
        }
        if (typeField == null) {
            try {
                typeField = PsiType::class.java.getField(typeName)
            } catch (e: NoSuchFieldException) { /* Ignore */
            }
        }
        if (typeField != null) {
            try {
                return typeField[null] as PsiType
            } catch (e: IllegalAccessException) { /* Ignore */
            }
        }
        return null
    }

    init {
        PRIMITIVE_TYPES = THashMap(10)
        PRIMITIVE_TYPES!![getPsiType("BYTE")] = "B"
        PRIMITIVE_TYPES!![getPsiType("CHAR")] = "C"
        PRIMITIVE_TYPES!![getPsiType("DOUBLE")] = "D"
        PRIMITIVE_TYPES!![getPsiType("FLOAT")] = "F"
        PRIMITIVE_TYPES!![getPsiType("INT")] = "I"
        PRIMITIVE_TYPES!![getPsiType("LONG")] = "J"
        PRIMITIVE_TYPES!![getPsiType("SHORT")] = "S"
        PRIMITIVE_TYPES!![getPsiType("VOID")] = "V"
        PRIMITIVE_TYPES!![getPsiType("BOOLEAN")] = "Z"
    }
}