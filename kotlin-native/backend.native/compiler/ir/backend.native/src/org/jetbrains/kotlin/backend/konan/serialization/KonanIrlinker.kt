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

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.common.lower.parents
import org.jetbrains.kotlin.backend.common.overrides.FakeOverrideBuilder
import org.jetbrains.kotlin.backend.common.overrides.FakeOverrideClassFilter
import org.jetbrains.kotlin.backend.common.overrides.FileLocalAwareLinker
import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinaryNameAndType
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.backend.common.serialization.encodings.FunctionFlags
import org.jetbrains.kotlin.backend.common.serialization.linkerissues.UserVisibleIrModulesSupport
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.descriptors.ClassLayoutBuilder
import org.jetbrains.kotlin.backend.konan.descriptors.findPackage
import org.jetbrains.kotlin.backend.konan.descriptors.isInteropLibrary
import org.jetbrains.kotlin.backend.konan.ir.interop.IrProviderForCEnumAndCStructStubs
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.konan.DeserializedKlibModuleOrigin
import org.jetbrains.kotlin.descriptors.konan.isNativeStdlib
import org.jetbrains.kotlin.descriptors.konan.klibModuleOrigin
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.builders.TranslationPluginContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.lazy.IrLazyClass
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrPublicSymbolBase
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.library.KotlinAbiVersion
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import sun.misc.Unsafe
import org.jetbrains.kotlin.backend.common.serialization.proto.IrClass as ProtoClass
import org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration as ProtoDeclaration
import org.jetbrains.kotlin.backend.common.serialization.proto.IrField as ProtoField
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFile as ProtoFile
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFunction as ProtoFunction
import org.jetbrains.kotlin.backend.common.serialization.proto.IrProperty as ProtoProperty

private val unsafe = with(Unsafe::class.java.getDeclaredField("theUnsafe")) {
    isAccessible = true
    return@with this.get(null) as Unsafe
}

private val byteArrayBaseOffset = unsafe.arrayBaseOffset(ByteArray::class.java).toLong()

internal class ByteArrayStream(val buf: ByteArray) {
    private var offset = 0

    fun hasData() = offset < buf.size

    fun readInt(): Int {
        checkSize(offset + Int.SIZE_BYTES) { "Can't read an int at $offset, size = ${buf.size}" }
        return unsafe.getInt(buf, byteArrayBaseOffset + offset).also { offset += Int.SIZE_BYTES }
    }

    fun writeInt(value: Int) {
        checkSize(offset + Int.SIZE_BYTES) { "Can't write an int at $offset, size = ${buf.size}" }
        unsafe.putInt(buf, byteArrayBaseOffset + offset, value).also { offset += Int.SIZE_BYTES }
    }

    private fun checkSize(at: Int, messageBuilder: () -> String) {
        if (at > buf.size) error(messageBuilder())
    }
}

class SerializedInlineFunctionReference(val file: Int, val functionSignature: Int, val body: Int,
                                        val startOffset: Int, val endOffset: Int,
                                        val extensionReceiverSig: Int, val dispatchReceiverSig: Int,
                                        val valueParameterSigs: IntArray, val typeParameterSigs: IntArray,
                                        val defaultValues: IntArray)

internal object InlineFunctionBodyReferenceSerializer {
    fun serialize(bodies: List<SerializedInlineFunctionReference>): ByteArray {
        val size = bodies.sumOf {
            Int.SIZE_BYTES * (10 + it.valueParameterSigs.size + it.typeParameterSigs.size + it.defaultValues.size)
        }
        val stream = ByteArrayStream(ByteArray(size))
        bodies.forEach {
            stream.writeInt(it.file)
            stream.writeInt(it.functionSignature)
            stream.writeInt(it.body)
            stream.writeInt(it.startOffset)
            stream.writeInt(it.endOffset)
            stream.writeInt(it.extensionReceiverSig)
            stream.writeInt(it.dispatchReceiverSig)
            stream.writeInt(it.valueParameterSigs.size)
            it.valueParameterSigs.forEach { sig -> stream.writeInt(sig) }
            stream.writeInt(it.typeParameterSigs.size)
            it.typeParameterSigs.forEach { sig -> stream.writeInt(sig) }
            stream.writeInt(it.defaultValues.size)
            it.defaultValues.forEach { stream.writeInt(it) }
        }
        return stream.buf
    }

    fun deserialize(data: ByteArray): List<SerializedInlineFunctionReference> {
        val result = mutableListOf<SerializedInlineFunctionReference>()
        val stream = ByteArrayStream(data)
        while (stream.hasData()) {
            val file = stream.readInt()
            val functionSignature = stream.readInt()
            val body = stream.readInt()
            val startOffset = stream.readInt()
            val endOffset = stream.readInt()
            val extensionReceiverSig = stream.readInt()
            val dispatchReceiverSig = stream.readInt()
            val valueParameterSigsCount = stream.readInt()
            val valueParameterSigs = IntArray(valueParameterSigsCount) { stream.readInt() }
            val typeParameterSigsCount = stream.readInt()
            val typeParameterSigs = IntArray(typeParameterSigsCount) { stream.readInt() }
            val defaultValuesCount = stream.readInt()
            val defaultValues = IntArray(defaultValuesCount) { stream.readInt() }
            result.add(SerializedInlineFunctionReference(file, functionSignature, body, startOffset, endOffset,
                    extensionReceiverSig, dispatchReceiverSig, valueParameterSigs, typeParameterSigs, defaultValues))
        }
        return result
    }
}

