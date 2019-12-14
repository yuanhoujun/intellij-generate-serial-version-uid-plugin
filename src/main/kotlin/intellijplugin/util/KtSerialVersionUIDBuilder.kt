package intellijplugin.util

import com.intellij.psi.impl.cache.ModifierFlags
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.idea.refactoring.fqName.getKotlinFqName
import org.jetbrains.kotlin.js.translate.utils.generateSignature
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.nj2k.postProcessing.type
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.psi.psiUtil.isProtected
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.experimental.and

private const val CONSTRUCTOR_NAME = "<init>"
private const val INITIALIZER_SIGNATURE = "()V"

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
        repeat(classInitializers.sortedBy { it.name }.size) {
            addMemberSignatureToOutputStream(
                name = "<clinit>",
                modifiers = 8,
                signature = INITIALIZER_SIGNATURE,
                dos = dos
            )
        }

        val primaryConstructor = ktClass.primaryConstructor

        if (null != primaryConstructor) {
            addMemberSignatureToOutputStream(
                name = primaryConstructor.text,
                modifiers = 1,
                signature = generateTypeSignature(primaryConstructor),
                dos = dos
            )
        } else {
            if (!ktClass.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
                if (ktClass.hasModifier(KtTokens.PROTECTED_KEYWORD)) {
                    addMemberSignatureToOutputStream(
                        name = CONSTRUCTOR_NAME,
                        modifiers = 0,
                        signature = INITIALIZER_SIGNATURE,
                        dos = dos
                    )
                } else {
                    addMemberSignatureToOutputStream(
                        name = CONSTRUCTOR_NAME,
                        modifiers = 1,
                        signature = INITIALIZER_SIGNATURE,
                        dos = dos
                    )
                }
            }
        }

        ktClass.secondaryConstructors.filter { !it.isPrivate() }.sortedBy { it.text }.forEach {
            addMemberSignatureToOutputStream(
                name = CONSTRUCTOR_NAME,
                modifiers = if (it.isProtected()) 0 else 1,
                signature = INITIALIZER_SIGNATURE,
                dos = dos
            )
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

        val numberStr = if (serialVersionUID >= 0) "$serialVersionUID" else
            "$serialVersionUID".substring(1, "$serialVersionUID".length)

        val symbolStr = if (serialVersionUID < 0) "-" else ""

        if (numberStr.length < 9) {
            val prefixBuilder = StringBuilder("9")
            val zeroNumber = 8 - numberStr.length

            repeat(zeroNumber - 1) {
                prefixBuilder.append("0")
            }

            serialVersionUID = "$symbolStr$prefixBuilder$numberStr".toLong()
        }

        return serialVersionUID
    } catch (e: Exception) {
        e.printStackTrace()
        throw e
    }
}

private fun addImportsInfoToOutputStream(ktClass: KtClass, dos: DataOutputStream) {
    ktClass.containingKtFile.importDirectives.filter { it.isValidImport }.sortedBy { it.text }.forEach {
        Log.debug(msg = "import: ${it.text}")
        dos.writeUTF(it.text)
    }
}

private fun addClassInfoToOutputStream(ktClass: KtClass, dos: DataOutputStream) {
    val className = ktClass.getKotlinFqName()?.asString() ?: (ktClass.name ?: ktClass.text)
    dos.writeUTF(className)

    Log.debug(msg = "KtClass name: ${ktClass.getKotlinFqName()?.asString()}, text: ${ktClass.text}")

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

    addImportsInfoToOutputStream(ktClass, dos)

    val interfaces = ktClass.superTypeListEntries.sortedBy { it.text }

    interfaces.forEach {
        val name = it.text
        Log.debug(msg = "${className} -> interface: $name")
        dos.writeUTF(name)
    }
}

private fun addMemberInfoToOutputStream(declaration: KtDeclaration, dos: DataOutputStream) {
    var name = declaration.name ?: declaration.text

    if (declaration is KtProperty) {
        name = declaration.type()?.fqName.toString() ?: declaration.name
    }
    addMemberSignatureToOutputStream(
        name = name,
        modifiers = calculateModifier(declaration.modifierList),
        signature = generateTypeSignature(declaration),
        dos = dos
    )
}

private fun addMemberSignatureToOutputStream(name: String, modifiers: Int, signature: String, dos: DataOutputStream) {
    dos.writeUTF(name)
    dos.writeInt(modifiers)
    dos.writeUTF(signature)
}

private fun calculateModifier(modifierList: KtModifierList?): Int {
    var modifiers = 0

    if (null == modifierList) {
        modifiers = modifiers or ModifierFlags.PUBLIC_MASK
        modifiers = modifiers or ModifierFlags.FINAL_MASK
        return modifiers
    }

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
        modifierList.hasModifier(KtTokens.INTERNAL_KEYWORD) -> {
            modifiers = modifiers or ModifierFlags.PACKAGE_LOCAL_MASK
        }
    }

    if (!modifierList.hasModifier(KtTokens.PUBLIC_KEYWORD) &&
        !modifierList.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
        !modifierList.hasModifier(KtTokens.PROTECTED_KEYWORD)) {
        modifiers = modifiers or ModifierFlags.PUBLIC_MASK
    }

    if (!modifierList.hasModifier(KtTokens.OPEN_KEYWORD) &&
        !modifierList.hasModifier(KtTokens.FINAL_KEYWORD)) {
        modifiers = modifiers or ModifierFlags.FINAL_MASK
    }

    return modifiers
}

private fun getNonPrivateMethod(ktClass: KtClass): List<KtNamedFunction> {
    return ktClass.body?.declarations?.filterIsInstance<KtNamedFunction>()?.filter { !it.isPrivate() } ?: emptyList()
}

private fun getNonPrivateProperties(ktClass: KtClass): List<KtProperty> {
    return ktClass.body?.declarations?.filterIsInstance<KtProperty>()?.filter { !it.isPrivate() } ?: emptyList()
}

private fun generateTypeSignature(declaration: KtDeclaration): String {
    val declarationDescriptor = declaration.resolveToDescriptorIfAny(BodyResolveMode.FULL) ?: return ""
    return generateSignature(declarationDescriptor) ?: (declaration.name ?: declaration.text)
}

private fun getClassInitializers(ktClass: KtClass): List<KtClassInitializer> {
    return ktClass.body?.declarations?.filterIsInstance<KtClassInitializer>() ?: emptyList()
}

private fun getNonPrivateConstructors(ktClass: KtClass): List<KtConstructor<*>> {
    return ktClass.body?.declarations?.filterIsInstance<KtConstructor<*>>()?.filter { !it.isPrivate() } ?: emptyList()
}