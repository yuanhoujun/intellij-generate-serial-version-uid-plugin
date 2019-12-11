package intellijplugin.util;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.*;

public class SerialVersionUIDBuilder extends JavaRecursiveElementVisitor {

    private static final String SERIAL_FIELD_NAME          = "serialVersionUID";
    private static final String CLASS_ACCESS_METHOD_PREFIX = "class$";
    private static final String ACCESS_METHOD_NAME_PREFIX  = "access$";
    private static final String SERIALIZABLE_CLASS_NAME    = "java.io.Serializable";

    @SuppressWarnings("UnusedDeclaration")
    enum Language {
        JAVA  ("java",   "private static final long serialVersionUID = {0}L;"),
        GROOVY("groovy", "private static final long serialVersionUID = {0}L")/*,
        SCALA ("scala",  "private val serialVersionUID = {0}L")*/;

        private String        fileExtension;
        private MessageFormat format;

        private Language(@NotNull String fileExtension, @NotNull String format) {
            this.fileExtension = fileExtension;
            this.format        = new MessageFormat(format);
        }
    }

    private final PsiClass                psiClass;
    private       int                     index;
    private final Set<MemberSignature>    nonPrivateConstructors;
    private final Set<MemberSignature>    nonPrivateMethods;
    private final Set<MemberSignature>    nonPrivateFields;
    private final List<MemberSignature>   staticInitializers;
    private       boolean                 assertStatement;
    private       boolean                 classObjectAccessExpression;
    private final Map<PsiElement, String> memberMap;

    private static final Comparator<PsiClass> INTERFACE_COMPARATOR = new Comparator<PsiClass>() {
            public int compare(PsiClass psiClass1, PsiClass psiClass2) {
                final String name1 = (psiClass1 == null) ? null : psiClass1.getQualifiedName();
                final String name2 = (psiClass2 == null) ? null : psiClass2.getQualifiedName();

                if (name1 == null) {
                    return (name2 == null) ? 0 : -1;
                } else {
                    return (name2 == null ? +1 : name1.compareTo(name2));
                }
            }
        };

