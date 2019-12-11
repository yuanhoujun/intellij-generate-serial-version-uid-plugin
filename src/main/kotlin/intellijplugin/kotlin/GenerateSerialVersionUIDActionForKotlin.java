package intellijplugin.kotlin;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.youngfeng.ideaplugin.java.GenerateSerialVersionUIDHandler;
import com.youngfeng.ideaplugin.java.GenerateSerialVersionUIDHandlerForKotlin;
import org.jetbrains.kotlin.idea.internal.Location;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtFile;

public final class GenerateSerialVersionUIDActionForKotlin extends EditorAction {

    public GenerateSerialVersionUIDActionForKotlin() {
        super(GenerateSerialVersionUIDHandlerForKotlin.INSTANCE);
    }

	@Override
	public void update(Editor editor, Presentation presentation, DataContext dataContext) {
		final Project project = PlatformDataKeys.PROJECT.getData(dataContext);

		boolean       visible = false;
		boolean       enabled = false;

		if (null != project) {
			PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

			if (psiFile instanceof KtFile) {
				Location location = Location.fromEditor(editor, project);
				PsiElement psiElement = psiFile.findElementAt(location.getStartOffset());

				if (null != psiElement) {
					KtClass ktClass = GenerateSerialVersionUIDHandler.getKtClassWith(psiElement);

					visible = GenerateSerialVersionUIDHandlerForKotlin.needsUIDField(ktClass);
					enabled = (visible && !GenerateSerialVersionUIDHandlerForKotlin.hasUIDField(ktClass));
				}
			}
		}

		presentation.setVisible(visible);
		presentation.setEnabled(enabled);
	}
}
