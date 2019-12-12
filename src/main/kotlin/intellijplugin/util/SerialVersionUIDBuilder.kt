package intellijplugin.util

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssertStatement
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPostfixExpression
import com.intellij.psi.PsiPrefixExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiUtil
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.MessageFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import kotlin.experimental.and

class SerialVersionUIDBuilder private constructor(psiClass: PsiClass) : JavaRecursiveElementVisitor() {
    internal enum class Language(val fileExtension: String, format: String) {
        JAVA("java", "private static final long serialVersionUID = {0}L;"), GROOVY(
            "groovy", "private static final long serialVersionUID = {0}L" /*,
        SCALA ("scala",  "private val serialVersionUID = {0}L")*/
        );

        val format: MessageFormat

        init {
            this.format = MessageFormat(format)
        }
    }

    private val psiClass: PsiClass
    private var index: Int
    private val nonPrivateConstructors: MutableSet<MemberSignature>
    private val nonPrivateMethods: MutableSet<MemberSignature>
    private val nonPrivateFields: MutableSet<MemberSignature>
    private val staticInitializers: MutableList<MemberSignature>
    private var assertStatement = false
    private var classObjectAccessExpression = false
    private val memberMap: MutableMap<PsiElement, String>
    private fun createClassObjectAccessSynthetics(type: PsiType) {
        if (!classObjectAccessExpression) {
            val syntheticMethod: MemberSignature =
                MemberSignature.classAccessMethodMemberSignature
            nonPrivateMethods.add(syntheticMethod)
        }
        var unwrappedType = type
        val fieldNameBuffer: StringBuffer
        if (type is PsiArrayType) {
            fieldNameBuffer = StringBuffer("array")
            while (unwrappedType is PsiArrayType) {
                unwrappedType = unwrappedType.componentType
                fieldNameBuffer.append('$')
            }
        } else {
            fieldNameBuffer =
                StringBuffer(CLASS_ACCESS_METHOD_PREFIX)
        }
        if (unwrappedType is PsiPrimitiveType) {
            fieldNameBuffer.append(MemberSignature.Companion.createPrimitiveType(unwrappedType))
        } else {
            val text = unwrappedType.canonicalText.replace('.', '$')
            fieldNameBuffer.append(text)
        }
        val fieldName = fieldNameBuffer.toString()
        val memberSignature =
            MemberSignature(fieldName, 8, "Ljava/lang/Class;")
        if (!nonPrivateFields.contains(memberSignature)) {
            nonPrivateFields.add(memberSignature)
        }
        classObjectAccessExpression = true
    }

    private fun getAccessMethodIndex(element: PsiElement): String {
        var cache = memberMap[element]
        if (cache == null) {
            cache = index.toString()
            index++
            memberMap[element] = cache
        }
        return cache
    }

    fun getNonPrivateConstructors(): Array<MemberSignature> {
        init()
        return nonPrivateConstructors.toTypedArray()
    }

    fun getNonPrivateFields(): Array<MemberSignature> {
        init()
        return nonPrivateFields.toTypedArray()
    }

    val nonPrivateMethodSignatures: Array<MemberSignature>
        get() {
            init()
            return nonPrivateMethods.toTypedArray()
        }

    fun getStaticInitializers(): Array<MemberSignature> {
        init()
        return staticInitializers.toTypedArray()
    }

    private fun init() {
        if (index < 0) {
            index = 0
            psiClass.acceptChildren(this)
        }
    }

    override fun visitAssertStatement(statement: PsiAssertStatement) {
        super.visitAssertStatement(statement)
        if (assertStatement) {
            return
        }
        val memberSignature: MemberSignature = MemberSignature.assertionsDisabledFieldMemberSignature
        nonPrivateFields.add(memberSignature)
        val project = psiClass.project
        val psiElementFactory = JavaPsiFacade.getInstance(project).elementFactory
        val classType = psiElementFactory.createType(psiClass)
        createClassObjectAccessSynthetics(classType)
        if (staticInitializers.isEmpty()) {
            val initializerSignature: MemberSignature = MemberSignature.staticInitializerMemberSignature
            staticInitializers.add(initializerSignature)
        }
        assertStatement = true
    }

