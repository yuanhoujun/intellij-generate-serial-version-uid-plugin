package intellijplugin.java.siyeh_ig.fixes;

import com.intellij.psi.*;
import intellijplugin.java.siyeh_ig.psiutils.ClassUtils;
import intellijplugin.java.siyeh_ig.psiutils.TypeUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MemberSignature implements Comparable<MemberSignature> {

	private static final String          CONSTRUCTOR_NAME      = "<init>";
	private static final String          INITIALIZER_SIGNATURE = "()V";
	private static final MemberSignature ASSERTIONS_DISABLED_FIELD;
	private static final MemberSignature CLASS_ACCESS_METHOD;
	private static final MemberSignature PACKAGE_PRIVATE_CONSTRUCTOR;
	private static final MemberSignature PUBLIC_CONSTRUCTOR;
	private static final MemberSignature STATIC_INITIALIZER;

    static {
        ASSERTIONS_DISABLED_FIELD   = new MemberSignature("$assertionsDisabled", 24, "Z");
        CLASS_ACCESS_METHOD         = new MemberSignature("class$",               8, "(Ljava.lang.String;)Ljava.lang.Class;");
        PACKAGE_PRIVATE_CONSTRUCTOR = new MemberSignature(CONSTRUCTOR_NAME,       0, INITIALIZER_SIGNATURE);
        PUBLIC_CONSTRUCTOR          = new MemberSignature(CONSTRUCTOR_NAME,       1, INITIALIZER_SIGNATURE);
        STATIC_INITIALIZER          = new MemberSignature("<clinit>",             8, INITIALIZER_SIGNATURE);
    }

	private final int    modifiers;
	private final String name;
	private final String signature;

    public MemberSignature(@NotNull PsiField field) {
		this.modifiers = calculateModifierBitmap(field.getModifierList());
		this.name      = field.getName();
		this.signature = createTypeSignature(field.getType());
    }

    public MemberSignature(@NotNull PsiMethod method) {
		this.modifiers = calculateModifierBitmap(method.getModifierList());
		this.signature = createMethodSignature(method).replace('/', '.');
		this.name      = (method.isConstructor() ? CONSTRUCTOR_NAME : method.getName());
    }

    public MemberSignature(@NotNull String name, int modifiers, @NotNull String signature) {
		this.name      = name;
		this.modifiers = modifiers;
		this.signature = signature;
    }

    public static int calculateModifierBitmap(@Nullable PsiModifierList modifierList) {
        int modifiers = 0;

        if (modifierList != null) {
            if (modifierList.hasModifierProperty(PsiModifier.PUBLIC)) {
                modifiers |= 1;
            }
            if (modifierList.hasModifierProperty(PsiModifier.PRIVATE)) {
                modifiers |= 2;
            }
            if (modifierList.hasModifierProperty(PsiModifier.PROTECTED)) {
                modifiers |= 4;
            }
            if (modifierList.hasModifierProperty(PsiModifier.STATIC)) {
                modifiers |= 8;
            }
            if (modifierList.hasModifierProperty(PsiModifier.FINAL)) {
                modifiers |= 0x10;
            }
            if (modifierList.hasModifierProperty(PsiModifier.VOLATILE)) {
                modifiers |= 0x40;
            }
            if (modifierList.hasModifierProperty(PsiModifier.TRANSIENT)) {
                modifiers |= 0x80;
            }
            if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) {
                modifiers |= 0x400;
            }
            if (modifierList.hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
                modifiers |= 0x20;
            }
            if (modifierList.hasModifierProperty(PsiModifier.NATIVE)) {
                modifiers |= 0x100;
            }
            if (modifierList.hasModifierProperty(PsiModifier.STRICTFP)) {
                modifiers |= 0x800;
            }
        }
        return modifiers;
    }

    public int compareTo(@NotNull MemberSignature other) {
        final int result = name.compareTo(other.name);

	    return (result == 0) ? signature.compareTo(other.signature) : result;
    }

    @NotNull public static String createMethodSignature(@NotNull PsiMethod method) {
        final StringBuilder signatureBuffer = new StringBuilder("(");

	    for (final PsiParameter parameter : method.getParameterList().getParameters()) {
		    signatureBuffer.append(createTypeSignature(parameter.getType()));
	    }

	    final PsiType returnType = method.getReturnType();

        signatureBuffer.append(')')
	                   .append(createTypeSignature((returnType == null) ? PsiType.VOID : returnType));

        return signatureBuffer.toString();
    }

    @NotNull public static String createPrimitiveType(@Nullable PsiPrimitiveType primitiveType) {
        final String primitiveTypeSignature = TypeUtils.PRIMITIVE_TYPES.get(primitiveType);

        if (primitiveTypeSignature == null) {
            throw new InternalError();
        }
        return primitiveTypeSignature;
    }

    public static String createTypeSignature(@NotNull PsiType type) {
        final StringBuilder buffer       = new StringBuilder();
        PsiType             internalType = type;
        PsiArrayType        arrayType;

        while (internalType instanceof PsiArrayType) {
            buffer.append('[');
            arrayType    = (PsiArrayType) internalType;
            internalType = arrayType.getComponentType();
        }

        if (internalType instanceof PsiPrimitiveType) {
            final PsiPrimitiveType primitiveType          = (PsiPrimitiveType) internalType;
            final String           primitypeTypeSignature = createPrimitiveType(primitiveType);

            buffer.append(primitypeTypeSignature);
        } else {
            buffer.append('L');

            if (internalType instanceof PsiClassType) {
                final PsiClassType classType = (PsiClassType)internalType;
                PsiClass           psiClass  = classType.resolve();

                if (psiClass != null) {
                    final StringBuilder postFix         = new StringBuilder();
                    PsiClass            containingClass = ClassUtils.getContainingClass(psiClass);

                    while (containingClass != null) {
                        postFix.insert(0, psiClass.getName()).insert(0, '$');
                        psiClass        = containingClass;
                        containingClass = ClassUtils.getContainingClass(psiClass);
                    }

                    final String qualifiedName = psiClass.getQualifiedName();

                    if (qualifiedName == null) {
                        buffer.append("java.lang.Object");
                    } else {
                        buffer.append(qualifiedName.replace('.', '/')).append(postFix);
                    }
                }
            } else {
                buffer.append(internalType.getCanonicalText().replace('.', '/'));
            }
            buffer.append(';');
        }
        return buffer.toString();
    }

    @NotNull public static MemberSignature getAssertionsDisabledFieldMemberSignature() {
        return ASSERTIONS_DISABLED_FIELD;
    }

    @NotNull public static MemberSignature getClassAccessMethodMemberSignature() {
        return CLASS_ACCESS_METHOD;
    }

    public int getModifiers() {
        return this.modifiers;
    }

    public String getName() {
        return this.name;
    }

    public String getSignature() {
        return this.signature;
    }

    @NotNull public static MemberSignature getPackagePrivateConstructor() {
        return PACKAGE_PRIVATE_CONSTRUCTOR;
    }

    @NotNull public static MemberSignature getPublicConstructor() {
        return PUBLIC_CONSTRUCTOR;
    }

    @NotNull public static MemberSignature getStaticInitializerMemberSignature() {
        return STATIC_INITIALIZER;
    }

    @Override public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }

        final MemberSignature that = (MemberSignature) object;

        return (this.modifiers     == that.modifiers &&
                this.name     .equals(that.name)     &&
                this.signature.equals(that.signature));

    }

	@Override
    public int hashCode() {
        return this.name.hashCode() + this.signature.hashCode();
    }

	@Override
    public String toString() {
        return this.name + this.signature;
    }
}
