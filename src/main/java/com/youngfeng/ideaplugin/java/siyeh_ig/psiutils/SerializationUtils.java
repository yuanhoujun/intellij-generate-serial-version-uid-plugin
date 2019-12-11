package com.youngfeng.ideaplugin.java.siyeh_ig.psiutils;

import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({"UnusedDeclaration"})
public class SerializationUtils {

    private static final String SERIALIZABLE_CLASS_NAME   = "java.io.Serializable";
    private static final String EXTERNALIZABLE_CLASS_NAME = "java.io.Externalizable";

    private SerializationUtils() {
        // Nothing to do
    }

    public static boolean isSerializable(@Nullable PsiClass psiClass) {
        if (psiClass == null) {
            return false;
        }

		final PsiClass serializable = ClassUtils.findPsiClass(psiClass.getManager(), SERIALIZABLE_CLASS_NAME);

		return InheritanceUtil.isInheritorOrSelf(psiClass, serializable, true);
    }

	public static boolean isSerializable(@Nullable KtClass psiClass) {
		if (psiClass == null) {
			return false;
		}

		return isImplementingSerializable(psiClass);
	}

	public static boolean isImplementingSerializable(KtClassOrObject classOrObject) {
		List<KtSuperTypeListEntry> list = classOrObject.getSuperTypeListEntries();

		AtomicBoolean result = new AtomicBoolean(false);
		list.forEach(ktSuperTypeListEntry -> {
			if ("Serializable".equals(ktSuperTypeListEntry.getText()) || "java.io.Serializable".equals(ktSuperTypeListEntry.getText())) {
				result.set(true);
			}
		});

		return result.get();
	}

    public static boolean isExternalizable(@NotNull PsiClass psiClass) {
		final PsiClass serializable = ClassUtils.findPsiClass(psiClass.getManager(), EXTERNALIZABLE_CLASS_NAME);

        return InheritanceUtil.isInheritorOrSelf(psiClass, serializable, true);
    }

    public static boolean isDirectlySerializable(@NotNull PsiClass psiClass) {
        final PsiReferenceList implementsList = psiClass.getImplementsList();

        if (implementsList != null) {
	        for (final PsiJavaCodeReferenceElement aInterfaces : implementsList.getReferenceElements()) {
		        final PsiClass implemented = (PsiClass) aInterfaces.resolve();

		        if (implemented != null && SERIALIZABLE_CLASS_NAME.equals(implemented.getQualifiedName())) {
			        return true;
		        }
	        }

        }
        return false;
    }

    public static boolean hasReadObject(@NotNull PsiClass psiClass) {
	    for (final PsiMethod psiMethod : psiClass.getMethods()) {
		    if (isReadObject(psiMethod)) {
			    return true;
		    }
	    }

        return false;
    }

    public static boolean hasWriteObject(@NotNull PsiClass psiClass) {
	    for (final PsiMethod psiMethod : psiClass.getMethods()) {
		    if (isWriteObject(psiMethod)) {
			    return true;
		    }
	    }

        return false;
    }

    public static boolean isReadObject(@NotNull PsiMethod psiMethod) {
	    return isReadWriteObject(psiMethod, "readObject", "java.io.ObjectInputStream");
    }

	public static boolean isWriteObject(@NotNull PsiMethod psiMethod) {
		return isReadWriteObject(psiMethod, "writeObject", "java.io.ObjectOutputStream");
    }

    public static boolean isReadResolve(@NotNull PsiMethod method) {
	    return isReadWriteResolve(method, "readResolve");
    }

	public static boolean isWriteReplace(@NotNull PsiMethod method) {
		return isReadWriteResolve(method, "writeReplace");
    }

	private static boolean isReadWriteObject(@NotNull PsiMethod psiMethod,
                                             @NotNull String    methodName,
                                             @NotNull String    objectStreamClassName) {
		final String psiMethodName = psiMethod.getName();
		if (!methodName.equals(psiMethodName)) {
			return false;
		}

		final PsiParameterList parameterList = psiMethod.getParameterList();
		final PsiParameter[]   parameters    = parameterList.getParameters();

		if (parameters.length != 1) {
		    return false;
	    }

		final PsiType argType = parameters[0].getType();

		return (TypeUtils.typeEquals(objectStreamClassName, argType) &&
		        TypeUtils.typeEquals("void",                psiMethod.getReturnType()));
	}

	private static boolean isReadWriteResolve(@NotNull PsiMethod method, @NotNull String resolveMethodName) {
		final String methodName = method.getName();

		if (!resolveMethodName.equals(methodName)) {
	        return false;
	    }

		final PsiParameterList parameterList = method.getParameterList();
		final PsiParameter[]   parameters    = parameterList.getParameters();

		return (parameters.length == 0 &&
		        TypeUtils.isJavaLangObject(method.getReturnType()));
	}
}