    override fun visitClassObjectAccessExpression(expression: PsiClassObjectAccessExpression) {
        val type = expression.operand.type
        if (type !is PsiPrimitiveType) {
            createClassObjectAccessSynthetics(type)
        }
        super.visitClassObjectAccessExpression(expression)
    }

    override fun visitMethodCallExpression(methodCallExpression: PsiMethodCallExpression) {
        for (expression in methodCallExpression.argumentList.expressions) {
            expression.accept(this)
        }
        val methodExpression = methodCallExpression.methodExpression
        methodExpression.accept(this)
    }

    override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
        super.visitReferenceElement(reference)
        val parentClass: PsiElement? = ClassUtils.getContainingClass(reference)
        if (reference.parent is PsiTypeElement) {
            return
        }
        val element = reference.resolve() as? PsiClass ?: return
        val elementParentClass = ClassUtils.getContainingClass(element)
        if (elementParentClass == null || elementParentClass != psiClass || element == parentClass) {
            return
        }
        val innerClass = element
        if (!innerClass.hasModifierProperty(PsiModifier.PRIVATE)) {
            return
        }
        val constructors = innerClass.constructors
        if (constructors.size == 0) {
            getAccessMethodIndex(innerClass)
        }
    }

    override fun visitReferenceExpression(expression: PsiReferenceExpression) {
        val element = expression.resolve()
        val elementParentClass: PsiElement? = ClassUtils.getContainingClass(element)
        val expressionParentClass: PsiElement? = ClassUtils.getContainingClass(expression)
        if (expressionParentClass == null || expressionParentClass == elementParentClass) {
            return
        }
        var parentOfParentClass: PsiElement? = ClassUtils.getContainingClass(expressionParentClass)
        while (parentOfParentClass != null && parentOfParentClass != psiClass) {
            if (expressionParentClass !is PsiAnonymousClass) {
                getAccessMethodIndex(expressionParentClass)
            }
            getAccessMethodIndex(parentOfParentClass)
            parentOfParentClass = ClassUtils.getContainingClass(parentOfParentClass)
        }
        if (element is PsiField) {
            val field = element
            if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
                val isStatic = field.hasModifierProperty(PsiModifier.STATIC)
                val type = field.type
                if (isStatic) { // Ignore constants.
                    if (field.hasModifierProperty(PsiModifier.FINAL) && type is PsiPrimitiveType) {
                        val initializer = field.initializer
                        if (PsiUtil.isConstantExpression(initializer)) {
                            return
                        }
                    }
                }
                // Ignore references to containing classes
                val returnTypeSignature =
                    getQualifiedName(
                        MemberSignature.Companion.createTypeSignature(type)
                    )
                val className = psiClass.qualifiedName
                val signatureBuffer = StringBuilder("(")
                if (!isStatic) {
                    signatureBuffer.append('L')
                        .append(className)
                        .append(';')
                }
                val accessMethodIndex = getAccessMethodIndex(field)
                if (psiClass != field.containingClass) {
                    return
                }
                // Add access methods
                var name: String? = null
                val parent = expression.parent
                if (parent is PsiAssignmentExpression) {
                    if (parent.lExpression == expression) {
                        name =
                            ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "02"
                        signatureBuffer.append(returnTypeSignature)
                    }
                } else if (parent is PsiPostfixExpression) {
                    val operationSign = parent.operationSign
                    val tokenType = operationSign.tokenType
                    if (tokenType === JavaTokenType.PLUSPLUS) {
                        name =
                            ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "08"
                    } else if (tokenType === JavaTokenType.MINUSMINUS) {
                        name =
                            ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "10"
                    }
                } else if (parent is PsiPrefixExpression) {
                    val operationSign = parent.operationSign
                    val tokenType = operationSign.tokenType
                    if (tokenType === JavaTokenType.PLUSPLUS) {
                        name =
                            ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "04"
                    } else if (tokenType === JavaTokenType.MINUSMINUS) {
                        name =
                            ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "06"
                    }
                }
                if (name == null) {
                    name =
                        ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "00"
                }
                signatureBuffer.append(')')
                    .append(returnTypeSignature)
                val signature = signatureBuffer.toString()
                val methodSignature =
                    MemberSignature(name, 8, signature)
                nonPrivateMethods.add(methodSignature)
            }
        } else if (element is PsiMethod) {
            val method = element
            if (method.hasModifierProperty(PsiModifier.PRIVATE) && psiClass == method.containingClass) {
                val signature: String
                signature = if (method.hasModifierProperty(PsiModifier.STATIC)) {
                    val s: String = MemberSignature.Companion.createMethodSignature(method)
                    getQualifiedName(s)
                } else {
                    val returnTypeSignature =
                        getQualifiedName(
                            MemberSignature.Companion.createTypeSignature(method.returnType!!)
                        )
                    val signatureBuffer = StringBuilder("(L")
                    signatureBuffer.append(psiClass.qualifiedName)
                        .append(';')
                    for (parameter in method.parameterList.parameters) {
                        val typeSignature =
                            getQualifiedName(
                                MemberSignature.Companion.createTypeSignature(parameter.type)
                            )
                        signatureBuffer.append(typeSignature)
                    }
                    signatureBuffer.append(')')
                        .append(returnTypeSignature)
                    signatureBuffer.toString()
                }
                val accessMethodIndex = getAccessMethodIndex(method)
                val methodSignature = MemberSignature(
                    ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "00",
                    8,
                    signature
                )
                nonPrivateMethods.add(methodSignature)
            }
        }
    }

    companion object {
        private const val SERIAL_FIELD_NAME = "serialVersionUID"
        private const val CLASS_ACCESS_METHOD_PREFIX = "class$"
        private const val ACCESS_METHOD_NAME_PREFIX = "access$"
        private const val SERIALIZABLE_CLASS_NAME = "java.io.Serializable"
        private val INTERFACE_COMPARATOR: Comparator<PsiClass?> = Comparator<PsiClass?> { psiClass1, psiClass2 ->
            val name1 = psiClass1?.qualifiedName
            val name2 = psiClass2?.qualifiedName
            if (name1 == null) {
                if (name2 == null) 0 else -1
            } else {
                if (name2 == null) +1 else name1.compareTo(name2)
            }
        }

        fun isUIDField(field: PsiField?): Boolean {
            return field != null && SERIAL_FIELD_NAME == field.name
        }

        fun getFullDeclaration(sourceFileExtension: String?, serial: Long): String? {
            for (language in Language.values()) {
                if (language.fileExtension == sourceFileExtension) {
                    return language.format.format(arrayOf(java.lang.Long.toString(serial)))
                }
            }
            return null
        }

        fun computeDefaultSUID(psiClass: PsiClass?): Long {
            if (psiClass == null) {
                return -1L
            }
            val manager = psiClass.manager
            val project = manager.project
            val psiFacade = JavaPsiFacade.getInstance(project)
            val serializable = psiFacade.findClass(
                SERIALIZABLE_CLASS_NAME,
                GlobalSearchScope.allScope(project)
            )
                ?: return -1L
            val isSerializable = psiClass.isInheritor(serializable, true)
            if (!isSerializable) {
                return 0L
            }
            val serialVersionUIDBuilder =
                SerialVersionUIDBuilder(psiClass)
            return try {
                val byteArrayOutputStream = ByteArrayOutputStream()
                val dataOutputStream = DataOutputStream(byteArrayOutputStream)
                val className = psiClass.qualifiedName
                dataOutputStream.writeUTF(className)
                val classModifierList = psiClass.modifierList
                var classModifiers: Int =
                    MemberSignature.Companion.calculateModifierBitmap(classModifierList)
                val methodSignatures =
                    serialVersionUIDBuilder.nonPrivateMethodSignatures
                if (psiClass.isInterface) {
                    classModifiers = classModifiers or 0x200
                    if (methodSignatures.size == 0) {
                        classModifiers = classModifiers and -0x401
                    }
                }
                dataOutputStream.writeInt(classModifiers)
                val interfaces = psiClass.interfaces
                Arrays.sort(
                    interfaces,
                    INTERFACE_COMPARATOR
                )
                for (aInterfaces in interfaces) {
                    val name = aInterfaces.qualifiedName
                    dataOutputStream.writeUTF(name)
                }
                val nonPrivateFields =
                    serialVersionUIDBuilder.getNonPrivateFields()
                val staticInitializers =
                    serialVersionUIDBuilder.getStaticInitializers()
                val nonPrivateConstructors =
                    serialVersionUIDBuilder.getNonPrivateConstructors()
                Arrays.sort(nonPrivateFields)
                Arrays.sort(nonPrivateConstructors)
                Arrays.sort(methodSignatures)
                writeMemberSignatures(
                    dataOutputStream,
                    nonPrivateFields
                )
                writeMemberSignatures(
                    dataOutputStream,
                    staticInitializers
                )
                writeMemberSignatures(
                    dataOutputStream,
                    nonPrivateConstructors
                )
                writeMemberSignatures(
                    dataOutputStream,
                    methodSignatures
                )
                dataOutputStream.flush()
                val digest = MessageDigest.getInstance("SHA")
                val digestBytes = digest.digest(byteArrayOutputStream.toByteArray())
                var serialVersionUID = 0L
                for (i in Math.min(digestBytes.size, 8) - 1 downTo 0) {
                    serialVersionUID = serialVersionUID shl 8 or (digestBytes[i] and 0xff.toByte()).toLong()
                }
                serialVersionUID
            } catch (exception: IOException) {
                val internalError = InternalError(exception.message)
                internalError.initCause(exception)
                throw internalError
            } catch (exception: NoSuchAlgorithmException) {
                val securityException: RuntimeException = SecurityException(exception.message)
                securityException.initCause(exception)
                throw securityException
            }
        }

        @Throws(IOException::class)
        private fun writeMemberSignatures(
            dataOutputStream: DataOutputStream,
            signatures: Array<MemberSignature>
        ) {
            for (field in signatures) {
                dataOutputStream.writeUTF(field.name)
                dataOutputStream.writeInt(field.modifiers)
                dataOutputStream.writeUTF(field.signature)
            }
        }

        private fun hasStaticInitializer(field: PsiField): Boolean {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) {
                return false
            }
            val manager = field.manager
            val scope = GlobalSearchScope.allScope(manager.project)
            val initializer = field.initializer ?: return false
            val fieldType = field.type
            val stringType: PsiType = PsiType.getJavaLangString(manager, scope)
            return !(field.hasModifierProperty(PsiModifier.FINAL) &&
                (fieldType is PsiPrimitiveType || fieldType == stringType)) ||
                !PsiUtil.isConstantExpression(initializer)
        }

        private fun getQualifiedName(className: String): String {
            return className.replace('/', '.')
        }
    }

    init {
        index = -1
        memberMap = HashMap()
        this.psiClass = psiClass
        nonPrivateMethods = HashSet()
        for (method in psiClass.methods) {
            if (!method.isConstructor && !method.hasModifierProperty(PsiModifier.PRIVATE)) {
                nonPrivateMethods.add(MemberSignature(method))
            }
        }
        nonPrivateFields = HashSet()
        for (field in psiClass.fields) {
            if (!field.hasModifierProperty(PsiModifier.PRIVATE) ||
                !field.hasModifierProperty(PsiModifier.STATIC) && !field.hasModifierProperty(PsiModifier.TRANSIENT)
            ) {
                nonPrivateFields.add(MemberSignature(field))
            }
        }
        staticInitializers = ArrayList()
        for (initializer in psiClass.initializers) {
            val modifierList = initializer.modifierList
            if (modifierList != null && modifierList.hasModifierProperty(PsiModifier.STATIC)) {
                staticInitializers.add(MemberSignature.staticInitializerMemberSignature)
                break
            }
        }
        if (staticInitializers.isEmpty()) {
            for (field in psiClass.fields) {
                if (hasStaticInitializer(field)) {
                    staticInitializers.add(MemberSignature.staticInitializerMemberSignature)
                    break
                }
            }
        }
        nonPrivateConstructors = HashSet()
        val constructors = psiClass.constructors
        if (constructors.size == 0 && !psiClass.isInterface) {
            val constructorSignature: MemberSignature
            constructorSignature = if (psiClass.hasModifierProperty(PsiModifier.PUBLIC)) {
                MemberSignature.publicConstructor
            } else {
                MemberSignature.packagePrivateConstructor
            }
            nonPrivateConstructors.add(constructorSignature)
        }
        for (constructor in constructors) {
            if (!constructor.hasModifierProperty(PsiModifier.PRIVATE)) {
                val constructorSignature =
                    MemberSignature(constructor)
                nonPrivateConstructors.add(constructorSignature)
            }
        }
    }
}