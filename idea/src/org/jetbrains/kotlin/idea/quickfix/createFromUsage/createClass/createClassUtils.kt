/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.quickfix.createFromUsage.createClass

import com.intellij.codeInsight.daemon.quickFix.CreateClassOrPackageFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.quickfix.DelegatingIntentionAction
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.TypeInfo
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.containsStarProjections
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.guessTypes
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.noSubstitutions
import org.jetbrains.kotlin.idea.refactoring.canRefactor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.bindingContextUtil.getAbbreviatedTypeOrType
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.receivers.Qualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.substitutions.getTypeSubstitution
import java.lang.AssertionError
import java.util.*
import org.jetbrains.kotlin.descriptors.ClassKind as ClassDescriptorKind

internal fun String.checkClassName(): Boolean = isNotEmpty() && Character.isUpperCase(first())

private fun String.checkPackageName(): Boolean = isNotEmpty() && Character.isLowerCase(first())

internal fun getTargetParentByQualifier(
        file: KtFile,
        isQualified: Boolean,
        qualifierDescriptor: DeclarationDescriptor?): PsiElement? {
    val project = file.project
    val targetParent: PsiElement? = when {
        !isQualified ->
            file
        qualifierDescriptor is ClassDescriptor ->
            DescriptorToSourceUtilsIde.getAnyDeclaration(project, qualifierDescriptor)
        qualifierDescriptor is PackageViewDescriptor ->
            if (qualifierDescriptor.fqName != file.packageFqName) {
                JavaPsiFacade.getInstance(project).findPackage(qualifierDescriptor.fqName.asString())
            }
            else file
        else ->
            null
    }
    return if (targetParent?.canRefactor() ?: false) return targetParent else null
}

internal fun getTargetParentByCall(call: Call, file: KtFile, context: BindingContext): PsiElement? {
    val receiver = call.explicitReceiver
    return when (receiver) {
        null -> getTargetParentByQualifier(file, false, null)
        is Qualifier -> getTargetParentByQualifier(file, true, context[BindingContext.REFERENCE_TARGET, receiver.referenceExpression])
        is ReceiverValue -> getTargetParentByQualifier(file, true, receiver.type.constructor.declarationDescriptor)
        else -> throw AssertionError("Unexpected receiver: $receiver")
    }
}

internal fun isInnerClassExpected(call: Call) = call.explicitReceiver is ReceiverValue

internal fun KtExpression.getInheritableTypeInfo(
        context: BindingContext,
        moduleDescriptor: ModuleDescriptor,
        containingDeclaration: PsiElement): Pair<TypeInfo, (ClassKind) -> Boolean> {
    val types = guessTypes(context, moduleDescriptor, coerceUnusedToUnit = false)
    if (types.size != 1) return TypeInfo.Empty to { _ -> true }

    val type = types.first()
    val descriptor = type.constructor.declarationDescriptor ?: return TypeInfo.Empty to { _ -> false }

    val canHaveSubtypes = !(type.constructor.isFinal || type.containsStarProjections())
    val isEnum = DescriptorUtils.isEnumClass(descriptor)

    if (!(canHaveSubtypes || isEnum)
        || descriptor is TypeParameterDescriptor) return TypeInfo.Empty to { _ -> false }

    return TypeInfo.ByType(type, Variance.OUT_VARIANCE).noSubstitutions() to { classKind ->
        when (classKind) {
            ClassKind.ENUM_ENTRY -> isEnum && containingDeclaration == DescriptorToSourceUtils.descriptorToDeclaration(descriptor)
            ClassKind.INTERFACE -> containingDeclaration !is PsiClass
                                   || (descriptor as? ClassDescriptor)?.kind == ClassDescriptorKind.INTERFACE
            else -> canHaveSubtypes
        }
    }
}

internal fun KtSimpleNameExpression.getCreatePackageFixIfApplicable(targetParent: PsiElement): IntentionAction? {
    val name = getReferencedName()
    if (!name.checkPackageName()) return null

    val basePackage: PsiPackage =
            when (targetParent) {
                is KtFile -> JavaPsiFacade.getInstance(targetParent.project).findPackage(targetParent.packageFqName.asString())
                is PsiPackage -> targetParent
                else -> null
            }
            ?: return null

    val baseName = basePackage.qualifiedName
    val fullName = if (baseName.isNotEmpty()) "$baseName.$name" else name

    val javaFix = CreateClassOrPackageFix.createFix(fullName, resolveScope, this, basePackage, null, null, null) ?: return null

    return object : DelegatingIntentionAction(javaFix) {
        override fun getFamilyName(): String = KotlinBundle.message("create.from.usage.family")

        override fun getText(): String = "Create package '$fullName'"
    }
}

data class UnsubstitutedTypeConstraintInfo(
        val typeParameter: TypeParameterDescriptor,
        private val originalSubstitution: Map<TypeConstructor, TypeProjection>,
        val upperBound: KotlinType
) {
    fun performSubstitution(vararg substitution: Pair<TypeConstructor, TypeProjection>): TypeConstraintInfo? {
        val currentSubstitution = LinkedHashMap<TypeConstructor, TypeProjection>().apply {
            this.putAll(originalSubstitution)
            this.putAll(substitution)
        }
        val substitutedUpperBound = TypeSubstitutor.create(currentSubstitution).substitute(upperBound, Variance.INVARIANT) ?: return null
        return TypeConstraintInfo(typeParameter, substitutedUpperBound)
    }
}

data class TypeConstraintInfo(
        val typeParameter: TypeParameterDescriptor,
        val upperBound: KotlinType
)

fun getUnsubstitutedTypeConstraintInfo(element: KtTypeElement): UnsubstitutedTypeConstraintInfo? {
    val context = element.analyze(BodyResolveMode.PARTIAL)
    val containingTypeArg = (element.parent as? KtTypeReference)?.parent as? KtTypeProjection ?: return null
    val argumentList = containingTypeArg.parent as? KtTypeArgumentList ?: return null
    val containingTypeRef = (argumentList.parent as? KtTypeElement)?.parent as? KtTypeReference ?: return null
    val containingType = containingTypeRef.getAbbreviatedTypeOrType(context) ?: return null
    val baseType = containingType.constructor.declarationDescriptor?.defaultType ?: return null
    val typeParameter = containingType.constructor.parameters.getOrNull(argumentList.arguments.indexOf(containingTypeArg))
    val upperBound = typeParameter?.upperBounds?.singleOrNull() ?: return null
    val substitution = getTypeSubstitution(baseType, containingType) ?: return null
    return UnsubstitutedTypeConstraintInfo(typeParameter, substitution, upperBound)
}

fun getTypeConstraintInfo(element: KtTypeElement) = getUnsubstitutedTypeConstraintInfo(element)?.performSubstitution()