// [binaryType] is needed in case a field is of a private inline class type (which can't be deserialized).
// But it is safe to just set the field's type to the primitive type the inline class will be erased to.
class SerializedClassFieldInfo(val name: Int, val binaryType: Int, val type: Int, val flags: Int) {
    companion object {
        const val FLAG_IS_CONST = 1
        const val FLAG_CONST_INITIALIZER = 2
    }
}

class SerializedClassFields(val file: Int, val classSignature: Int,
                            val typeParameterSigs: IntArray, val fields: Array<SerializedClassFieldInfo>)

internal object ClassFieldsSerializer {
    fun serialize(classFields: List<SerializedClassFields>): ByteArray {
        val size = classFields.sumOf { Int.SIZE_BYTES * (4 + it.typeParameterSigs.size + it.fields.size * 4) }
        val stream = ByteArrayStream(ByteArray(size))
        classFields.forEach {
            stream.writeInt(it.file)
            stream.writeInt(it.classSignature)
            stream.writeInt(it.typeParameterSigs.size)
            it.typeParameterSigs.forEach { stream.writeInt(it) }
            stream.writeInt(it.fields.size)
            it.fields.forEach { field ->
                stream.writeInt(field.name)
                stream.writeInt(field.binaryType)
                stream.writeInt(field.type)
                stream.writeInt(field.flags)
            }
        }
        return stream.buf
    }

    fun deserialize(data: ByteArray): List<SerializedClassFields> {
        val result = mutableListOf<SerializedClassFields>()
        val stream = ByteArrayStream(data)
        while (stream.hasData()) {
            val file = stream.readInt()
            val classSignature = stream.readInt()
            val typeParameterSigsCount = stream.readInt()
            val typeParameterSigs = IntArray(typeParameterSigsCount) { stream.readInt() }
            val fieldsCount = stream.readInt()
            val fields = Array(fieldsCount) {
                val name = stream.readInt()
                val binaryType = stream.readInt()
                val type = stream.readInt()
                val flags = stream.readInt()
                SerializedClassFieldInfo(name, binaryType, type, flags)
            }
            result.add(SerializedClassFields(file, classSignature, typeParameterSigs, fields))
        }
        return result
    }
}

internal fun ProtoClass.findClass(irClass: IrClass, fileReader: IrLibraryFile, symbolDeserializer: IrSymbolDeserializer): ProtoClass {
    val signature = irClass.symbol.signature ?: error("No signature for ${irClass.render()}")
    var result: ProtoClass? = null

    for (i in 0 until this.declarationCount) {
        val child = this.getDeclaration(i)
        if (child.declaratorCase != ProtoDeclaration.DeclaratorCase.IR_CLASS) continue
        val childClass = child.irClass

        val name = fileReader.string(child.irClass.name)
        if (name == irClass.name.asString()) {
            if (result == null)
                result = childClass
            else {
                val resultIdSignature = symbolDeserializer.deserializeIdSignature(BinarySymbolData.decode(result.base.symbol).signatureId)
                if (resultIdSignature == signature)
                    return result
                result = childClass
            }
        }
    }
    return result ?: error("Class ${irClass.render()} is not found")
}

internal fun ProtoClass.findProperty(irProperty: IrProperty, fileReader: IrLibraryFile, symbolDeserializer: IrSymbolDeserializer): ProtoProperty {
    val signature = irProperty.symbol.signature ?: error("No signature for ${irProperty.render()}")
    var result: ProtoProperty? = null

    for (i in 0 until this.declarationCount) {
        val child = this.getDeclaration(i)
        if (child.declaratorCase != ProtoDeclaration.DeclaratorCase.IR_PROPERTY) continue
        val childProperty = child.irProperty

        val name = fileReader.string(child.irProperty.name)
        if (name == irProperty.name.asString()) {
            if (result == null)
                result = childProperty
            else {
                val resultIdSignature = symbolDeserializer.deserializeIdSignature(BinarySymbolData.decode(result.base.symbol).signatureId)
                if (resultIdSignature == signature)
                    return result
                result = childProperty
            }
        }
    }
    return result ?: error("Property ${irProperty.render()} is not found")
}

internal fun ProtoProperty.findAccessor(irProperty: IrProperty, irFunction: IrSimpleFunction): ProtoFunction {
    if (irFunction == irProperty.getter)
        return getter
    require(irFunction == irProperty.setter) { "Accessor should be either a getter or a setter. ${irFunction.render()}" }
    return setter
}

internal fun ProtoClass.findInlineFunction(irFunction: IrFunction, fileReader: IrLibraryFile, symbolDeserializer: IrSymbolDeserializer): ProtoFunction {
    (irFunction as? IrSimpleFunction)?.correspondingPropertySymbol?.owner?.let { irProperty ->
        return findProperty(irProperty, fileReader, symbolDeserializer).findAccessor(irProperty, irFunction)
    }

    val signature = irFunction.symbol.signature ?: error("No signature for ${irFunction.render()}")
    var result: ProtoFunction? = null
    for (i in 0 until this.declarationCount) {
        val child = this.getDeclaration(i)
        if (child.declaratorCase != ProtoDeclaration.DeclaratorCase.IR_FUNCTION) continue
        val childFunction = child.irFunction
        if (childFunction.base.valueParameterCount != irFunction.valueParameters.size) continue
        if (childFunction.base.hasExtensionReceiver() xor (irFunction.extensionReceiverParameter != null)) continue
        if (childFunction.base.hasDispatchReceiver() xor (irFunction.dispatchReceiverParameter != null)) continue
        if (!FunctionFlags.decode(childFunction.base.base.flags).isInline) continue

        val nameAndType = BinaryNameAndType.decode(childFunction.base.nameType)
        val name = fileReader.string(nameAndType.nameIndex)
        if (name == irFunction.name.asString()) {
            if (result == null)
                result = childFunction
            else {
                val resultIdSignature = symbolDeserializer.deserializeIdSignature(BinarySymbolData.decode(result.base.base.symbol).signatureId)
                if (resultIdSignature == signature)
                    return result
                result = childFunction
            }
        }
    }
    return result ?: error("Function ${irFunction.render()} is not found")
}

