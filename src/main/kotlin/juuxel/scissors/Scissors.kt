package juuxel.scissors

import cuchaz.enigma.command.ConvertMappingsCommand
import cuchaz.enigma.command.MappingCommandsUtil
import cuchaz.enigma.translation.mapping.MappingFileNameFormat
import cuchaz.enigma.translation.mapping.MappingSaveParameters
import net.fabricmc.stitch.commands.tinyv2.TinyClass
import net.fabricmc.stitch.commands.tinyv2.TinyField
import net.fabricmc.stitch.commands.tinyv2.TinyFile
import net.fabricmc.stitch.commands.tinyv2.TinyHeader
import net.fabricmc.stitch.commands.tinyv2.TinyMethod
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

    override fun run() {
        val saveParams = MappingSaveParameters(MappingFileNameFormat.BY_DEOBF)
        println("Parsing inputs...")
        val mappingsA = MappingCommandsUtil.read(inputAFormat, inputA, saveParams)
        val mappingsB = MappingCommandsUtil.read(inputBFormat, inputB, saveParams)

        val intermediaryA = Files.createTempFile(null, ".tiny")
        val intermediaryB = Files.createTempFile(null, ".tiny")

        intermediaryA.toFile().deleteOnExit()
        intermediaryB.toFile().deleteOnExit()

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
            val methods: MutableList<TinyMethod> = ArrayList()
            val fields: MutableList<TinyField> = ArrayList()

            val otherClass = classesB[it.classNames[0]] ?: continue
            if (it.classNames[1] != otherClass.classNames[1]) {
                println("Adding ${it.classNames[1]}")
                shouldMakeClass = true
            }

            val selfMethods = it.mapMethodsByFirstNamespaceAndDescriptor()
            val selfFields = it.mapFieldsByFirstNamespace()
            val otherMethods = otherClass.mapMethodsByFirstNamespaceAndDescriptor()
            val otherFields = otherClass.mapFieldsByFirstNamespace()

            for ((name, field) in selfFields) {
                val otherField = otherFields[name] ?: continue
                if (field.fieldNames[1] != otherField.fieldNames[1]) {
                    println("Adding ${it.classNames[1]}.${field.fieldNames[1]}")
                    fields += field
                    shouldMakeClass = true
                }
            }

            for ((desc, method) in selfMethods) {
                val otherMethod = otherMethods[desc] ?: continue
                if (method.methodNames[1] != otherMethod.methodNames[1]) {
                    println("Adding ${it.classNames[1]}.${method.methodNames[1]}")
                    methods += method
                    shouldMakeClass = true
                }
            }

            if (shouldMakeClass) {
                outputClasses += TinyClass(it.classNames, methods, fields, emptyList())
            }
        }

        val outputHeader = TinyHeader(listOf(sharedNamespace, differentNamespace), 2, 0, emptyMap())
        val outputTiny = TinyFile(outputHeader, outputClasses)
        val intermediaryOutput = Files.createTempFile(null, ".tiny")
        intermediaryOutput.toFile().deleteOnExit()
        println(intermediaryOutput.toAbsolutePath())

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