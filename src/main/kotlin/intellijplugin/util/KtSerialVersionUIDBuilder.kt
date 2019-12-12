package intellijplugin.util

import com.intellij.psi.impl.cache.ModifierFlags
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.js.translate.utils.generateSignature
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.experimental.and

/**
 * This is a short description.
 *
 * @author Scott Smith 2019-12-11 22:57
 */
fun computeDefaultSUID(ktClass: KtClass?): Long {
    if (null == ktClass) return -1L

    try {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        addClassInfoToOutputStream(ktClass, dos)

        val noPrivateProperties = getNonPrivateProperties(ktClass)
        noPrivateProperties.sortedBy { it.name }.forEach {
            addMemberInfoToOutputStream(it, dos)
        }

        val classInitializers = getClassInitializers(ktClass)
        classInitializers.sortedBy { it.name }.forEach {
            addMemberInfoToOutputStream(it, dos)
        }

        val constructors = getNonPrivateConstructors(ktClass)
        constructors.sortedBy { it.getName() }.forEach {
            addMemberInfoToOutputStream(it, dos)
        }

        val nonPrivateMethods = getNonPrivateMethod(ktClass)
        nonPrivateMethods.sortedBy { it.name }.forEach {
            addMemberInfoToOutputStream(it, dos)
        }

        dos.flush()

        val digest = MessageDigest.getInstance("SHA")
        val digestBytes = digest.digest(baos.toByteArray())
        var serialVersionUID = 0L

        for (i in Math.min(digestBytes.size, 8) - 1 downTo 0) {
            serialVersionUID = serialVersionUID shl 8 or (digestBytes[i] and 0xff.toByte()).toLong()
        }

        return serialVersionUID
    } catch (e: Exception) {
        e.printStackTrace()
        throw e
    }
}

private fun addClassInfoToOutputStream(ktClass: KtClass, dos: DataOutputStream) {
    dos.writeUTF(ktClass.javaClass.canonicalName)

    var modifiers = calculateModifier(ktClass.modifierList)
    Log.debug(msg = "${ktClass.name} -> modifiers: $modifiers")

    val nonPrivateMethods = getNonPrivateMethod(ktClass)
    if (ktClass.isInterface()) {
        modifiers = modifiers or 0x200
        if (nonPrivateMethods.isEmpty()) {
            modifiers = modifiers and -0x401
        }
    }

    dos.writeInt(modifiers)

    val interfaces = ktClass.superTypeListEntries.sortedBy { it.name }
    interfaces.forEach {
        val name = it.name
        Log.debug(msg = "${ktClass.name} -> interface: $name")
        dos.writeUTF(name ?: it.text)
    }

}

private fun addMemberInfoToOutputStream(declaration: KtDeclaration, dos: DataOutputStream) {
    dos.writeUTF(declaration.name ?: declaration.text)
    val modifiers = calculateModifier(declaration.modifierList)
    dos.writeInt(modifiers)
    dos.writeUTF(generateTypeSignature(declaration))
}

private fun calculateModifier(modifierList: KtModifierList?): Int {
    var modifiers = 0

    if (null != modifierList) {
        when {
            modifierList.hasModifier(KtTokens.PUBLIC_KEYWORD) -> {
                modifiers = modifiers or ModifierFlags.PUBLIC_MASK
            }
            modifierList.hasModifier(KtTokens.PRIVATE_KEYWORD) -> {
                modifiers = modifiers or ModifierFlags.PRIVATE_MASK
            }
            modifierList.hasModifier(KtTokens.PROTECTED_KEYWORD) -> {
                modifiers = modifiers or ModifierFlags.PROTECTED_MASK
            }
            modifierList.hasModifier(KtTokens.COMPANION_KEYWORD) -> {
                modifiers = modifiers or ModifierFlags.STATIC_MASK
            }
            modifierList.hasModifier(KtTokens.FINAL_KEYWORD) -> {
                modifiers = modifiers or ModifierFlags.FINAL_MASK
            }
            modifierList.hasModifier(KtModifierKeywordToken.softKeywordModifier("volatile")) -> {
                modifiers = modifiers or ModifierFlags.VOLATILE_MASK
            }
            modifierList.hasModifier(KtModifierKeywordToken.softKeywordModifier("transient")) -> {
                modifiers = modifiers or ModifierFlags.TRANSIENT_MASK
            }
            modifierList.hasModifier(KtTokens.ABSTRACT_KEYWORD) -> {
                modifiers = modifiers or ModifierFlags.ABSTRACT_MASK
            }
        }
    }

    return modifiers
}

private fun getNonPrivateMethod(ktClass: KtClass): List<KtNamedFunction> {
    return ktClass.body?.declarations?.filterIsInstance<KtNamedFunction>()?.filter { !it.isPrivate() } ?: emptyList()
}

private fun getNonPrivateProperties(ktClass: KtClass): List<KtProperty> {
    return ktClass.body?.declarations?.filterIsInstance<KtProperty>()?.filter { !it.isPrivate() } ?: emptyList()
}

private fun getComanionObjects(ktClass: KtClass): KtObjectDeclaration? {
    return ktClass.body?.declarations?.filterIsInstance<KtObjectDeclaration>()?.filter { it.isCompanion() }?.firstOrNull()
}

private fun generateTypeSignature(declaration: KtDeclaration): String {
    val declarationDescriptor = declaration.resolveToDescriptorIfAny(BodyResolveMode.FULL) ?: return ""
    return generateSignature(declarationDescriptor) ?: (declaration.name ?: declaration.text)
}

private fun getClassInitializers(ktClass: KtClass): List<KtClassInitializer> {
    return ktClass.body?.declarations?.filterIsInstance<KtClassInitializer>() ?: emptyList()
}

private fun getNonPrivateConstructors(ktClass: KtClass): List<KtConstructor<*>> {
    return ktClass.body?.declarations?.filterIsInstance<KtConstructor<*>>() ?: emptyList()
}