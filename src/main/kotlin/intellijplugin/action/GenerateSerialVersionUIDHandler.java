package intellijplugin.action;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.IncorrectOperationException;
import intellijplugin.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.elements.KtLightElement;
import org.jetbrains.kotlin.idea.internal.Location;
import org.jetbrains.kotlin.psi.*;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GenerateSerialVersionUIDHandler extends EditorWriteActionHandler {

	private static final Logger                   LOGGER   = Logger.getInstance(GenerateSerialVersionUIDHandler.class.getName());
	public  static final EditorWriteActionHandler INSTANCE = new GenerateSerialVersionUIDHandler();
	private static       boolean                  _showing;
	private static final boolean                  m_ignoreSerializableDueToInheritance = false;

	private GenerateSerialVersionUIDHandler() {
        // Nothing to do
	}

	public final void executeWriteAction(@Nullable Editor editor, @Nullable DataContext dataContext) {
        if (editor == null) {
            LOGGER.debug("editor == null");
            displayMessage("No editor found.");
            return;
        }
        if (dataContext == null) {
            LOGGER.debug("dataContext == null");
            displayMessage("No data context.");
            return;
        }

		final Project     project     = DataKeys.PROJECT     .getData(dataContext);
		final VirtualFile virtualFile = DataKeys.VIRTUAL_FILE.getData(dataContext);

		if (project == null) {
			LOGGER.debug("project == null");
			displayMessage("No project found.");
			return;
		}
        if (virtualFile == null) {
            LOGGER.debug("virtualFile == null");
            displayMessage("No file found.");
            return;
        }

		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
		if (psiFile instanceof PsiJavaFile) {
			final PsiManager manager  = PsiManager.getInstance(project);
			final PsiClass   psiClass = getPsiClass(virtualFile, manager, editor);

			if (psiClass == null) {
				LOGGER.debug("psiClass == null");
				displayMessage("Not a Java class file.");
				return;
			}

			final long serialVersionUIDValue = SerialVersionUIDBuilder.computeDefaultSUID(psiClass);

			if (needsUIDField(psiClass) && !hasUIDField(psiClass, serialVersionUIDValue)) {
				insertSerialVersionUID(project, virtualFile.getExtension(), psiClass, serialVersionUIDValue);
			}
		} else if (psiFile instanceof KtFile) {
			Location location = Location.fromEditor(editor, project);
			PsiElement psiElement = psiFile.findElementAt(location.getStartOffset());

			KtClass ktClass = null;
			if (null != psiElement) {
				ktClass = GenerateSerialVersionUIDHandler.getKtClassWith(psiElement);
			}

			if (ktClass == null) {
				LOGGER.debug("ktClass == null");
				displayMessage("Not a Kotlin class file.");
				return;
			}

			if (needsUIDField(ktClass) && !hasUIDField(ktClass)) {
				Long serialVersionUID = KtSerialVersionUIDBuilderKt.computeDefaultSUID(ktClass);
				insertSerialVersionUID(editor, ktClass, serialVersionUID);
			}
		}
	}

	@Nullable public static PsiClass getPsiClass(@Nullable VirtualFile virtualFile,
                                                 @NotNull  PsiManager  manager,
                                                 @NotNull  Editor      editor) {
		final PsiFile psiFile = (virtualFile == null) ? null : manager.findFile(virtualFile);

        if (psiFile == null) {
            return null;
        }
        final PsiElement elementAtCaret = psiFile.findElementAt(editor.getCaretModel().getOffset());

        return ClassUtils.findPsiClass(elementAtCaret);
	}

	@Nullable public static KtClass getKtClassWith(PsiElement psiElement) {
		if (psiElement instanceof KtLightElement) {
			PsiElement origin = ((KtLightElement) psiElement).getKotlinOrigin();
			if (origin != null) {
				return getKtClassWith(origin);
			} else {
				return null;
			}

		} else if (psiElement instanceof KtClass && !((KtClass) psiElement).isEnum() &&
				!((KtClass) psiElement).isInterface() &&
				!((KtClass) psiElement).isAnnotation() &&
				!((KtClass) psiElement).isSealed()) {
			return (KtClass) psiElement;

		} else {
			PsiElement parent = psiElement.getParent();
			if (parent == null) {
				return null;
			} else {
				return getKtClassWith(parent);
			}
		}
	}

	private static void insertSerialVersionUID(Project project, String extension, PsiClass psiClass, long serial) {
		final PsiElementFactory psiElementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
		final CodeStyleManager  codeStyleManager  = CodeStyleManager.getInstance(project);

		if (psiElementFactory != null && codeStyleManager != null) {
			try {
                final String   fullDeclaration = SerialVersionUIDBuilder.getFullDeclaration(extension, serial);
                final PsiField psiField        = psiElementFactory.createFieldFromText(fullDeclaration, null);

				if (psiField != null) {
                    final PsiField oldPsiField = getUIDField(psiClass);

                    codeStyleManager.reformat(psiField);
                    if (oldPsiField != null) {
                        oldPsiField.replace(psiField);
                    } else {
                        psiClass.add(psiField);
                    }
                }
			} catch (IncorrectOperationException e) {
				LOGGER.info("Could not insert field", e);
			}
		}
	}

	private static void insertSerialVersionUID(Editor editor, KtClass ktClass, Long serialVersionUID) {
		final CodeStyleManager  codeStyleManager  = CodeStyleManager.getInstance(ktClass.getProject());
		KtPsiFactory elementFactory = new KtPsiFactory(ktClass.getProject());

		KtClassBody body = ktClass.getBody();

		if (null != body) {
			List<KtDeclaration> declarations = body.getDeclarations();

			AtomicBoolean hasSerialVersionUID = new AtomicBoolean(false);
			AtomicBoolean hasCompanionObject = new AtomicBoolean(false);

			if (declarations.size() > 0) {
				declarations.forEach(declaration -> {
					if (declaration instanceof KtObjectDeclaration) {
						KtObjectDeclaration objectDeclaration = (KtObjectDeclaration) declaration;

						if (objectDeclaration.isCompanion()) {
							KtClassBody companionBody = objectDeclaration.getBody();

							if (null != companionBody) {
								List<KtProperty> properties = companionBody.getProperties();

								properties.forEach(prop -> {
									if ("serialVersionUID".equals(prop.getName())) {
										hasSerialVersionUID.set(true);
									}
								});

								if (!hasSerialVersionUID.get()) {
									String block1 = "\n\t\tconst val serialVersionUID = " + serialVersionUID + "L\n";

									KtProperty property = elementFactory.createProperty("const", "serialVersionUID", "Long", false, serialVersionUID + "L");

									objectDeclaration.getBody().addAfter(property, objectDeclaration.getBody().getFirstChild());
									codeStyleManager.reformat(objectDeclaration);
								}
							}
							hasCompanionObject.set(true);
						}
					}
				});
			}

			if (!hasCompanionObject.get()) {
				StringBuilder block = new StringBuilder("\tcompanion object {\n");
				block.append("\t\tconst val serialVersionUID = ")
						.append(serialVersionUID)
						.append("L\n")
						.append("\t}\n");

				KtObjectDeclaration companionObject = elementFactory.createCompanionObject();


				String block1 = "\n\t\tconst val serialVersionUID = " + serialVersionUID + "L\n";

				companionObject.getBody().addAfter(elementFactory.createBlockCodeFragment(block1, companionObject), companionObject.getBody().getFirstChild());

				ktClass.getBody().addAfter(companionObject, ktClass.getBody().getFirstChild());
				codeStyleManager.reformat(companionObject);
			}
		}
	}

	private static void displayMessage(@NotNull final String message) {
		SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					try {
						if (!GenerateSerialVersionUIDHandler._showing) {
							GenerateSerialVersionUIDHandler._showing = true;
							Messages.showErrorDialog(message, "Error");
						}
					} finally {
						GenerateSerialVersionUIDHandler._showing = false;
					}
				}
			});
	}

	public static boolean needsUIDField(@Nullable PsiClass aClass) {
		if (aClass == null) {
			return false;
		}
		if (aClass.isInterface() || aClass.isAnnotationType() || aClass.isEnum()) {
			return false;
		}
		if (aClass instanceof PsiTypeParameter || aClass instanceof PsiAnonymousClass) {
			return false;
		}

		if (m_ignoreSerializableDueToInheritance) {
			if (!SerializationUtils.isDirectlySerializable(aClass)) {
				return false;
			}
		} else if (!SerializationUtils.isSerializable(aClass)) {
			return false;
		}
		return true;
	}

	public static boolean needsUIDField(@Nullable KtClass aClass) {
		if (aClass == null) {
			return false;
		}
		if (aClass.isInterface() || aClass.isAnnotation() || aClass.isEnum()) {
			return false;
		}
		//if (aClass instanceof PsiTypeParameter || aClass instanceof PsiAnonymousClass) {
		//	return false;
		//}

		if (m_ignoreSerializableDueToInheritance) {
			//if (!SerializationUtils.isDirectlySerializable(aClass)) {
			//	return false;
			//}
		} else if (!SerializationUtils.isSerializable(aClass)) {
			return false;
		}
		return true;
	}

    @Nullable public static PsiField getUIDField(@Nullable PsiClass psiClass) {
        if (psiClass != null) {
            for (final PsiField field : psiClass.getFields()) {
                if (SerialVersionUIDBuilder.isUIDField(field)) {
                    return field;
                }
            }
        }
        return null;
    }

	public static boolean hasUIDField(@Nullable PsiClass psiClass) {
		return hasUIDField(psiClass, SerialVersionUIDBuilder.computeDefaultSUID(psiClass));
	}

	public static boolean hasUIDField(@Nullable PsiClass psiClass, long serialVersionUIDValue) {
		final PsiField field = getUIDField(psiClass);

		if (field != null) {
			PsiExpression initializer = field.getInitializer();
            int           sign        = 1;

            if (initializer instanceof PsiPrefixExpression) {
                final PsiPrefixExpression prefixExpression = (PsiPrefixExpression) initializer;

                if (prefixExpression.getOperationSign().getTokenType() == JavaTokenType.MINUS) {
                    sign = -1;
                }
                initializer = prefixExpression.getOperand();
            }

            final Object literalValue = (initializer instanceof PsiLiteral) ? ((PsiLiteral) initializer).getValue() : null;

			return (literalValue instanceof Long && (((Long) literalValue) * sign) == serialVersionUIDValue);
		}
		return false;
	}

	public static boolean hasUIDField(@Nullable KtClass psiClass) {
		return hasUIDField(psiClass, KtSerialVersionUIDBuilderKt.computeDefaultSUID(psiClass));
	}

	public static boolean hasUIDField(@Nullable KtClass psiClass, long serialVersionUIDValue) {
		if (null == psiClass) return false;

		List<KtObjectDeclaration> companionObjects = psiClass.getCompanionObjects();
		AtomicBoolean result = new AtomicBoolean(false);
		companionObjects.forEach(value -> {
			KtClassBody body = value.getBody();
			if (null != body) {
				List<KtProperty> properties = body.getProperties();
				properties.forEach(prop -> {
					if ("serialVersionUID".equals(prop.getName())) {
						KtExpression initializer = prop.getInitializer();
						if (null != initializer) {
							String initialValue = initializer.getText();
							if (null != initialValue && initialValue.length() > 0) {
								if (Long.parseLong(initialValue) == serialVersionUIDValue) {
									result.set(true);
								}
							}
						}
					}
				});
			}
		});

		return result.get();
	}
}
