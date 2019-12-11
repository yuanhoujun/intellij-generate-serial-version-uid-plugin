package com.youngfeng.ideaplugin.hash

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.cache.ModifierFlags.ABSTRACT_MASK
import com.intellij.psi.impl.cache.ModifierFlags.FINAL_MASK
import com.intellij.psi.impl.cache.ModifierFlags.OPEN_MASK
import com.intellij.psi.impl.cache.ModifierFlags.PRIVATE_MASK
import com.intellij.psi.impl.cache.ModifierFlags.PROTECTED_MASK
import com.intellij.psi.impl.cache.ModifierFlags.PUBLIC_MASK
import com.youngfeng.ideaplugin.java.GenerateSerialVersionUIDHandler
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService.Companion.getInstance
import org.jetbrains.kotlin.idea.core.isInheritable
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.js.translate.utils.generateSignature
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.isAbstract
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.psi.psiUtil.isProtected
import org.jetbrains.kotlin.psi.psiUtil.isPublic
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Comparator
import kotlin.experimental.and
import kotlin.math.min

private const val DEFAULT_HASH_VALUE = PUBLIC_MASK
private const val SIGNATURE_PREFIX = "abcdefghijklmnopqrstuvwxyz0123456789"

private val INTERFACE_COMPARATOR: Comparator<KtClass?> = Comparator<KtClass?> { psiClass1, psiClass2 ->
    val name1 = psiClass1?.name
    val name2 = psiClass2?.name
    if (name1 == null) {
        if (name2 == null) 0 else -1
    } else {
        if (name2 == null) +1 else name1.compareTo(name2)
    }
}

fun computeHashCode(ktClass: KtClass, ktClassBody: KtClassBody) = ByteArrayOutputStream().use { baos ->
    DataOutputStream(baos).use { dos ->
        dos.addClassHash(ktClass)
        dos.addConstructorsHash(ktClass)
        dos.computeInitializersHash(ktClassBody)
        dos.addClassBodyHash(ktClassBody)
        dos.flush()
        val messageDigest = MessageDigest.getInstance("SHA")
        val byteArray = messageDigest.digest(baos.toByteArray())
        var hash = 0L
        for (index in min(byteArray.size, 8) - 1 downTo 0) {
            hash = (hash shl 8) or (byteArray[index] and 0xff.toByte()).toLong()
        }

        hash
    }
}

private fun DataOutputStream.addClassHash(ktClass: KtClass) {
    with(ktClass) {
        safeWriteUTF(name)
        writeInt(classModifiers())

        val interfaces = superTypeListEntries
        interfaces.sortedBy { it.text }.forEach {
            writeUTF(generateSignature(it.text))
        }

        writeUTF(generateSignature(text))
    }
}

private fun KtClass.classModifiers(): Int {
    var hash = 0
    if (isPublic) hash = hash or PUBLIC_MASK
    if (isPrivate()) hash = hash or PRIVATE_MASK
    if (isProtected()) hash = hash or PROTECTED_MASK
    if (!isInheritable()) hash = hash or FINAL_MASK
    if (isAbstract()) hash = hash or ABSTRACT_MASK
    if (isInheritable()) hash = hash or OPEN_MASK
    if (isInterface()) hash = hash or 0x200

    val body = getBody();
    if (null != body) {
        val functions = body.children.filterIsInstance<KtNamedFunction>().filter {
            !it.isPrivate()
        }
        if (functions.isEmpty()) {
            hash = hash and 0xfffffbff.toInt()
        }
    }
    return hash
}

private fun DataOutputStream.addConstructorsHash(ktClass: KtClass) {
    with(ktClass) {
        primaryConstructor?.let { calculateConstructorHash(it) }
        calculateConstructorsHash(secondaryConstructors)
    }
}

private fun  <T : KtConstructor<T>> DataOutputStream.calculateConstructorsHash(constructors: List<KtConstructor<T>>) {
    constructors.forEach {
        calculateConstructorHash(it)
    }
}

private fun <T : KtConstructor<T>> DataOutputStream.calculateConstructorHash(constructor: KtConstructor<T>) {
    with(constructor) {
        safeWriteUTF(name)
        writeInt(computeModifierHash(modifierList))
        safeWriteUTF(generateSignature(text))
    }
}

