package intellijplugin.action;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import intellijplugin.util.KtSerialVersionUIDBuilderKt;
import org.jetbrains.kotlin.idea.internal.Location;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtFile;

public final class GenerateSerialVersionUIDAction extends EditorAction {

    public GenerateSerialVersionUIDAction() {
        super(GenerateSerialVersionUIDHandler.INSTANCE);
    }

	@Override
	public void update(Editor editor, Presentation presentation, DataContext dataContext) {
		final Project project = PlatformDataKeys.PROJECT.getData(dataContext);
		boolean       visible = false;
		boolean       enabled = false;

		if (project != null) {
			PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

			if (psiFile instanceof KtFile) {
				Location location = Location.fromEditor(editor, project);
				PsiElement psiElement = psiFile.findElementAt(location.getStartOffset());

				if (null != psiElement) {
					KtClass ktClass = GenerateSerialVersionUIDHandler.getKtClassWith(psiElement);

					visible = GenerateSerialVersionUIDHandler.needsUIDField(ktClass);
					enabled = (visible && !GenerateSerialVersionUIDHandler.hasUIDField(ktClass));

					KtSerialVersionUIDBuilderKt.computeDefaultSUID(ktClass);
				}
			} else if (psiFile instanceof PsiJavaFile) {
				final VirtualFile virtualFile = PlatformDataKeys.VIRTUAL_FILE.getData(dataContext);

				final PsiManager  psiManager  = PsiManager.getInstance(project);
				final PsiClass    psiClass    = GenerateSerialVersionUIDHandler.getPsiClass(virtualFile, psiManager, editor);

				visible = GenerateSerialVersionUIDHandler.needsUIDField(psiClass);
				enabled = (visible && !GenerateSerialVersionUIDHandler.hasUIDField(psiClass));
			}
		}

		presentation.setVisible(visible);
		presentation.setEnabled(enabled);
	}
}
