/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.synthetic

import com.intellij.util.SmartList
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.synthetic.SyntheticMemberDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.load.java.descriptors.SamAdapterDescriptor
import org.jetbrains.kotlin.load.java.descriptors.SamConstructorDescriptor
import org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaClassDescriptor
import org.jetbrains.kotlin.load.java.sam.SingleAbstractMethodUtils
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.inference.wrapWithCapturingSubstitution
import org.jetbrains.kotlin.resolve.isHiddenInResolution
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.ResolutionScope
import org.jetbrains.kotlin.resolve.scopes.SyntheticScope
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.findCorrespondingSupertype
import java.util.*
import kotlin.collections.LinkedHashSet
import kotlin.properties.Delegates

interface SamAdapterExtensionFunctionDescriptor : FunctionDescriptor, SyntheticMemberDescriptor<FunctionDescriptor> {
    override val baseDescriptorForSynthetic: FunctionDescriptor
}

class SamAdapterFunctionsScope(
        storageManager: StorageManager,
        private val languageVersionSettings: LanguageVersionSettings
) : SyntheticScope {
    private val extensionForFunction = storageManager.createMemoizedFunctionWithNullableValues<FunctionDescriptor, FunctionDescriptor> { function ->
        extensionForFunctionNotCached(function)
    }

    private val samAdapterForStaticFunction =
            storageManager.createMemoizedFunction<JavaMethodDescriptor, SamAdapterDescriptor<JavaMethodDescriptor>> { function ->
                SingleAbstractMethodUtils.createSamAdapterFunction(function)
            }

    private val samConstructorForClassifier =
            storageManager.createMemoizedFunction<JavaClassDescriptor, SamConstructorDescriptor> { classifier ->
                samConstructorForClassifierNotCached(classifier)
            }

    private fun extensionForFunctionNotCached(function: FunctionDescriptor): FunctionDescriptor? {
        if (!function.visibility.isVisibleOutside()) return null
        if (!function.hasJavaOriginInHierarchy()) return null //TODO: should we go into base at all?
        if (!SingleAbstractMethodUtils.isSamAdapterNecessary(function)) return null
        if (function.returnType == null) return null
        if (function.isHiddenInResolution(languageVersionSettings)) return null
        return MyFunctionDescriptor.create(function)
    }

    private fun samConstructorForClassifierNotCached(classifier: JavaClassDescriptor): SamConstructorDescriptor {
        return SingleAbstractMethodUtils.createSamConstructorFunction(classifier.containingDeclaration, classifier)
    }

    override fun getSyntheticMemberFunctions(receiverTypes: Collection<KotlinType>, name: Name, location: LookupLocation): Collection<FunctionDescriptor> {
        var result: SmartList<FunctionDescriptor>? = null
        for (type in receiverTypes) {
            for (function in type.memberScope.getContributedFunctions(name, location)) {
                val extension = extensionForFunction(function.original)?.substituteForReceiverType(type)
                if (extension != null) {
                    if (result == null) {
                        result = SmartList()
                    }
                    result.add(extension)
                }
            }
        }
        return when {
            result == null -> emptyList()
            result.size > 1 -> result.toSet()
            else -> result
        }
    }

    private fun FunctionDescriptor.substituteForReceiverType(receiverType: KotlinType): FunctionDescriptor? {
        val containingClass = containingDeclaration as? ClassDescriptor ?: return null
        val correspondingSupertype = findCorrespondingSupertype(receiverType, containingClass.defaultType) ?: return null

        return substitute(
                TypeConstructorSubstitution
                        .create(correspondingSupertype)
                        .wrapWithCapturingSubstitution(needApproximation = true)
                        .buildSubstitutor()
        )
    }

    override fun getSyntheticMemberFunctions(receiverTypes: Collection<KotlinType>): Collection<FunctionDescriptor> {
        return receiverTypes.flatMapTo(LinkedHashSet<FunctionDescriptor>()) { type ->
            type.memberScope.getContributedDescriptors(DescriptorKindFilter.FUNCTIONS)
                    .filterIsInstance<FunctionDescriptor>()
                    .mapNotNull {
                        extensionForFunction(it.original)?.substituteForReceiverType(type)
                    }
        }
    }

    override fun getSyntheticExtensionProperties(receiverTypes: Collection<KotlinType>, name: Name, location: LookupLocation): Collection<PropertyDescriptor> = emptyList()

    override fun getSyntheticExtensionProperties(receiverTypes: Collection<KotlinType>): Collection<PropertyDescriptor> = emptyList()

    override fun getSyntheticStaticFunctions(scope: ResolutionScope, name: Name, location: LookupLocation): Collection<FunctionDescriptor> {
        val classifier = scope.getContributedClassifier(name, location)
        val samConstructor = classifier?.let { getSamConstructor(it) }
        return getSamFunctions(scope.getContributedFunctions(name, location)) + listOfNotNull(samConstructor)
    }

    override fun getSyntheticStaticFunctions(scope: ResolutionScope): Collection<FunctionDescriptor> {
        val samConstructors =
                scope.getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS)
                        .filterIsInstance<ClassifierDescriptor>()
                        .mapNotNull{ getSamConstructor(it) }

        return getSamFunctions(scope.getContributedDescriptors(DescriptorKindFilter.FUNCTIONS)) + samConstructors
    }

    private fun getSamFunctions(functions: Collection<DeclarationDescriptor>): List<SamAdapterDescriptor<JavaMethodDescriptor>> {
        return functions.mapNotNull { function ->
            if (function !is JavaMethodDescriptor) return@mapNotNull null
            if (function.dispatchReceiverParameter != null) return@mapNotNull null // consider only statics
            if (!SingleAbstractMethodUtils.isSamAdapterNecessary(function)) return@mapNotNull null

            samAdapterForStaticFunction(function)
        }
    }

    private fun getSamConstructor(classifier: ClassifierDescriptor): SamConstructorDescriptor? {
        if (classifier is TypeAliasDescriptor) {
            return getTypeAliasSamConstructor(classifier)
        }

        if (classifier !is LazyJavaClassDescriptor || classifier.functionTypeForSamInterface == null) return null
        return samConstructorForClassifier(classifier)
    }

    private fun getTypeAliasSamConstructor(classifier: TypeAliasDescriptor): SamConstructorDescriptor? {
        val classDescriptor = classifier.classDescriptor ?: return null
        if (classDescriptor !is LazyJavaClassDescriptor || classDescriptor.functionTypeForSamInterface == null) return null

        return SingleAbstractMethodUtils.createTypeAliasSamConstructorFunction(classifier, samConstructorForClassifier(classDescriptor))
    }

    private class MyFunctionDescriptor(
            containingDeclaration: DeclarationDescriptor,
            original: SimpleFunctionDescriptor?,
            annotations: Annotations,
            name: Name,
            kind: CallableMemberDescriptor.Kind,
            source: SourceElement
    ) : SamAdapterExtensionFunctionDescriptor, SimpleFunctionDescriptorImpl(containingDeclaration, original, annotations, name, kind, source) {

        override var baseDescriptorForSynthetic: FunctionDescriptor by Delegates.notNull()
            private set

        private val fromSourceFunctionTypeParameters: Map<TypeParameterDescriptor, TypeParameterDescriptor> by lazy {
            baseDescriptorForSynthetic.typeParameters.zip(typeParameters).toMap()
        }

        companion object {
            fun create(sourceFunction: FunctionDescriptor): MyFunctionDescriptor {
                val descriptor = MyFunctionDescriptor(sourceFunction.containingDeclaration,
                                                      null,
                                                      sourceFunction.annotations,
                                                      sourceFunction.name,
                                                      CallableMemberDescriptor.Kind.SYNTHESIZED,
                                                      sourceFunction.original.source)
                descriptor.baseDescriptorForSynthetic = sourceFunction

                val sourceTypeParams = (sourceFunction.typeParameters).toMutableList()
                val ownerClass = sourceFunction.containingDeclaration as ClassDescriptor

                val typeParameters = ArrayList<TypeParameterDescriptor>(sourceTypeParams.size)
                val typeSubstitutor = DescriptorSubstitutor.substituteTypeParameters(sourceTypeParams, TypeSubstitution.EMPTY, descriptor, typeParameters)

                val returnType = typeSubstitutor.safeSubstitute(sourceFunction.returnType!!, Variance.INVARIANT)
                val valueParameters = SingleAbstractMethodUtils.createValueParametersForSamAdapter(sourceFunction, descriptor, typeSubstitutor)

                val visibility = syntheticVisibility(sourceFunction, isUsedForExtension = false)

                descriptor.initialize(null, ownerClass.thisAsReceiverParameter, typeParameters, valueParameters, returnType,
                                      Modality.FINAL, visibility)

                descriptor.isOperator = sourceFunction.isOperator
                descriptor.isInfix = sourceFunction.isInfix

                return descriptor
            }
        }

        override fun hasStableParameterNames() = baseDescriptorForSynthetic.hasStableParameterNames()
        override fun hasSynthesizedParameterNames() = baseDescriptorForSynthetic.hasSynthesizedParameterNames()

        override fun createSubstitutedCopy(
                newOwner: DeclarationDescriptor,
                original: FunctionDescriptor?,
                kind: CallableMemberDescriptor.Kind,
                newName: Name?,
                annotations: Annotations,
                source: SourceElement
        ): MyFunctionDescriptor {
            return MyFunctionDescriptor(
                    containingDeclaration, original as SimpleFunctionDescriptor?, annotations, newName ?: name, kind, source
            ).apply {
                baseDescriptorForSynthetic = this@MyFunctionDescriptor.baseDescriptorForSynthetic
            }
        }

        override fun newCopyBuilder(substitutor: TypeSubstitutor): CopyConfiguration =
                super.newCopyBuilder(substitutor).setOriginal(this.original)

        override fun doSubstitute(configuration: CopyConfiguration): FunctionDescriptor? {
            val descriptor = super.doSubstitute(configuration) as MyFunctionDescriptor? ?: return null
            val original = configuration.original
                           ?: throw UnsupportedOperationException("doSubstitute with no original should not be called for synthetic extension $this")

            original as MyFunctionDescriptor
            assert(original.original == original) { "original in doSubstitute should have no other original" }

            val sourceFunctionSubstitutor =
                    CompositionTypeSubstitution(configuration.substitution, fromSourceFunctionTypeParameters).buildSubstitutor()

            descriptor.baseDescriptorForSynthetic = original.baseDescriptorForSynthetic.substitute(sourceFunctionSubstitutor) ?: return null
            return descriptor
        }
    }
}