private fun DataOutputStream.addClassBodyHash(ktClassBody: KtClassBody) {
    with(ktClassBody) {
        computeDeclarationsHash(children.filterIsInstance<KtProperty>().filter { !it.isPrivate() })

        val functions = children.filterIsInstance<KtNamedFunction>().filter { !it.isPrivate() }
        if (functions.isEmpty()) {
            var hash = DEFAULT_HASH_VALUE
            hash = hash and 0xfffffbff.toInt()
            hash
        } else {
            computeDeclarationsHash(functions)
        }
    }
}

private fun DataOutputStream.computeInitializersHash(ktClassBody: KtClassBody) {
    ktClassBody.anonymousInitializers.forEach {
        safeWriteUTF(it.name)
        writeInt(computeModifierHash(it.modifierList))
        writeUTF(generateSignature(ktClassBody.text))
    }
}

private fun DataOutputStream.computeDeclarationsHash(declarations: List<KtDeclaration>) = declarations.forEach {
    computeDeclarationHash(it)
}

private fun DataOutputStream.computePropertyHash(it: KtProperty) {
    safeWriteUTF(it.name)
    writeInt(computeModifierHash(it.modifierList))
}

fun createTypeSignature(el: KtClass): String? {
    val resolveSession =
        getInstance(el.project)
            .getResolutionFacade(listOf(el)).getFrontendService(
                ResolveSession::class.java
            )
    val discriptor = resolveSession.getClassDescriptor(el, NoLookupLocation.FROM_IDE)
    val signature = generateSignature(discriptor)
    println("signature = " + signature)

    return signature
}

fun generateSignature(el: PsiElement): String {
    var ktClass = GenerateSerialVersionUIDHandler.getKtClassWith(el)

    if (null == ktClass) return ""

    return createTypeSignature(ktClass) ?: ""
}

fun generateSignature(text: String) = "$SIGNATURE_PREFIX$text"

private fun computeModifierHash(it: KtModifierList?): Int {
    var modifiers = 0

    if (null == it) {
        // 默认为public
        modifiers = modifiers or 1
        // 默认为final
        modifiers = modifiers or 0x10
        return modifiers;
    }

    when {
        it.hasModifier(KtTokens.PUBLIC_KEYWORD) -> {
            modifiers = modifiers or 1
        }
        it.hasModifier(KtTokens.PRIVATE_KEYWORD) -> {
            modifiers = modifiers or 2
        }
        it.hasModifier(KtTokens.PROTECTED_KEYWORD) -> {
            modifiers = modifiers or 4
        }
        it.hasModifier(KtTokens.OPEN_KEYWORD) -> {
            modifiers = modifiers or 0x200
        }
        it.hasModifier(KtTokens.ABSTRACT_KEYWORD) -> {
            modifiers = modifiers or 0x400
        }
        it.hasModifier(KtTokens.CONST_KEYWORD) -> {
            modifiers = modifiers or 0x0200
        }
        it.hasModifier(KtTokens.CONST_KEYWORD) -> {
            modifiers = modifiers or 0x0200
        }
        it.hasModifier(KtTokens.INTERNAL_KEYWORD) -> {
            modifiers = modifiers or 0x600
        }
        it.hasModifier(KtTokens.FINAL_KEYWORD) -> {
            modifiers = modifiers or 0x0010
        }
    }

    if (!it.hasModifier(KtTokens.PRIVATE_KEYWORD) && !it.hasModifier(KtTokens.PROTECTED_KEYWORD)
        && !it.hasModifier(KtTokens.INTERNAL_KEYWORD)) {
        // 默认为public
        modifiers = modifiers or 1
    }

    if (!it.hasModifier(KtTokens.OPEN_KEYWORD) ) {
        // 默认为final
        modifiers = modifiers or 0x0010
    }

    return modifiers
}


private fun DataOutputStream.computeDeclarationHash(it: KtDeclaration) {
    safeWriteUTF(it.name)
    writeInt(computeModifierHash(it.modifierList))
    writeUTF(generateSignature(it.text))
}

private fun DataOutputStream.safeWriteUTF(text: String?) = text?.let { writeUTF(it) }