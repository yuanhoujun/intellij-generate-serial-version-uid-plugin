package com.youngfeng.ideaplugin.java;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiManager;

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
			final VirtualFile virtualFile = PlatformDataKeys.VIRTUAL_FILE.getData(dataContext);

			final PsiManager  psiManager  = PsiManager.getInstance(project);
			final PsiClass    psiClass    = GenerateSerialVersionUIDHandler.getPsiClass(virtualFile, psiManager, editor);

			visible = GenerateSerialVersionUIDHandler.needsUIDField(psiClass);
			enabled = (visible && !GenerateSerialVersionUIDHandler.hasUIDField(psiClass));
		}

		presentation.setVisible(visible);
		presentation.setEnabled(enabled);
	}
}
