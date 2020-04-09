package juuxel.scissors

import cuchaz.enigma.command.ConvertMappingsCommand
import cuchaz.enigma.command.MappingCommandsUtil
import cuchaz.enigma.translation.mapping.MappingFileNameFormat
import cuchaz.enigma.translation.mapping.MappingSaveParameters
import net.fabricmc.stitch.commands.tinyv2.TinyClass
import net.fabricmc.stitch.commands.tinyv2.TinyField
import net.fabricmc.stitch.commands.tinyv2.TinyFile
import net.fabricmc.stitch.commands.tinyv2.TinyHeader
import net.fabricmc.stitch.commands.tinyv2.TinyLocalVariable
import net.fabricmc.stitch.commands.tinyv2.TinyMethod
import net.fabricmc.stitch.commands.tinyv2.TinyMethodParameter
import net.fabricmc.stitch.commands.tinyv2.TinyV2Reader
import net.fabricmc.stitch.commands.tinyv2.TinyV2Writer
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

@CommandLine.Command(mixinStandardHelpOptions = true)
class Scissors : Runnable {
    @CommandLine.Parameters(index = "0", arity = "1")
    private lateinit var inputA: Path

    @CommandLine.Parameters(index = "1", arity = "1")
    private lateinit var inputB: Path

    @CommandLine.Parameters(index = "2", arity = "1")
    private lateinit var output: Path

    @CommandLine.Option(names = ["--shared-namespace"])
    private var sharedNamespace: String = "intermediary"

    @CommandLine.Option(names = ["--different-namespace"])
    private var differentNamespace: String = "named"

    @CommandLine.Option(names = ["--inputA-format"])
    private var inputAFormat: String = DEFAULT_FORMAT

    @CommandLine.Option(names = ["--inputB-format"])
    private var inputBFormat: String = DEFAULT_FORMAT

    @CommandLine.Option(names = ["--output-format"])
    private var outputFormat: String = DEFAULT_FORMAT

    @CommandLine.Option(names = ["--keep-temporary-files"])
    private var keepTemp: Boolean = false

    override fun run() {
        val saveParams = MappingSaveParameters(MappingFileNameFormat.BY_DEOBF)
        println("Parsing inputs...")
        val mappingsA = MappingCommandsUtil.read(inputAFormat, inputA, saveParams)
        val mappingsB = MappingCommandsUtil.read(inputBFormat, inputB, saveParams)

        val intermediaryA = Files.createTempFile(null, ".tiny")
        val intermediaryB = Files.createTempFile(null, ".tiny")

        if (!keepTemp) {
            intermediaryA.toFile().deleteOnExit()
            intermediaryB.toFile().deleteOnExit()
        }

        println("Converting to tiny...")
        MappingCommandsUtil.write(mappingsA, "tinyv2:intermediary:named", intermediaryA, saveParams)
        MappingCommandsUtil.write(mappingsB, "tinyv2:intermediary:named", intermediaryB, saveParams)

        println("Parsing tiny...")
        val tinyA = TinyV2Reader.read(intermediaryA)
        val tinyB = TinyV2Reader.read(intermediaryB)
        val classesB = tinyB.mapClassesByFirstNamespace()

        val outputClasses: MutableList<TinyClass> = ArrayList()

        println("Scanning...")
        for (it in tinyA.classEntries) {
            var shouldMakeClass = false
            var shouldNameClass = false
            val methods: MutableList<TinyMethod> = ArrayList()
            val fields: MutableList<TinyField> = ArrayList()

            val otherClass = classesB[it.classNames[0]]

            if (otherClass == null) {
                outputClasses += it
                continue
            }

            if (it.classNames[1] != otherClass.classNames[1]) {
                shouldMakeClass = true
                shouldNameClass = true
            }

            if (it.comments != otherClass.comments) {
                shouldMakeClass = true
                shouldNameClass = true
            }

            val selfMethods = it.mapMethodsByFirstNamespaceAndDescriptor()
            val selfFields = it.mapFieldsByFirstNamespace()
            val otherMethods = otherClass.mapMethodsByFirstNamespaceAndDescriptor()
            val otherFields = otherClass.mapFieldsByFirstNamespace()

            for ((name, field) in selfFields) {
                val otherField = otherFields[name]
                if (otherField == null || field.fieldNames[1] != otherField.fieldNames[1] || field.comments != otherField.comments) {
                    fields += field
                    shouldMakeClass = true
                }
            }

            for ((desc, method) in selfMethods) {
                var shouldMakeMethod = false
                var shouldNameMethod = false
                val params: MutableSet<TinyMethodParameter> = HashSet()
                val variables: MutableSet<TinyLocalVariable> = HashSet()
                val otherMethod = otherMethods[desc]

                if (otherMethod == null) {
                    methods += method
                    shouldMakeClass = true
                    continue
                }

                if (method.methodNames[1] != otherMethod.methodNames[1]) {
                    shouldMakeMethod = true
                    shouldNameMethod = true
                }

                for (param in method.parameters) {
                    val otherParam = otherMethod.parameters.find { it.lvIndex == param.lvIndex }
                    if (param.parameterNames[1] != otherParam?.parameterNames?.get(1)) {
                        shouldMakeMethod = true
                        params += param
                    }
                }

                for (variable in method.localVariables) {
                    val otherVariable = otherMethod.localVariables.find { it.lvIndex == variable.lvIndex }
                    if (variable.localVariableNames[1] != otherVariable?.localVariableNames?.get(1)) {
                        shouldMakeMethod = true
                        variables += variable
                    }
                }

                if (method.comments != otherMethod.comments) {
                    shouldMakeMethod = true
                    shouldNameMethod = true
                }

                if (shouldMakeMethod) {
                    val methodNames =
                        if (shouldNameMethod) method.methodNames
                        else listOf(method.methodNames.first())

                    methods += TinyMethod(
                        method.methodDescriptorInFirstNamespace,
                        methodNames,
                        params,
                        variables,
                        if (shouldNameMethod) method.comments else emptyList()
                    )
                    shouldMakeClass = true
                }
            }

            if (shouldMakeClass) {
                val classNames =
                    if (shouldNameClass) it.classNames
                    else listOf(it.classNames.first())

                outputClasses += TinyClass(
                    classNames,
                    methods,
                    fields,
                    if (shouldNameClass) it.comments else emptyList()
                )
            }
        }

        val outputHeader = TinyHeader(listOf(sharedNamespace, differentNamespace), 2, 0, emptyMap())
        val outputTiny = TinyFile(outputHeader, outputClasses)
        val intermediaryOutput = Files.createTempFile(null, ".tiny")

        if (!keepTemp) {
            intermediaryOutput.toFile().deleteOnExit()
        }

        TinyV2Writer.write(outputTiny, intermediaryOutput)
        ConvertMappingsCommand().run("tinyv2", intermediaryOutput.toString(), outputFormat, output.toString())
        println("Done!")
    }

    companion object {
        private const val DEFAULT_FORMAT: String = "enigma"

        @JvmStatic
        fun main(args: Array<String>) {
            val cl = CommandLine(Scissors())
            val exitCode = cl.execute(*args)
            exitProcess(exitCode)
        }
    }
}