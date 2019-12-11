package com.youngfeng.ideaplugin.java.siyeh_ig.psiutils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings({"UnusedDeclaration"})
public class ClassUtils {

	private static final Set<PsiType> integralTypes;
	private static final Set<PsiType> primitiveNumericTypes;
    private static final Set<String>  immutableTypes;
	private static final Set<String>  numericTypes;

    private ClassUtils() {
        // Nothing to do
    }

    public static boolean isSubclass(@NotNull PsiClass psiClass, @NotNull String ancestorName) {
	    final PsiClass ancestorClass = ClassUtils.findPsiClass(psiClass.getManager(), ancestorName);

        return InheritanceUtil.isInheritorOrSelf(psiClass, ancestorClass, true);
    }

	public static PsiClass findPsiClass(@NotNull PsiManager psiManager, @NotNull String className) {
		final Project       project   = psiManager.getProject();
		final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);

		return psiFacade.findClass(className, GlobalSearchScope.allScope(project));
	}

    public static PsiClass findPsiClass(@Nullable PsiElement element) {
        while (true) {
            final PsiClass psiClass = (element instanceof PsiClass) ? (PsiClass) element
                                                                    : PsiTreeUtil.getParentOfType(element, PsiClass.class);

            if (psiClass == null || !(psiClass.getContainingClass() instanceof PsiAnonymousClass)) {
                return psiClass;
            }
            element = psiClass.getParent();
        }
    }

    // 用于处理Kotlin问题
    @Nullable
    public static KtClass findKtClass(@NotNull PsiElement leaf) {
        if (!(leaf.getContainingFile() instanceof KtFile)) return null;
        KtFile jetFile = (KtFile) leaf.getContainingFile();

        return PsiTreeUtil.getParentOfType(leaf, KtClass.class, false);
    }

	public static boolean isPrimitive(@NotNull PsiType type) {
        return TypeConversionUtil.isPrimitiveAndNotNull(type);
    }

    public static boolean isIntegral(@Nullable PsiType type) {
        return (type != null && integralTypes.contains(type));
    }

    public static boolean isImmutable(@NotNull PsiType type) {
        return (TypeConversionUtil.isPrimitiveAndNotNull(type) || immutableTypes.contains(type.getCanonicalText()));
    }

    public static boolean inSamePackage(@Nullable PsiClass class1, @Nullable PsiClass class2) {
	    final String className1 = (class1 == null) ? null : class1.getQualifiedName();
        final String className2 = (class2 == null) ? null : class2.getQualifiedName();

	    return (className1 != null && className2 != null &&
	            getClassPackageName(className1).equals(getClassPackageName(className2)));
    }

	private static String getClassPackageName(@NotNull String className) {
		final int lastDotIndex = className.lastIndexOf('.');

		if (lastDotIndex < 0) {
		    return "";
		}
		return className.substring(0, lastDotIndex);
	}

	@SuppressWarnings({"SimplifiableIfStatement", "TypeMayBeWeakened"})
    public static boolean isFieldVisible(@Nullable PsiField field, @NotNull PsiClass fromClass) {
        if (field == null) {
            return false;
        }
        final PsiClass fieldClass = field.getContainingClass();
        if (fieldClass == null) {
            return false;
        }
        if (fieldClass.equals(fromClass)) {
            return true;
        }
        if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
            return false;
        }
        if (field.hasModifierProperty(PsiModifier.PUBLIC) || field.hasModifierProperty(PsiModifier.PROTECTED)) {
            return true;
        }
		return inSamePackage(fieldClass, fromClass);
    }

    public static boolean isWrappedNumericType(@Nullable PsiType type) {
	    return (type instanceof PsiClassType &&
	            numericTypes.contains(((PsiClassType) type).getClassName()));

    }

    public static boolean isPrimitiveNumericType(@Nullable PsiType type) {
        return (type != null && primitiveNumericTypes.contains(type));
    }

    @SuppressWarnings({"TypeMayBeWeakened"})
    public static boolean isInnerClass(@Nullable PsiClass psiClass) {
	    return (getContainingClass(psiClass) != null);
    }

    public static PsiClass getContainingClass(@Nullable PsiElement psiClass) {
        return PsiTreeUtil.getParentOfType(psiClass, PsiClass.class);
    }

    public static PsiClass getOutermostContainingClass(@Nullable PsiClass psiClass) {
	    PsiClass outerClass = psiClass;

        do {
            final PsiClass containingClass = getContainingClass(outerClass);
	        if (containingClass == null) {
                return outerClass;
			}
			outerClass = containingClass;
        } while (true);
    }

    public static boolean isClassVisibleFromClass(@NotNull PsiClass baseClass, @NotNull PsiClass referencedClass) {
        if (referencedClass.hasModifierProperty(PsiModifier.PUBLIC)) {
            return true;
        } else if (referencedClass.hasModifierProperty(PsiModifier.PRIVATE)) {
            return (PsiTreeUtil.findCommonParent(baseClass, referencedClass) != null);
        } else {
            return inSamePackage(baseClass, referencedClass);
        }
    }

    static {
        final PsiType[] integralTypeArray = { TypeUtils.getPsiType("LONG"),  TypeUtils.getPsiType("INT"),
                                              TypeUtils.getPsiType("SHORT"), TypeUtils.getPsiType("CHAR"),
                                              TypeUtils.getPsiType("BYTE") };
        final PsiType[] primitiveNumericTypeArray = { TypeUtils.getPsiType("BYTE"),  TypeUtils.getPsiType("CHAR"),
                                                      TypeUtils.getPsiType("SHORT"), TypeUtils.getPsiType("INT"),
                                                      TypeUtils.getPsiType("LONG"),  TypeUtils.getPsiType("FLOAT"),
                                                      TypeUtils.getPsiType("DOUBLE") };

        final String[] immutableTypeArray = { "java.lang.Boolean", "java.lang.Char", "java.lang.Short",
                                              "java.lang.Integer", "java.lang.Long", "java.lang.Float",
                                              "java.lang.Double",  "java.lang.Byte", "java.lang.String",
                                              "java.awt.Font",     "java.awt.Color" };
        final String[] numericTypeArray = { "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                                            "java.lang.Long", "java.lang.Float", "java.lang.Double" };

        integralTypes         = new HashSet<PsiType>(Arrays.asList(integralTypeArray));
        primitiveNumericTypes = new HashSet<PsiType>(Arrays.asList(primitiveNumericTypeArray));
        immutableTypes        = new HashSet<String> (Arrays.asList(immutableTypeArray));
		numericTypes          = new HashSet<String> (Arrays.asList(numericTypeArray));
    }
}