object KonanFakeOverrideClassFilter : FakeOverrideClassFilter {
    private fun IdSignature.isInteropSignature(): Boolean = with(this) {
        IdSignature.Flags.IS_NATIVE_INTEROP_LIBRARY.test()
    }

    // This is an alternative to .isObjCClass that doesn't need to walk up all the class heirarchy,
    // rather it only looks at immediate super class symbols.
    private fun IrClass.hasInteropSuperClass() = this.superTypes
        .mapNotNull { it.classOrNull }
        .filter { it is IrPublicSymbolBase<*> }
        .any { it.signature?.isInteropSignature() ?: false }

    override fun needToConstructFakeOverrides(clazz: IrClass): Boolean {
        return !clazz.hasInteropSuperClass() && clazz !is IrLazyClass
    }
}

internal class KonanIrLinker(
        private val currentModule: ModuleDescriptor,
        override val translationPluginContext: TranslationPluginContext?,
        messageLogger: IrMessageLogger,
        builtIns: IrBuiltIns,
        symbolTable: SymbolTable,
        friendModules: Map<String, Collection<String>>,
        private val forwardModuleDescriptor: ModuleDescriptor?,
        private val stubGenerator: DeclarationStubGenerator,
        private val cenumsProvider: IrProviderForCEnumAndCStructStubs,
        exportedDependencies: List<ModuleDescriptor>,
        private val cachedLibraries: CachedLibraries,
        private val lazyIrForCaches: Boolean,
        override val userVisibleIrModulesSupport: UserVisibleIrModulesSupport,
) : KotlinIrLinker(currentModule, messageLogger, builtIns, symbolTable, exportedDependencies) {

    companion object {
        private val C_NAMES_NAME = Name.identifier("cnames")
        private val OBJC_NAMES_NAME = Name.identifier("objcnames")

        val FORWARD_DECLARATION_ORIGIN = object : IrDeclarationOriginImpl("FORWARD_DECLARATION_ORIGIN") {}

        const val offset = SYNTHETIC_OFFSET
    }

    override fun isBuiltInModule(moduleDescriptor: ModuleDescriptor): Boolean = moduleDescriptor.isNativeStdlib()

    private val forwardDeclarationDeserializer = forwardModuleDescriptor?.let { KonanForwardDeclarationModuleDeserializer(it) }
    override val fakeOverrideBuilder: FakeOverrideBuilder =
        FakeOverrideBuilder(this, symbolTable, KonanManglerIr, IrTypeSystemContextImpl(builtIns), friendModules, KonanFakeOverrideClassFilter)

    val nonCachedLibraryModuleDeserializers = mutableMapOf<ModuleDescriptor, KonanModuleDeserializer>()
    val cachedLibraryModuleDeserializers = mutableMapOf<ModuleDescriptor, KonanCachedLibraryModuleDeserializer>()

    override fun createModuleDeserializer(moduleDescriptor: ModuleDescriptor, klib: KotlinLibrary?, strategyResolver: (String) -> DeserializationStrategy) =
            when {
                moduleDescriptor === forwardModuleDescriptor -> {
                    forwardDeclarationDeserializer ?: error("forward declaration deserializer expected")
                }
                klib == null -> {
                    error("Expecting kotlin library for $moduleDescriptor")
                }
                klib.isInteropLibrary() -> {
                    // See https://youtrack.jetbrains.com/issue/KT-43517.
                    // Disabling this flag forces linker to generate IR.
                    val isCached = false //cachedLibraries.isLibraryCached(klib)
                    KonanInteropModuleDeserializer(moduleDescriptor, klib, isCached)
                }
                lazyIrForCaches && cachedLibraries.isLibraryCached(klib) -> {
                    KonanCachedLibraryModuleDeserializer(moduleDescriptor, klib).also {
                        cachedLibraryModuleDeserializers[moduleDescriptor] = it
                    }
                }
                else -> {
                    KonanModuleDeserializer(moduleDescriptor, klib, strategyResolver).also {
                        nonCachedLibraryModuleDeserializers[moduleDescriptor] = it
                    }
                }
            }

    override fun postProcess() {
        stubGenerator.unboundSymbolGeneration = true
        super.postProcess()
    }

    private val IrClass.firstNonClassParent: IrDeclarationParent
        get() {
            var parent = parent
            while (parent is IrClass) parent = parent.parent
            return parent
        }

    private fun IrClass.getOuterClasses(takeOnlyInner: Boolean): List<IrClass> {
        var outerClass = this
        val outerClasses = mutableListOf(outerClass)
        while (outerClass.isInner || !takeOnlyInner) {
            outerClass = outerClass.parent as? IrClass ?: break
            outerClasses.add(outerClass)
        }
        outerClasses.reverse()
        return outerClasses
    }

    private val InvalidIndex = -1

    inner class KonanModuleDeserializer(
            moduleDescriptor: ModuleDescriptor,
            klib: KotlinLibrary,
            strategyResolver: (String) -> DeserializationStrategy
    ): BasicIrModuleDeserializer(this@KonanIrLinker, moduleDescriptor, klib, strategyResolver, klib.versions.abiVersion ?: KotlinAbiVersion.CURRENT) {
        override val moduleFragment: IrModuleFragment = KonanIrModuleFragmentImpl(moduleDescriptor, builtIns, emptyList())

        fun buildInlineFunctionReference(irFunction: IrFunction): SerializedInlineFunctionReference {
            val signature = irFunction.symbol.signature
                    ?: error("No signature for ${irFunction.render()}")
            val topLevelSignature = signature.topLevelSignature()
            val fileDeserializationState = moduleReversedFileIndex[topLevelSignature]
                    ?: error("No file deserializer for ${topLevelSignature.render()}")
            val declarationIndex = fileDeserializationState.fileDeserializer.reversedSignatureIndex[topLevelSignature]
                    ?: error("No declaration for ${topLevelSignature.render()}")
            val fileReader = fileDeserializationState.fileReader
            val symbolDeserializer = fileDeserializationState.fileDeserializer.symbolDeserializer
            val protoDeclaration = fileReader.declaration(declarationIndex)

            val outerClasses = (irFunction.parent as? IrClass)?.getOuterClasses(takeOnlyInner = false) ?: emptyList()
            require((outerClasses.getOrNull(0)?.parent ?: irFunction.parent) is IrFile) {
                "Local inline functions are not supported: ${irFunction.render()}"
            }

            val typeParameterSigs = mutableListOf<Int>()
            val protoFunction = if (outerClasses.isEmpty()) {
                val irProperty = (irFunction as? IrSimpleFunction)?.correspondingPropertySymbol?.owner
                if (irProperty == null)
                    protoDeclaration.irFunction
                else protoDeclaration.irProperty.findAccessor(irProperty, irFunction)
            } else {
                val firstNotInnerClassIndex = outerClasses.indexOfLast { !it.isInner }
                var protoClass = protoDeclaration.irClass
                outerClasses.indices.forEach { classIndex ->
                    if (classIndex >= firstNotInnerClassIndex /* owner's type parameters are always accessible */) {
                        (0 until protoClass.typeParameterCount).mapTo(typeParameterSigs) {
                            BinarySymbolData.decode(protoClass.getTypeParameter(it).base.symbol).signatureId
                        }
                    }
                    if (classIndex < outerClasses.size - 1)
                        protoClass = protoClass.findClass(outerClasses[classIndex + 1], fileReader, symbolDeserializer)
                }
                protoClass.findInlineFunction(irFunction, fileReader, symbolDeserializer)
            }

            val functionSignature = BinarySymbolData.decode(protoFunction.base.base.symbol).signatureId
            (0 until protoFunction.base.typeParameterCount).mapTo(typeParameterSigs) {
                BinarySymbolData.decode(protoFunction.base.getTypeParameter(it).base.symbol).signatureId
            }
            val defaultValues = mutableListOf<Int>()
            val valueParameterSigs = (0 until protoFunction.base.valueParameterCount).map {
                val valueParameter = protoFunction.base.getValueParameter(it)
                defaultValues.add(if (valueParameter.hasDefaultValue()) valueParameter.defaultValue else InvalidIndex)
                BinarySymbolData.decode(valueParameter.base.symbol).signatureId
            }
            val extensionReceiverSig = irFunction.extensionReceiverParameter?.let {
                BinarySymbolData.decode(protoFunction.base.extensionReceiver.base.symbol).signatureId
            } ?: InvalidIndex
            val dispatchReceiverSig = irFunction.dispatchReceiverParameter?.let {
                BinarySymbolData.decode(protoFunction.base.dispatchReceiver.base.symbol).signatureId
            } ?: InvalidIndex

            return SerializedInlineFunctionReference(fileDeserializationState.fileIndex, functionSignature, protoFunction.base.body,
                    irFunction.startOffset, irFunction.endOffset, extensionReceiverSig, dispatchReceiverSig,
                    valueParameterSigs.toIntArray(), typeParameterSigs.toIntArray(), defaultValues.toIntArray())
        }

        fun buildClassFields(irClass: IrClass, fields: List<ClassLayoutBuilder.FieldInfo>): SerializedClassFields {
            val signature = irClass.symbol.signature
                    ?: error("No signature for ${irClass.render()}")
            val topLevelSignature = signature.topLevelSignature()
            val fileDeserializationState = moduleReversedFileIndex[topLevelSignature]
                    ?: error("No file deserializer for ${topLevelSignature.render()}")
            val fileDeserializer = fileDeserializationState.fileDeserializer
            val declarationIndex = fileDeserializer.reversedSignatureIndex[topLevelSignature]
                    ?: error("No declaration for ${topLevelSignature.render()}")
            val fileReader = fileDeserializationState.fileReader
            val symbolDeserializer = fileDeserializer.symbolDeserializer
            val protoDeclaration = fileReader.declaration(declarationIndex)

            val outerClasses = irClass.getOuterClasses(takeOnlyInner = false)
            require(outerClasses.first().parent is IrFile) { "Local classes are not supported: ${irClass.render()}" }

            val typeParameterSigs = mutableListOf<Int>()
            var protoClass = protoDeclaration.irClass
            val firstNotInnerClassIndex = outerClasses.indexOfLast { !it.isInner }
            for (classIndex in outerClasses.indices) {
                if (classIndex >= firstNotInnerClassIndex /* owner's type parameters are always accessible */) {
                    (0 until protoClass.typeParameterCount).mapTo(typeParameterSigs) {
                        BinarySymbolData.decode(protoClass.getTypeParameter(it).base.symbol).signatureId
                    }
                }
                if (classIndex < outerClasses.size - 1)
                    protoClass = protoClass.findClass(outerClasses[classIndex + 1], fileReader, symbolDeserializer)
            }

            val protoFields = mutableListOf<ProtoField>()
            for (i in 0 until protoClass.declarationCount) {
                val declaration = protoClass.getDeclaration(i)
                if (declaration.declaratorCase == ProtoDeclaration.DeclaratorCase.IR_FIELD)
                    protoFields.add(declaration.irField)
                else if (declaration.declaratorCase == ProtoDeclaration.DeclaratorCase.IR_PROPERTY) {
                    val protoProperty = declaration.irProperty
                    if (protoProperty.hasBackingField())
                        protoFields.add(protoProperty.backingField)
                }
            }
            val protoFieldsMap = mutableMapOf<String, ProtoField>()
            protoFields.forEach {
                val nameAndType = BinaryNameAndType.decode(it.nameType)
                val name = fileReader.string(nameAndType.nameIndex)
                val prev = protoFieldsMap[name]
                if (prev != null)
                    error("Class ${irClass.render()} has two fields with same name '$name'")
                protoFieldsMap[name] = it
            }

            val compatibleMode = CompatibilityMode(libraryAbiVersion).oldSignatures
            return SerializedClassFields(
                    fileDeserializationState.fileIndex,
                    BinarySymbolData.decode(protoClass.base.symbol).signatureId,
                    typeParameterSigs.toIntArray(),
                    Array(fields.size) {
                        val field = fields[it]
                        val irField = field.irField ?: error("No IR for field ${field.name} of ${irClass.render()}")
                        val protoField = protoFieldsMap[field.name] ?: error("No proto for ${irField.render()}")
                        val nameAndType = BinaryNameAndType.decode(protoField.nameType)
                        var flags = 0
                        if (field.isConst)
                            flags = flags or SerializedClassFieldInfo.FLAG_IS_CONST
                        if (field.hasConstInitializer)
                            flags = flags or SerializedClassFieldInfo.FLAG_CONST_INITIALIZER
                        val classifier = irField.type.classifierOrNull ?: error("Fields of type ${irField.type.render()} are not supported")
                        val primitiveBinaryType = irField.type.computePrimitiveBinaryTypeOrNull()

                        SerializedClassFieldInfo(
                                nameAndType.nameIndex,
                                primitiveBinaryType?.ordinal ?: InvalidIndex,
                                if (with(KonanManglerIr) { (classifier as? IrClassSymbol)?.owner?.isExported(compatibleMode) } == false)
                                    InvalidIndex
                                else nameAndType.typeIndex,
                                flags)
                    })
        }
    }

    private inner class KonanInteropModuleDeserializer(
            moduleDescriptor: ModuleDescriptor,
            override val klib: KotlinLibrary,
            private val isLibraryCached: Boolean
    ) : IrModuleDeserializer(moduleDescriptor, klib.versions.abiVersion ?: KotlinAbiVersion.CURRENT) {
        init {
            require(klib.isInteropLibrary())
        }

        private val descriptorByIdSignatureFinder = DescriptorByIdSignatureFinder(
                moduleDescriptor, KonanManglerDesc,
                DescriptorByIdSignatureFinder.LookupMode.MODULE_ONLY
        )

        private fun IdSignature.isInteropSignature() = IdSignature.Flags.IS_NATIVE_INTEROP_LIBRARY.test()

        override fun contains(idSig: IdSignature): Boolean {
            if (idSig.isPubliclyVisible) {
                if (idSig.isInteropSignature()) {
                    // TODO: add descriptor cache??
                    return descriptorByIdSignatureFinder.findDescriptorBySignature(idSig) != null
                }
            }

            return false
        }

        private fun DeclarationDescriptor.isCEnumsOrCStruct(): Boolean = cenumsProvider.isCEnumOrCStruct(this)

        private val fileMap = mutableMapOf<PackageFragmentDescriptor, IrFile>()

        private fun getIrFile(packageFragment: PackageFragmentDescriptor): IrFile = fileMap.getOrPut(packageFragment) {
            IrFileImpl(NaiveSourceBasedFileEntryImpl(IrProviderForCEnumAndCStructStubs.cTypeDefinitionsFileName), packageFragment).also {
                moduleFragment.files.add(it)
            }
        }

        private fun resolveCEnumsOrStruct(descriptor: DeclarationDescriptor, idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            val file = getIrFile(descriptor.findPackage())
            return cenumsProvider.getDeclaration(descriptor, idSig, file, symbolKind).symbol
        }

        override fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            val descriptor = descriptorByIdSignatureFinder.findDescriptorBySignature(idSig) ?: error("Expecting descriptor for $idSig")
            // If library is cached we don't need to create an IrClass for struct or enum.
            if (!isLibraryCached && descriptor.isCEnumsOrCStruct()) return resolveCEnumsOrStruct(descriptor, idSig, symbolKind)

            val symbolOwner = stubGenerator.generateMemberStub(descriptor) as IrSymbolOwner

            return symbolOwner.symbol
        }

        override val moduleFragment: IrModuleFragment = KonanIrModuleFragmentImpl(moduleDescriptor, builtIns)
        override val moduleDependencies: Collection<IrModuleDeserializer> = listOfNotNull(forwardDeclarationDeserializer)
    }

    inner class KonanCachedLibraryModuleDeserializer(
            moduleDescriptor: ModuleDescriptor,
            override val klib: KotlinLibrary
    ) : IrModuleDeserializer(moduleDescriptor, klib.versions.abiVersion ?: KotlinAbiVersion.CURRENT) {

        private val descriptorByIdSignatureFinder = DescriptorByIdSignatureFinder(
                moduleDescriptor, KonanManglerDesc,
                DescriptorByIdSignatureFinder.LookupMode.MODULE_ONLY
        )

        override fun contains(idSig: IdSignature) =
                idSig.isPubliclyVisible && descriptorByIdSignatureFinder.findDescriptorBySignature(idSig) != null

        override fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            val descriptor = descriptorByIdSignatureFinder.findDescriptorBySignature(idSig)
                    ?: error("Expecting descriptor for $idSig")

            return (stubGenerator.generateMemberStub(descriptor) as IrSymbolOwner).symbol
        }

        override val moduleFragment: IrModuleFragment = KonanIrModuleFragmentImpl(moduleDescriptor, builtIns)

        override val moduleDependencies by lazy {
            moduleDescriptor.allDependencyModules
                    .filter { it != moduleDescriptor }
                    .map { resolveModuleDeserializer(it, null) }
        }

        inner class FileDeserializationInfo(val fileReader: IrLibraryFileFromBytes, val file: IrFile,
                                            val declarationDeserializer: IrDeclarationDeserializer,
                                            val fakeOverrideBuilder: FakeOverrideBuilder)

        private val filesDeserializationInfo by lazy {
            val result = mutableListOf<FileDeserializationInfo>()
            val fileCount = klib.fileCount()

            for (i in 0 until fileCount) {
                val fileStream = klib.file(i).codedInputStream
                val fileProto = ProtoFile.parseFrom(fileStream, ExtensionRegistryLite.newInstance())

                val fileReader = IrLibraryFileFromBytes(IrKlibBytesSource(klib, i))
                val file = fileReader.createFile(moduleFragment, fileProto)

                val symbolDeserializer = IrSymbolDeserializer(
                        symbolTable, fileReader, file.symbol, emptyList(), { }, { _, symbol -> symbol })
                { idSig, symbolKind ->
                    val topLevelSig = idSig.topLevelSignature()
                    val actualModuleDeserializer = resolveModuleDeserializer(moduleDescriptor, null)
                            .findModuleDeserializerForTopLevelId(topLevelSig)
                            ?: handleSignatureIdNotFoundInModuleWithDependencies(idSig, this)

                    actualModuleDeserializer.deserializeIrSymbol(idSig, symbolKind)
                }

                val fakeOverrideBuilder = FakeOverrideBuilder(
                        object : FileLocalAwareLinker {
                            override fun tryReferencingSimpleFunctionByLocalSignature(parent: IrDeclaration, idSignature: IdSignature) =
                                    if (idSignature.isPubliclyVisible) null else symbolDeserializer.referenceSimpleFunctionByLocalSignature(idSignature)

                            override fun tryReferencingPropertyByLocalSignature(parent: IrDeclaration, idSignature: IdSignature) =
                                    if (idSignature.isPubliclyVisible) null else symbolDeserializer.referencePropertyByLocalSignature(idSignature)
                        },
                        symbolTable, KonanManglerIr, IrTypeSystemContextImpl(builtIns), emptyMap(), KonanFakeOverrideClassFilter)

                val declarationDeserializer = IrDeclarationDeserializer(
                        builtIns,
                        symbolTable,
                        IrFactoryImpl,
                        fileReader,
                        file,
                        allowErrorNodes = false,
                        deserializeInlineFunctions = true,
                        deserializeBodies = true,
                        symbolDeserializer,
                        fakeOverrideBuilder.platformSpecificClassFilter,
                        fakeOverrideBuilder,
                        compatibilityMode = CompatibilityMode(libraryAbiVersion)
                )

                result.add(FileDeserializationInfo(fileReader, file, declarationDeserializer, fakeOverrideBuilder))
            }

            result
        }

        private val inlineFunctionReferences by lazy {
            cachedLibraries.getLibraryCache(klib)!!.serializedInlineFunctionBodies.associateBy {
                filesDeserializationInfo[it.file].declarationDeserializer.symbolDeserializer.deserializeIdSignature(it.functionSignature)
            }
        }

        private val inlineFunctionFiles = mutableMapOf<IrExternalPackageFragment, IrFile>()

        fun deserializeInlineFunction(function: IrFunction): InlineFunctionOriginInfo {
            val packageFragment = function.findPackage() as? IrExternalPackageFragment
                    ?: error("Expected an external package fragment for ${function.render()}")
            if (function.parents.any { (it as? IrFunction)?.isInline == true}) {
                // Already deserialized by the top-most inline function.
                return InlineFunctionOriginInfo(
                        inlineFunctionFiles[packageFragment]
                                ?: error("${function.render()} should've been deserialized along with its parent"),
                        function.startOffset, function.endOffset
                )
            }

            val signature = function.symbol.signature
                    ?: error("No signature for ${function.render()}")
            val inlineFunctionReference = inlineFunctionReferences[signature]
                    ?: error("No inline function reference for ${function.render()}, sig = ${signature.render()}")
            val fileDeserializationInfo = filesDeserializationInfo[inlineFunctionReference.file]
            val declarationDeserializer = fileDeserializationInfo.declarationDeserializer
            val symbolDeserializer = declarationDeserializer.symbolDeserializer

            val outerClasses = (function.parent as? IrClass)?.getOuterClasses(takeOnlyInner = true) ?: emptyList()
            require((outerClasses.getOrNull(0)?.firstNonClassParent ?: function.parent) is IrExternalPackageFragment) {
                "Local inline functions are not supported: ${function.render()}"
            }

            var endToEndTypeParameterIndex = 0
            outerClasses.forEach { outerClass ->
                outerClass.typeParameters.forEach { parameter ->
                    val sigIndex = inlineFunctionReference.typeParameterSigs[endToEndTypeParameterIndex++]
                    symbolDeserializer.referenceLocalIrSymbol(parameter.symbol, symbolDeserializer.deserializeIdSignature(sigIndex))
                }
            }
            function.typeParameters.forEach { parameter ->
                val sigIndex = inlineFunctionReference.typeParameterSigs[endToEndTypeParameterIndex++]
                symbolDeserializer.referenceLocalIrSymbol(parameter.symbol, symbolDeserializer.deserializeIdSignature(sigIndex))
            }
            function.valueParameters.forEachIndexed { index, parameter ->
                val sigIndex = inlineFunctionReference.valueParameterSigs[index]
                symbolDeserializer.referenceLocalIrSymbol(parameter.symbol, symbolDeserializer.deserializeIdSignature(sigIndex))
            }
            function.extensionReceiverParameter?.let { parameter ->
                val sigIndex = inlineFunctionReference.extensionReceiverSig
                require(sigIndex != InvalidIndex) { "Expected a valid sig reference to the extension receiver for ${function.render()}" }
                symbolDeserializer.referenceLocalIrSymbol(parameter.symbol, symbolDeserializer.deserializeIdSignature(sigIndex))
            }
            function.dispatchReceiverParameter?.let { parameter ->
                val sigIndex = inlineFunctionReference.dispatchReceiverSig
                require(sigIndex != InvalidIndex) { "Expected a valid sig reference to the dispatch receiver for ${function.render()}" }
                symbolDeserializer.referenceLocalIrSymbol(parameter.symbol, symbolDeserializer.deserializeIdSignature(sigIndex))
            }

            function.body = declarationDeserializer.deserializeStatementBody(inlineFunctionReference.body) as IrBody

            function.valueParameters.forEachIndexed { index, parameter ->
                val defaultValueIndex = inlineFunctionReference.defaultValues[index]
                if (defaultValueIndex != InvalidIndex)
                    parameter.defaultValue = declarationDeserializer.deserializeExpressionBody(defaultValueIndex)
            }

            fileDeserializationInfo.fakeOverrideBuilder.provideFakeOverrides()

            inlineFunctionFiles[packageFragment]?.let {
                require(it == fileDeserializationInfo.file) {
                    "Different files ${it.fileEntry.name} and ${fileDeserializationInfo.file.fileEntry.name} have the same $packageFragment"
                }
            }
            inlineFunctionFiles[packageFragment] = fileDeserializationInfo.file

            return InlineFunctionOriginInfo(fileDeserializationInfo.file, inlineFunctionReference.startOffset, inlineFunctionReference.endOffset)
        }

        private val classesFields by lazy {
            cachedLibraries.getLibraryCache(klib)!!.serializedClassFields.associateBy {
                filesDeserializationInfo[it.file].declarationDeserializer.symbolDeserializer.deserializeIdSignature(it.classSignature)
            }
        }

        fun deserializeClassFields(irClass: IrClass): List<ClassLayoutBuilder.FieldInfo> {
            val signature = irClass.symbol.signature
                    ?: error("No signature for ${irClass.render()}")
            val serializedClassFields = classesFields[signature]
                    ?: error("No class fields for ${irClass.render()}, sig = ${signature.render()}")
            val fileDeserializationInfo = filesDeserializationInfo[serializedClassFields.file]
            val declarationDeserializer = fileDeserializationInfo.declarationDeserializer
            val symbolDeserializer = declarationDeserializer.symbolDeserializer

            val outerClasses = irClass.getOuterClasses(takeOnlyInner = true)
            require(outerClasses.first().firstNonClassParent is IrExternalPackageFragment) {
                "Local classes are not supported: ${irClass.render()}"
            }

            var endToEndTypeParameterIndex = 0
            outerClasses.forEach { outerClass ->
                outerClass.typeParameters.forEach { parameter ->
                    val sigIndex = serializedClassFields.typeParameterSigs[endToEndTypeParameterIndex++]
                    symbolDeserializer.referenceLocalIrSymbol(parameter.symbol, symbolDeserializer.deserializeIdSignature(sigIndex))
                }
            }

            fun getByClassId(classId: ClassId): IrClassSymbol {
                val classIdSig = getPublicSignature(classId.packageFqName, classId.relativeClassName.asString())
                return symbolDeserializer.deserializePublicSymbol(classIdSig, BinarySymbolData.SymbolKind.CLASS_SYMBOL) as IrClassSymbol
            }

            return serializedClassFields.fields.map {
                val name = fileDeserializationInfo.fileReader.string(it.name)
                val type = when {
                    it.type != InvalidIndex -> declarationDeserializer.deserializeIrType(it.type)
                    it.binaryType == InvalidIndex -> builtIns.anyNType
                    else -> when (PrimitiveBinaryType.values().getOrNull(it.binaryType)) {
                        PrimitiveBinaryType.BOOLEAN -> builtIns.booleanType
                        PrimitiveBinaryType.BYTE -> builtIns.byteType
                        PrimitiveBinaryType.SHORT -> builtIns.shortType
                        PrimitiveBinaryType.INT -> builtIns.intType
                        PrimitiveBinaryType.LONG -> builtIns.longType
                        PrimitiveBinaryType.FLOAT -> builtIns.floatType
                        PrimitiveBinaryType.DOUBLE -> builtIns.doubleType
                        PrimitiveBinaryType.POINTER -> getByClassId(KonanPrimitiveType.NON_NULL_NATIVE_PTR.classId).defaultType
                        PrimitiveBinaryType.VECTOR128 -> getByClassId(KonanPrimitiveType.VECTOR128.classId).defaultType
                        else -> error("Bad binary type of field $name of ${irClass.render()}")
                    }
                }
                ClassLayoutBuilder.FieldInfo(
                        name, type,
                        isConst = (it.flags and SerializedClassFieldInfo.FLAG_IS_CONST) != 0,
                        hasConstInitializer = (it.flags and SerializedClassFieldInfo.FLAG_CONST_INITIALIZER) != 0,
                        irField = null)
            }
        }
    }

    private inner class KonanForwardDeclarationModuleDeserializer(moduleDescriptor: ModuleDescriptor) : IrModuleDeserializer(moduleDescriptor, KotlinAbiVersion.CURRENT) {
        init {
            require(moduleDescriptor.isForwardDeclarationModule)
        }

        private val declaredDeclaration = mutableMapOf<IdSignature, IrClass>()

        private fun IdSignature.isForwardDeclarationSignature(): Boolean {
            if (isPubliclyVisible) {
                return packageFqName().run {
                    startsWith(C_NAMES_NAME) || startsWith(OBJC_NAMES_NAME)
                }
            }

            return false
        }

        override fun contains(idSig: IdSignature): Boolean = idSig.isForwardDeclarationSignature()

        private fun resolveDescriptor(idSig: IdSignature): ClassDescriptor =
                with(idSig as IdSignature.CommonSignature) {
                    val classId = ClassId(packageFqName(), FqName(declarationFqName), false)
                    moduleDescriptor.findClassAcrossModuleDependencies(classId) ?: error("No declaration found with $idSig")
                }

        private fun buildForwardDeclarationStub(descriptor: ClassDescriptor): IrClass {
            return stubGenerator.generateClassStub(descriptor).also {
                it.origin = FORWARD_DECLARATION_ORIGIN
            }
        }

        override fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            require(symbolKind == BinarySymbolData.SymbolKind.CLASS_SYMBOL) {
                "Only class could be a Forward declaration $idSig (kind $symbolKind)"
            }
            val descriptor = resolveDescriptor(idSig)
            val actualModule = descriptor.module
            if (actualModule !== moduleDescriptor) {
                val moduleDeserializer = resolveModuleDeserializer(actualModule, idSig)
                moduleDeserializer.addModuleReachableTopLevel(idSig)
                return symbolTable.referenceClassFromLinker(idSig)
            }

            return declaredDeclaration.getOrPut(idSig) { buildForwardDeclarationStub(descriptor) }.symbol
        }

        override val moduleFragment: IrModuleFragment = KonanIrModuleFragmentImpl(moduleDescriptor, builtIns)
        override val moduleDependencies: Collection<IrModuleDeserializer> = emptyList()
    }

    private val String.isForwardDeclarationModuleName: Boolean get() = this == "<forward declarations>"

    val modules: Map<String, IrModuleFragment>
        get() = mutableMapOf<String, IrModuleFragment>().apply {
            deserializersForModules
                    .filter { !it.key.isForwardDeclarationModuleName && it.value.moduleDescriptor !== currentModule }
                    .forEach {
                        val klib = it.value.klib as? KotlinLibrary ?: error("Expected to be KotlinLibrary (${it.key})")
                        this[klib.libraryName] = it.value.moduleFragment
                    }
        }
}

class KonanIrModuleFragmentImpl(
        override val descriptor: ModuleDescriptor,
        override val irBuiltins: IrBuiltIns,
        files: List<IrFile> = emptyList(),
) : IrModuleFragment() {
    override val name: Name get() = descriptor.name // TODO

    override val files: MutableList<IrFile> = files.toMutableList()

    val konanLibrary = (descriptor.klibModuleOrigin as? DeserializedKlibModuleOrigin)?.library

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R =
            visitor.visitModuleFragment(this, data)

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        files.forEach { it.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        files.forEachIndexed { i, irFile ->
            files[i] = irFile.transform(transformer, data)
        }
    }
}

fun IrModuleFragment.toKonanModule() = KonanIrModuleFragmentImpl(descriptor, irBuiltins, files)

class KonanFileMetadataSource(val module: KonanIrModuleFragmentImpl) : MetadataSource.File {
    override val name: Name? = null
    override var serializedIr: ByteArray? = null
}