    private SerialVersionUIDBuilder(PsiClass psiClass) {
        this.index     = -1;
        this.memberMap = new HashMap<PsiElement, String>();
        this.psiClass  = psiClass;
        this.nonPrivateMethods = new HashSet<MemberSignature>();

        for (PsiMethod method : psiClass.getMethods()) {
            if (!method.isConstructor() && !method.hasModifierProperty(PsiModifier.PRIVATE)) {
                this.nonPrivateMethods.add(new MemberSignature(method));
            }
        }

        this.nonPrivateFields = new HashSet<MemberSignature>();
        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.PRIVATE) ||
                !field.hasModifierProperty(PsiModifier.STATIC) && !field.hasModifierProperty(PsiModifier.TRANSIENT)) {
                this.nonPrivateFields.add(new MemberSignature(field));
            }
        }

        this.staticInitializers = new ArrayList<MemberSignature>();
        for (final PsiClassInitializer initializer : psiClass.getInitializers()) {
            final PsiModifierList modifierList = initializer.getModifierList();

            if (modifierList != null && modifierList.hasModifierProperty(PsiModifier.STATIC)) {
                this.staticInitializers.add(MemberSignature.getStaticInitializerMemberSignature());
                break;
            }
        }

        if (this.staticInitializers.isEmpty()) {
            for (final PsiField field : psiClass.getFields()) {
                if (hasStaticInitializer(field)) {
                    this.staticInitializers.add(MemberSignature.getStaticInitializerMemberSignature());
                    break;
                }
            }
        }

        this.nonPrivateConstructors = new HashSet<MemberSignature>();

        final PsiMethod[] constructors = psiClass.getConstructors();

        if (constructors.length == 0 && !psiClass.isInterface()) {
            final MemberSignature constructorSignature;

            if (psiClass.hasModifierProperty(PsiModifier.PUBLIC)) {
                constructorSignature = MemberSignature.getPublicConstructor();
            } else {
                constructorSignature = MemberSignature.getPackagePrivateConstructor();
            }
            this.nonPrivateConstructors.add(constructorSignature);
        }
        for (PsiMethod constructor : constructors) {
            if (!constructor.hasModifierProperty(PsiModifier.PRIVATE)) {
                final MemberSignature constructorSignature = new MemberSignature(constructor);
                this.nonPrivateConstructors.add(constructorSignature);
            }
        }
    }

    @SuppressWarnings({"TypeMayBeWeakened"})
    public static boolean isUIDField(@Nullable PsiField field) {
        return (field != null && SERIAL_FIELD_NAME.equals(field.getName()));
    }

    @Nullable public static String getFullDeclaration(String sourceFileExtension, long serial) {
        for (Language language : Language.values()) {
            if (language.fileExtension.equals(sourceFileExtension)) {
                return language.format.format(new String[] { Long.toString(serial) });
            }
        }
        return null;
    }

    public static long computeDefaultSUID(@Nullable PsiClass psiClass) {
        if (psiClass == null) {
            return -1L;
        }

        final PsiManager    manager      = psiClass.getManager();
        final Project       project      = manager.getProject();
        final JavaPsiFacade psiFacade    = JavaPsiFacade.getInstance(project);
        final PsiClass      serializable = psiFacade.findClass(SERIALIZABLE_CLASS_NAME, GlobalSearchScope.allScope(project));

        if (serializable == null) {
            return -1L;
        }

        final boolean isSerializable = psiClass.isInheritor(serializable, true);

        if (!isSerializable) {
            return 0L;
        }

        final SerialVersionUIDBuilder serialVersionUIDBuilder = new SerialVersionUIDBuilder(psiClass);

        try {
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            final DataOutputStream      dataOutputStream      = new DataOutputStream(byteArrayOutputStream);
            final String                className             = psiClass.getQualifiedName();

            dataOutputStream.writeUTF(className);

            final PsiModifierList   classModifierList = psiClass.getModifierList();
            int                     classModifiers    = MemberSignature.calculateModifierBitmap(classModifierList);
            final MemberSignature[] methodSignatures  = serialVersionUIDBuilder.getNonPrivateMethodSignatures();

            if (psiClass.isInterface()) {
                classModifiers |= 0x200;
                if (methodSignatures.length == 0) {
                    classModifiers &= 0xfffffbff;
                }
            }
            dataOutputStream.writeInt(classModifiers);

            final PsiClass[] interfaces = psiClass.getInterfaces();

            Arrays.sort(interfaces, INTERFACE_COMPARATOR);
            for (PsiClass aInterfaces : interfaces) {
                final String name = aInterfaces.getQualifiedName();
                dataOutputStream.writeUTF(name);
            }

            final MemberSignature[] nonPrivateFields       = serialVersionUIDBuilder.getNonPrivateFields();
            final MemberSignature[] staticInitializers     = serialVersionUIDBuilder.getStaticInitializers();
            final MemberSignature[] nonPrivateConstructors = serialVersionUIDBuilder.getNonPrivateConstructors();

            Arrays.sort(nonPrivateFields);
            Arrays.sort(nonPrivateConstructors);
            Arrays.sort(methodSignatures);

            writeMemberSignatures(dataOutputStream, nonPrivateFields);
            writeMemberSignatures(dataOutputStream, staticInitializers);
            writeMemberSignatures(dataOutputStream, nonPrivateConstructors);
            writeMemberSignatures(dataOutputStream, methodSignatures);

            dataOutputStream.flush();

            final MessageDigest digest           = MessageDigest.getInstance("SHA");
            final byte[]        digestBytes      = digest.digest(byteArrayOutputStream.toByteArray());
            long                serialVersionUID = 0L;

            for (int i = Math.min(digestBytes.length, 8) - 1; i >= 0; i--) {
                serialVersionUID = serialVersionUID << 8 | (long)(digestBytes[i] & 0xff);
            }

            return serialVersionUID;

        } catch (IOException exception) {
            final InternalError internalError = new InternalError(exception.getMessage());
            internalError.initCause(exception);
            throw internalError;
        } catch (NoSuchAlgorithmException exception) {
            final RuntimeException securityException = new SecurityException(exception.getMessage());
            securityException.initCause(exception);
            throw securityException;
        }
    }

    private static void writeMemberSignatures(DataOutputStream dataOutputStream, MemberSignature[] signatures) throws IOException {
        for (MemberSignature field : signatures) {
            dataOutputStream.writeUTF(field.getName());
            dataOutputStream.writeInt(field.getModifiers());
            dataOutputStream.writeUTF(field.getSignature());
        }
    }

    private void createClassObjectAccessSynthetics(PsiType type) {
        if (!this.classObjectAccessExpression) {
            final MemberSignature syntheticMethod = MemberSignature.getClassAccessMethodMemberSignature();
            this.nonPrivateMethods.add(syntheticMethod);
        }
        PsiType unwrappedType = type;
        final StringBuffer fieldNameBuffer;
        if (type instanceof PsiArrayType) {
            fieldNameBuffer = new StringBuffer("array");
            while (unwrappedType instanceof PsiArrayType)  {
                final PsiArrayType arrayType = (PsiArrayType)unwrappedType;
                unwrappedType = arrayType.getComponentType();
                fieldNameBuffer.append('$');
            }
        } else {
            fieldNameBuffer = new StringBuffer(CLASS_ACCESS_METHOD_PREFIX);
        }
        if (unwrappedType instanceof PsiPrimitiveType) {
            final PsiPrimitiveType primitiveType = (PsiPrimitiveType)unwrappedType;
            fieldNameBuffer.append(MemberSignature.createPrimitiveType(primitiveType));
        } else {
            final String text = unwrappedType.getCanonicalText().replace('.', '$');
            fieldNameBuffer.append(text);
        }
        final String fieldName = fieldNameBuffer.toString();
        final MemberSignature memberSignature = new MemberSignature(fieldName, 8, "Ljava/lang/Class;");
        if (!this.nonPrivateFields.contains(memberSignature)) {
            this.nonPrivateFields.add(memberSignature);
        }
        this.classObjectAccessExpression = true;
    }

    private String getAccessMethodIndex(PsiElement element) {
        String cache = this.memberMap.get(element);
        if (cache == null) {
            cache = String.valueOf(this.index);
            this.index++;
            this.memberMap.put(element, cache);
        }
        return cache;
    }

    public MemberSignature[] getNonPrivateConstructors() {
        this.init();
        return this.nonPrivateConstructors.toArray(new MemberSignature[this.nonPrivateConstructors.size()]);
    }

    public MemberSignature[] getNonPrivateFields() {
        this.init();
        return this.nonPrivateFields.toArray(new MemberSignature[this.nonPrivateFields.size()]);
    }

    public MemberSignature[] getNonPrivateMethodSignatures() {
        this.init();
        return this.nonPrivateMethods.toArray(new MemberSignature[this.nonPrivateMethods.size()]);
    }

    public MemberSignature[] getStaticInitializers() {
        this.init();
        return this.staticInitializers.toArray(new MemberSignature[this.staticInitializers.size()]);
    }

    @SuppressWarnings({"TypeMayBeWeakened"})
    private static boolean hasStaticInitializer(PsiField field) {
        if (!field.hasModifierProperty(PsiModifier.STATIC)) {
            return false;
        }
        final PsiManager        manager     = field.getManager();
        final GlobalSearchScope scope       = GlobalSearchScope.allScope(manager.getProject());
        final PsiExpression     initializer = field.getInitializer();

        if (initializer == null) {
            return false;
        }

        final PsiType fieldType  = field.getType();
        final PsiType stringType = PsiType.getJavaLangString(manager, scope);

        return !(field.hasModifierProperty(PsiModifier.FINAL) &&
                 (fieldType instanceof PsiPrimitiveType || fieldType.equals(stringType))) ||
               !PsiUtil.isConstantExpression(initializer);
    }

    private void init() {
        if (this.index < 0) {
            this.index = 0;
            this.psiClass.acceptChildren(this);
        }
    }

    @Override
    public void visitAssertStatement(PsiAssertStatement statement) {
        super.visitAssertStatement(statement);

        if (this.assertStatement) {
            return;
        }

        final MemberSignature memberSignature = MemberSignature.getAssertionsDisabledFieldMemberSignature();

        this.nonPrivateFields.add(memberSignature);

        final Project           project           = this.psiClass.getProject();
        final PsiElementFactory psiElementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
        final PsiClassType      classType         = psiElementFactory.createType(this.psiClass);

        createClassObjectAccessSynthetics(classType);
        if (this.staticInitializers.isEmpty()) {
            final MemberSignature initializerSignature = MemberSignature.getStaticInitializerMemberSignature();

            this.staticInitializers.add(initializerSignature);
        }
        this.assertStatement = true;
    }

    @Override
    public void visitClassObjectAccessExpression(PsiClassObjectAccessExpression expression) {
        final PsiType type = expression.getOperand().getType();

        if (!(type instanceof PsiPrimitiveType)) {
            createClassObjectAccessSynthetics(type);
        }
        super.visitClassObjectAccessExpression(expression);
    }

    @Override
    public void visitMethodCallExpression(PsiMethodCallExpression methodCallExpression) {
        for (PsiExpression expression : methodCallExpression.getArgumentList().getExpressions()) {
            expression.accept(this);
        }

        final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
        methodExpression.accept(this);
    }

    @Override
    public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
        super.visitReferenceElement(reference);

        final PsiElement parentClass = ClassUtils.getContainingClass(reference);

        if (reference.getParent() instanceof PsiTypeElement) {
            return;
        }

        final PsiElement element = reference.resolve();

        if (!(element instanceof PsiClass)) {
            return;
        }

        final PsiClass elementParentClass = ClassUtils.getContainingClass(element);

        if (elementParentClass == null || !elementParentClass.equals(psiClass) || element.equals(parentClass)) {
            return;
        }

        final PsiClass innerClass = (PsiClass)element;

        if (!innerClass.hasModifierProperty(PsiModifier.PRIVATE)) {
            return;
        }
        final PsiMethod[] constructors = innerClass.getConstructors();
        if (constructors.length == 0) {
            getAccessMethodIndex(innerClass);
        }
    }

    @Override
    public void visitReferenceExpression(PsiReferenceExpression expression) {
        final PsiElement element               = expression.resolve();
        final PsiElement elementParentClass    = ClassUtils.getContainingClass(element);
        final PsiElement expressionParentClass = ClassUtils.getContainingClass(expression);

        if (expressionParentClass == null || expressionParentClass.equals(elementParentClass)) {
            return;
        }

        PsiElement parentOfParentClass = ClassUtils.getContainingClass(expressionParentClass);

        while (parentOfParentClass != null && !parentOfParentClass.equals(this.psiClass)) {
            if (!(expressionParentClass instanceof PsiAnonymousClass)) {
                getAccessMethodIndex(expressionParentClass);
            }
            this.getAccessMethodIndex(parentOfParentClass);
            parentOfParentClass = ClassUtils.getContainingClass(parentOfParentClass);
        }

        if (element instanceof PsiField) {
            final PsiField field = (PsiField) element;

            if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
                final boolean isStatic = (field.hasModifierProperty(PsiModifier.STATIC));
                final PsiType type     = field.getType();

                if (isStatic) {
                    // Ignore constants.
                    if (field.hasModifierProperty(PsiModifier.FINAL) && type instanceof PsiPrimitiveType) {
                        final PsiExpression initializer = field.getInitializer();

                        if (PsiUtil.isConstantExpression(initializer)) {
                            return;
                        }
                    }
                }

                // Ignore references to containing classes
                final String        returnTypeSignature = getQualifiedName(MemberSignature.createTypeSignature(type));
                final String        className           = this.psiClass.getQualifiedName();
                final StringBuilder signatureBuffer     = new StringBuilder("(");

                if (!isStatic) {
                    signatureBuffer.append('L')
                                   .append(className)
                                   .append(';');
                }

                final String accessMethodIndex = this.getAccessMethodIndex(field);

                if (!this.psiClass.equals(field.getContainingClass())) {
                    return;
                }

                // Add access methods
                String name = null;

                final PsiElement parent = expression.getParent();

                if (parent instanceof PsiAssignmentExpression) {
                    final PsiAssignmentExpression assignment = (PsiAssignmentExpression)parent;

                    if (assignment.getLExpression().equals(expression)) {
                        name = ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "02";
                        signatureBuffer.append(returnTypeSignature);
                    }

                } else if (parent instanceof PsiPostfixExpression) {
                    final PsiPostfixExpression postfixExpression = (PsiPostfixExpression)parent;
                    final PsiJavaToken operationSign = postfixExpression.getOperationSign();
                    final com.intellij.psi.tree.IElementType tokenType = operationSign.getTokenType();
                    if (tokenType == JavaTokenType.PLUSPLUS) {
                        name = ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "08";
                    } else if (tokenType == JavaTokenType.MINUSMINUS) {
                        name = ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "10";
                    }
                } else if (parent instanceof PsiPrefixExpression) {
                    final PsiPrefixExpression prefixExpression = (PsiPrefixExpression)parent;
                    final PsiJavaToken operationSign = prefixExpression.getOperationSign();
                    final com.intellij.psi.tree.IElementType tokenType = operationSign.getTokenType();
                    if (tokenType == JavaTokenType.PLUSPLUS) {
                        name = ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "04";
                    } else
                    if (tokenType == JavaTokenType.MINUSMINUS) {
                        name = ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "06";
                    }
                }
                if (name == null) {
                    name = ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "00";
                }
                signatureBuffer.append(')')
                               .append(returnTypeSignature);

                final String          signature       = signatureBuffer.toString();
                final MemberSignature methodSignature = new MemberSignature(name, 8, signature);

                this.nonPrivateMethods.add(methodSignature);
            }
        } else if (element instanceof PsiMethod) {
            final PsiMethod method = (PsiMethod) element;

            if (method.hasModifierProperty(PsiModifier.PRIVATE) && psiClass.equals(method.getContainingClass())) {
                final String signature;

                if (method.hasModifierProperty(PsiModifier.STATIC)) {
                    final String s = MemberSignature.createMethodSignature(method);
                    signature = getQualifiedName(s);
                } else {
                    final String        returnTypeSignature = getQualifiedName(MemberSignature.createTypeSignature(method.getReturnType()));
                    final StringBuilder signatureBuffer     = new StringBuilder("(L");

                    signatureBuffer.append(psiClass.getQualifiedName())
                                   .append(';');
                    for (PsiParameter parameter : method.getParameterList().getParameters()) {
                        final String typeSignature = getQualifiedName(MemberSignature.createTypeSignature(parameter.getType()));

                        signatureBuffer.append(typeSignature);
                    }
                    signatureBuffer.append(')')
                                   .append(returnTypeSignature);

                    signature = signatureBuffer.toString();
                }

                final String          accessMethodIndex = getAccessMethodIndex(method);
                final MemberSignature methodSignature   = new MemberSignature(ACCESS_METHOD_NAME_PREFIX + accessMethodIndex + "00", 8, signature);

                this.nonPrivateMethods.add(methodSignature);
            }
        }
    }

    private static String getQualifiedName(String className) {
        return className.replace('/', '.');
    }
}
