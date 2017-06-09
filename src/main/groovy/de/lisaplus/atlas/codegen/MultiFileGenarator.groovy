package de.lisaplus.atlas.codegen

import de.lisaplus.atlas.interf.ICodeGen
import de.lisaplus.atlas.model.Model
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Base class for generators that creates more than one file ... for instance JavaBeanGenerator
 * Created by eiko on 05.06.17.
 */
abstract class MultiFileGenarator extends GeneratorBase implements ICodeGen {
    /**
     * This funkction is called to start the code generation process
     * @param model model that is the base for the code generation
     * @param outputBasePath under this path the output is generated. A generator can add a needed sub path if needed (for instance for packeges)
     * @param extraParams additional parameters to initialize the generator
     */
    void doCodeGen(Model model, String outputBasePath, Map<String,String> extraParams) {
        model.types*.each {
            genForSingleType()
        }
    }
}