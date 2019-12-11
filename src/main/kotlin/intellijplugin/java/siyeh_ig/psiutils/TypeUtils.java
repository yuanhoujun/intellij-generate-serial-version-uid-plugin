package intellijplugin.java.siyeh_ig.psiutils;

import com.intellij.psi.*;
import gnu.trove.THashMap;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Map;

@SuppressWarnings({"UnusedDeclaration"})
public class TypeUtils {

    public static final Map<PsiType, String> PRIMITIVE_TYPES;

    static {
        PRIMITIVE_TYPES = new THashMap<PsiType, String>(10);
        PRIMITIVE_TYPES.put(getPsiType("BYTE"),    "B");
        PRIMITIVE_TYPES.put(getPsiType("CHAR"),    "C");
        PRIMITIVE_TYPES.put(getPsiType("DOUBLE"),  "D");
        PRIMITIVE_TYPES.put(getPsiType("FLOAT"),   "F");
        PRIMITIVE_TYPES.put(getPsiType("INT"),     "I");
        PRIMITIVE_TYPES.put(getPsiType("LONG"),    "J");
        PRIMITIVE_TYPES.put(getPsiType("SHORT"),   "S");
        PRIMITIVE_TYPES.put(getPsiType("VOID"),    "V");
        PRIMITIVE_TYPES.put(getPsiType("BOOLEAN"), "Z");
    }

    private TypeUtils() {
        // Nothing to do
    }

    public static boolean expressionHasType(@Nullable String typeName, @Nullable PsiExpression expression) {
	    return (expression != null && typeEquals(typeName, expression.getType()));
    }

    public static boolean typeEquals(@Nullable String typeName, @Nullable PsiType targetType) {
	    return (targetType != null && targetType.equalsToText(typeName));
    }

    public static boolean isJavaLangObject(@Nullable PsiType targetType) {
        return typeEquals("java.lang.Object", targetType);
    }

    public static boolean isJavaLangString(@Nullable PsiType targetType) {
        return typeEquals("java.lang.String", targetType);
    }

    public static boolean expressionHasTypeOrSubtype(@Nullable String typeName, @Nullable PsiExpression expression) {
        if (expression == null || typeName == null) {
            return false;
        }
        final PsiType type = expression.getType();

	    if (type == null || !(type instanceof PsiClassType)) {
		    return false;
	    }

		final PsiClass psiClass = ((PsiClassType) type).resolve();

	    return (psiClass != null && ClassUtils.isSubclass(psiClass, typeName));
    }

    /**
     * LONG, INT, SHORT, ... constants are moved from PsiType to PsiPrimitiveType
     * between IDEA 9 and IDEA 10. This method is used to encapsulate their access.
     * @param typeName the name of the constant field (e.g. "LONG")
     * @return the integral type instance
     */
    public static PsiType getPsiType(String typeName) {
        Field typeField = null;
        try {
            typeField = PsiPrimitiveType.class.getField(typeName);
        } catch (NoSuchFieldException e) { /* Ignore */ }

        if (typeField == null) {
            try {
                typeField = PsiType.class.getField(typeName);
            } catch (NoSuchFieldException e) { /* Ignore */ }
        }

        if (typeField != null) {
            try {
                return (PsiType) typeField.get(null);
            } catch (IllegalAccessException e) { /* Ignore */ }
        }

        return null;
    }
}
