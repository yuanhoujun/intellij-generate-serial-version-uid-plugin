package intellijplugin.util

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType

class MemberSignature : Comparable<MemberSignature> {
    companion object {
        private const val CONSTRUCTOR_NAME = "<init>"
        private const val INITIALIZER_SIGNATURE = "()V"
        private var ASSERTIONS_DISABLED_FIELD: MemberSignature? = null
        private var CLASS_ACCESS_METHOD: MemberSignature? = null
        private var PACKAGE_PRIVATE_CONSTRUCTOR: MemberSignature? = null
        private var PUBLIC_CONSTRUCTOR: MemberSignature? = null
        private var STATIC_INITIALIZER: MemberSignature? = null

        fun calculateModifierBitmap(modifierList: PsiModifierList?): Int {
            var modifiers = 0
            if (modifierList != null) {
                if (modifierList.hasModifierProperty(PsiModifier.PUBLIC)) {
                    modifiers = modifiers or 1
                }
                if (modifierList.hasModifierProperty(PsiModifier.PRIVATE)) {
                    modifiers = modifiers or 2
                }
                if (modifierList.hasModifierProperty(PsiModifier.PROTECTED)) {
                    modifiers = modifiers or 4
                }
                if (modifierList.hasModifierProperty(PsiModifier.STATIC)) {
                    modifiers = modifiers or 8
                }
                if (modifierList.hasModifierProperty(PsiModifier.FINAL)) {
                    modifiers = modifiers or 0x10
                }
                if (modifierList.hasModifierProperty(PsiModifier.VOLATILE)) {
                    modifiers = modifiers or 0x40
                }
                if (modifierList.hasModifierProperty(PsiModifier.TRANSIENT)) {
                    modifiers = modifiers or 0x80
                }
                if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    modifiers = modifiers or 0x400
                }
                if (modifierList.hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
                    modifiers = modifiers or 0x20
                }
                if (modifierList.hasModifierProperty(PsiModifier.NATIVE)) {
                    modifiers = modifiers or 0x100
                }
                if (modifierList.hasModifierProperty(PsiModifier.STRICTFP)) {
                    modifiers = modifiers or 0x800
                }
            }
            return modifiers
        }

        fun createMethodSignature(method: PsiMethod): String {
            val signatureBuffer = StringBuilder("(")
            for (parameter in method.parameterList.parameters) {
                signatureBuffer.append(createTypeSignature(parameter.type))
            }
            val returnType = method.returnType
            signatureBuffer.append(')')
                .append(createTypeSignature(returnType ?: PsiType.VOID))
            return signatureBuffer.toString()
        }

        fun createPrimitiveType(primitiveType: PsiPrimitiveType?): String {
            return TypeUtils.PRIMITIVE_TYPES!![primitiveType] ?: throw InternalError()
        }

        fun createTypeSignature(type: PsiType): String {
            val buffer = StringBuilder()
            var internalType = type
            var arrayType: PsiArrayType
            while (internalType is PsiArrayType) {
                buffer.append('[')
                arrayType = internalType
                internalType = arrayType.componentType
            }
            if (internalType is PsiPrimitiveType) {
                val primitypeTypeSignature =
                    createPrimitiveType(internalType)
                buffer.append(primitypeTypeSignature)
            } else {
                buffer.append('L')
                if (internalType is PsiClassType) {
                    var psiClass = internalType.resolve()
                    if (psiClass != null) {
                        val postFix = StringBuilder()
                        var containingClass = ClassUtils.getContainingClass(psiClass)
                        while (containingClass != null) {
                            postFix.insert(0, psiClass!!.name).insert(0, '$')
                            psiClass = containingClass
                            containingClass = ClassUtils.getContainingClass(psiClass)
                        }
                        val qualifiedName = psiClass!!.qualifiedName
                        if (qualifiedName == null) {
                            buffer.append("java.lang.Object")
                        } else {
                            buffer.append(qualifiedName.replace('.', '/')).append(postFix)
                        }
                    }
                } else {
                    buffer.append(internalType.canonicalText.replace('.', '/'))
                }
                buffer.append(';')
            }
            return buffer.toString()
        }

        val assertionsDisabledFieldMemberSignature: MemberSignature
            get() = ASSERTIONS_DISABLED_FIELD!!

        val classAccessMethodMemberSignature: MemberSignature
            get() = CLASS_ACCESS_METHOD!!

        val packagePrivateConstructor: MemberSignature
            get() = PACKAGE_PRIVATE_CONSTRUCTOR!!

        val publicConstructor: MemberSignature
            get() = PUBLIC_CONSTRUCTOR!!

        val staticInitializerMemberSignature: MemberSignature
            get() = STATIC_INITIALIZER!!

        init {
            ASSERTIONS_DISABLED_FIELD =
                MemberSignature("\$assertionsDisabled", 24, "Z")
            CLASS_ACCESS_METHOD =
                MemberSignature("class$", 8, "(Ljava.lang.String;)Ljava.lang.Class;")
            PACKAGE_PRIVATE_CONSTRUCTOR =
                MemberSignature(
                    CONSTRUCTOR_NAME,
                    0,
                    INITIALIZER_SIGNATURE
                )
            PUBLIC_CONSTRUCTOR = MemberSignature(
                CONSTRUCTOR_NAME,
                1,
                INITIALIZER_SIGNATURE
            )
            STATIC_INITIALIZER = MemberSignature(
                "<clinit>",
                8,
                INITIALIZER_SIGNATURE
            )
        }
    }

    val modifiers: Int
    val name: String
    val signature: String

    constructor(field: PsiField) {
        modifiers = calculateModifierBitmap(field.modifierList)
        name = field.name
        signature = createTypeSignature(field.type)
    }

    constructor(method: PsiMethod) {
        modifiers = calculateModifierBitmap(method.modifierList)
        signature = createMethodSignature(method).replace('/', '.')
        name =
            if (method.isConstructor) CONSTRUCTOR_NAME else method.name
    }

    constructor(name: String, modifiers: Int, signature: String) {
        this.name = name
        this.modifiers = modifiers
        this.signature = signature
    }

    override fun compareTo(other: MemberSignature): Int {
        val result = name.compareTo(other.name)
        return if (result == 0) signature.compareTo(other.signature) else result
    }

    override fun equals(`object`: Any?): Boolean {
        if (this === `object`) {
            return true
        }
        if (`object` == null || javaClass != `object`.javaClass) {
            return false
        }
        val that = `object` as MemberSignature
        return modifiers == that.modifiers && name == that.name && signature == that.signature
    }

    override fun hashCode(): Int {
        return name.hashCode() + signature.hashCode()
    }

    override fun toString(): String {
        return name + signature
    }
}