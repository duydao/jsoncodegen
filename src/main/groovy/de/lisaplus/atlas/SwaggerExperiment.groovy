package de.lisaplus.atlas

import de.lisaplus.atlas.builder.JsonSchemaBuilder
import de.lisaplus.atlas.codegen.GeneratorBase
import de.lisaplus.atlas.interf.IModelBuilder
import de.lisaplus.atlas.model.Model
import de.lisaplus.atlas.model.Property
import de.lisaplus.atlas.model.Type

class SwaggerExperiment {

    static main(args) {

        // service service-junction
        def base = '/home/stefan/Entwicklung/service-junction/models/models-lisa-server/model/'
        def modelPath = args.length == 0 ?
                base + 'junction.json'
                // base + 'shared\\geo_point.json'
                : args[0]

        /*
        // service service-op-message
        def base = '/home/stefan/Entwicklung/service-op-message/models/models-lisa-server/model/'
        def modelPath = args.length == 0 ?
                base + 'op_message.json'
                : args[0]
        */

        /*
        // service incident
        def base = '/home/stefan/Entwicklung/service-op-message/models/models-lisa-server/model/'
        def modelPath = args.length == 0 ?
                base + 'incident.json'
                : args[0]
        */

        def maskExp = new SwaggerExperiment(modelPath)
        maskExp.execute()
    }

    /** The path to the model definition file */
    String modelPath = modelPath
    /** The complete model with all types */
    Model model
    /** The bindings of the template / code generation */
    Map data
    /** Indicates whether the current type is a joined type */
    boolean joined
    /** The name of the Java class, which is being masked. */
    String targetType
    /** This stack holds the property (names) visited while traversing the object hierarchy.*/
    List<Property> propStack = []
    /** Indicates that this property is an array. This sack is build while traversing the object hierarchy. */
    List<Boolean> propIsArrayStack = []
    /**
     *  Indicates that this property or any of its parents was an array and that we therefore have to process an collection.
     *  This sack is build while traversing the object hierarchy.
     */
    List<Boolean> propIsCollectionStack = []
    /**
     * Defines overwrites for maskKeys (mapping of property name to associated mask key).
     * This may be necessary for e.g. Joined types, where on property holds the Id of the joined object, and another
     * property holds the joined object itself (property objectBaseId vs. property objectBase).
     */
    Map<String, String> maskKeyOverwrites
    /** For caching the lines generated by the (recursive) closure calls of the model loops! */
    List<String> allLines = []
    /** For enabling/disabling debug output */
    boolean verbose = false

    SwaggerExperiment( String modelPath) {
        this.modelPath = modelPath
        this.model = readModel(modelPath)
    }

    private Model readModel(String modelPath) {
        def modelFile = new File(modelPath)
        IModelBuilder builder = new JsonSchemaBuilder()
        Model model = builder.buildModel(modelFile)
        model.types.each { type ->
            boolean isMainType = type.isMainType(modelFile.name)
            if(isMainType) {
                println "Tagging ${type.name}!"
                type.tags.add('mainType')
            }
        }
        return model
    }

    /**
     * Prepares and performs the code generation for one model
     */
    void execute() {
        GeneratorBase generator = new DummyGenerator()
        data = generator.createTemplateDataMap(model)
        executeForModel()
    }

    def printOperationId = { operationStr, pathStr ->
        return "${operationStr}${data.firstUpperCase.call(pathStr).replaceAll('[^a-zA-Z0-9]','_').replaceAll('__','_')}"
    }

    def includeAdditionalPaths = {
        if (!extraParam.additionalPaths) return ''
        File f = new File (extraParam.additionalPaths)
        if (!f.exists()) {
            return "!!! additionalPaths file not found: $extraParam.additionalPaths"
        }
        else {
            return f.getText('UTF-8')
        }
    }

    def includeAdditionalTypes = {
        if (!extraParam.additionalTypes) return ''
        File f = new File (extraParam.additionalTypes)
        if (!f.exists()) {
            return "!!! additionalTypes file not found: $extraParam.additionalTypes"
        }
        else {
            return f.getText('UTF-8')
        }
    }

    def getParameterStr = { List typeList,boolean isIdPath,boolean isGetPath ->
        if ((!typeList) && (!isGetPath) || ((typeList.size()==1) && (!isIdPath) && (!isGetPath) )) return ''
        def parameterStr=''
        if (isGetPath && (typeList.size()==1)) {
            parameterStr += "\n        - in: query"
            parameterStr += "\n          name: offset"
            parameterStr += "\n          type: integer"
            parameterStr += "\n          description: The number of objects to skip before starting to collect the result set."
            parameterStr += "\n        - in: query"
            parameterStr += "\n          name: limit"
            parameterStr += "\n          type: integer"
            parameterStr += "\n          description: The numbers of objects to return."
            parameterStr += "\n        - in: query"
            parameterStr += "\n          name: filter"
            parameterStr += "\n          type: string"
            parameterStr += "\n          description: Defines a criteria for selecting specific objects"
            parameterStr += "\n        - in: query"
            parameterStr += "\n          name: sort"
            parameterStr += "\n          type: string"
            parameterStr += "\n          description: Defines a sorting order for the objects"
        }
        typeList.findAll {
            isIdPath || (it != typeList[typeList.size()-1])
        }.each {
            def descriptionStr = it.description ? it.description : '???'
            parameterStr += "\n        - name: \"${data.lowerCamelCase.call(it.name)}_id\""
            parameterStr += '\n          in: "path"'
            parameterStr += "\n          description: \"$descriptionStr\""
            parameterStr += '\n          required: true'
            parameterStr += '\n          type: "string"'
            parameterStr += '\n          format: "uuid"'
        }
        def ret = """\n      parameters:
${parameterStr}
"""
        return ret
    }

    def printParametersSectionForPost = { parameterStr ->
        if (!parameterStr) {
            return '      parameters:'
        }
        else {
            return parameterStr
        }
    }

    def printAdditionalParametersForPostAndPut = { item ->
        return """        - name: "bodyParam"
          in: "body"
          description: "object to save"
          required: true
          schema:
            ${DOLLAR}ref: "#/definitions/${data.upperCamelCase.call(item.name)}\""""
    }

    /**
     * prints out tags section
     */
    def printTags = { type ->
        return """      tags:
        - ${type.name}"""
    }

    /**
     * prints out response section for ID-Paths
     */
    def printIdResponse = { type ->
        return """      responses:
        200:
          description: "in case of success"
          schema:
            ${DOLLAR}ref: "#/definitions/${data.upperCamelCase.call(type.name)}"
        404:
          description: "Requested object was not found"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\"
        default:
          description: "Unexpected error"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\""""
    }

    /**
     * prints out response section for List-Paths
     */
    def printListResponse = { typeName, boolean withQueryParameter ->
        // TODO Eiko: avoid duplication!
        if(withQueryParameter) {
            // without status code 400
            return """      responses:
        200:
          description: "in case of success"
          schema:
            type: "array"
            items:
              ${DOLLAR}ref: "#/definitions/${data.upperCamelCase.call(typeName)}"
        default:
          description: "Unexpected error"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\""""
        }
        // with status code 400
        return """      responses:
        200:
          description: "in case of success"
          schema:
            type: "array"
            items:
              ${DOLLAR}ref: "#/definitions/${data.upperCamelCase.call(typeName)}"
        400:
          description: "in case of broken filter or sort criteria"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\"
        default:
          description: "Unexpected error"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\""""
    }

    /**
     * prints out response section for Delete-Paths
     */
    def printDeleteResponse = { typeName ->
        return """      responses:
        200:
          description: "in case of success"
          schema:
            ${DOLLAR}ref: "#/definitions/IdObj"
        404:
          description: "if the object to delete was not found"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\"
        default:
          description: "Unexpected error"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\""""
    }

    /**
     * prints out response section for List-Paths
     */
    def printPutPatchPostItemResponse = { type ->
        return """      responses:
        200:
          description: "in case of success"
          schema:
            ${DOLLAR}ref: "#/definitions/${data.upperCamelCase.call(type.name)}"
        404:
          description: "if the object to process was not found"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\"
        409:
          description: "if altering object would cause inconsistent data model"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\"
        default:
          description: "Unexpected error"
          schema:
            ${DOLLAR}ref: "#/definitions/LisaError\""""
    }

    /**
     * prints out options block
     */
    def printOptionsBlock = { pathStr,lastItem,parameterStr ->
        return """    options:
${printTags(lastItem)}
      summary: Provides meta data of the related type
      description: return a meta data object
      operationId: \"${printOperationId('options',pathStr)}\"
      produces:
        - \"application/xml\"
        - \"application/json\"
${parameterStr}
      responses:
        200:
          description: \"in case of success\"
          schema:
            ${DOLLAR}ref: \"#/definitions/OptionsResponse\"
        501:
          description: \"in case of missing implementation\"
          schema:
            ${DOLLAR}ref: \"#/definitions/LisaError\"
        default:
          description: \"Unexpected error\"
          schema:
            ${DOLLAR}ref: \"#/definitions/LisaError\""""
    }

    /**
     * prints out options block
     */
    def printListOptionsBlock = { pathStr,lastItem,parameterStr ->
        if (parameterStr) {
            return printOptionsBlock.call (pathStr,lastItem,parameterStr)
        }
        parameterStr = "      parameters:"
        return """    options:
${printTags(lastItem)}
      summary: Provides meta data of the related type
      description: return a meta data object
      operationId: \"${printOperationId('options',pathStr)}\"
      produces:
        - \"application/xml\"
        - \"application/json\"
${parameterStr}
        - in: query
          name: filter
          type: string
          description: Defines a criteria for selecting specific objects
      responses:
        200:
          description: \"in case of success\"
          schema:
            ${DOLLAR}ref: \"#/definitions/OptionsListResponse\"
        501:
          description: \"in case of missing implementation\"
          schema:
            ${DOLLAR}ref: \"#/definitions/LisaError\"
        default:
          description: \"Unexpected error\"
          schema:
            ${DOLLAR}ref: \"#/definitions/LisaError\""""
    }

    // If you declare a closure you can use it inside the template
    /**
     * Print path for List URLs
     */
    def printIDPath = { List typeList ->
        def pathStr=''
        typeList.each {
            pathStr += '/'
            pathStr += data.lowerCamelCase.call(it.name)
            pathStr += "/{${data.lowerCamelCase.call(it.name)}_id}"
        }
        def lastItem = typeList[typeList.size()-1]
        def summary = lastItem.description ? lastItem.description : '???'
        def parameterStr = getParameterStr(typeList,true,false)
        // def parameterStrGetList = getParameterStr(typeList,true,true)
        return """
  ${pathStr}:
${printOptionsBlock(pathStr,lastItem,parameterStr)}
    get:
${printTags(lastItem)}
      summary: ${summary}
      description: "returns object by id"
      operationId: "${printOperationId('get',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
${parameterStr}
${printIdResponse(lastItem)}
    put:
${printTags(lastItem)}
      summary: "update ${lastItem.name}"
      description: "update existing ${lastItem.name}"
      operationId: "${printOperationId('upd',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
      consumes:
        - "application/xml"
        - "application/json"
${parameterStr}
${printAdditionalParametersForPostAndPut(lastItem)}
${printPutPatchPostItemResponse(lastItem)}
    patch:
${printTags(lastItem)}
      summary: "partial update ${lastItem.name}"
      description: "partial update existing ${lastItem.name}"
      operationId: "${printOperationId('patch',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
      consumes:
        - "application/xml"
        - "application/json"
${parameterStr}
${printAdditionalParametersForPostAndPut(lastItem)}
${printPutPatchPostItemResponse(lastItem)}
    delete:
${printTags(lastItem)}
      summary: "delete ${lastItem.name}"
      description: "delete existing ${lastItem.name}"
      operationId: "${printOperationId('del',pathStr)}"
${parameterStr}
${printDeleteResponse()}
"""
    }

    /**
     * Print path for ID URLs and joined types
     */
    def printIDPathJoined = { List typeList ->
        def pathStr=''
        typeList.each {
            pathStr += '/'
            pathStr += data.lowerCamelCase.call(it.name)
            pathStr += "/{${data.lowerCamelCase.call(it.name)}_id}"
        }
        def lastItem = typeList[typeList.size()-1]
        def summary = lastItem.description ? lastItem.description : '???'
        def parameterStr = getParameterStr(typeList,true,false)
        // def parameterStrGetList = getParameterStr(typeList,true,true)
        return """
  ${pathStr}:
${printOptionsBlock(pathStr,lastItem,parameterStr)}
    get:
${printTags(lastItem)}
      summary: ${summary}
      description: "returns object by id"
      operationId: "${printOperationId('get',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
${parameterStr}
${printIdResponse(lastItem)}
"""
    }

    /**
     * Print path for List URLs that are no arrays
     */
    def printListPath_noArray = { List typeList ->
        def pathStr=''
        def lastElem = typeList[typeList.size()-1]
        typeList.each {
            pathStr += '/'
            pathStr += data.lowerCamelCase.call(it.name)
            if (it!=lastElem) {
                pathStr += "/{${data.lowerCamelCase.call(it.name)}_id}"
            }
        }
        def lastItem = typeList[typeList.size()-1]
        def summary = lastItem.description ? lastItem.description : '???'
        def parameterStr = getParameterStr(typeList,false,false)
        def parameterStrGetList = getParameterStr(typeList,false,true)
        def descriptionExtension = ''
        if (typeList.size==1) {
            descriptionExtension += ", contains optional query paramter for defining offset, limit, object filter and object order"
        }
        def ret = """
  ${pathStr}:
${printOptionsBlock(pathStr,lastItem,parameterStr)}
    get:
${printTags(lastItem)}
      summary: "${summary}"
      description: "returns object list${descriptionExtension}"
      operationId: "${printOperationId('get',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
${parameterStrGetList}
${printListResponse(lastItem.name,typeList.size!=1)}
    put:
${printTags(lastItem)}
      summary: "add a new ${lastItem.name}"
      description: ""
      operationId: "${printOperationId('upd',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
      consumes:
        - "application/xml"
        - "application/json"
${printParametersSectionForPost(parameterStr)}
${printAdditionalParametersForPostAndPut(lastItem)}
${printPutPatchPostItemResponse(lastItem)}
    patch:
${printTags(lastItem)}
      summary: "partial update ${lastItem.name}"
      description: ""
      operationId: "${printOperationId('patch',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
      consumes:
        - "application/xml"
        - "application/json"
${printParametersSectionForPost(parameterStr)}
${printAdditionalParametersForPostAndPut(lastItem)}
${printPutPatchPostItemResponse(lastItem)}
"""
        return ret
    }

    /**
     * Print path for List URLs for arrays
     */
    def printListPath_array = { List typeList ->
        def pathStr=''
        def lastElem = typeList[typeList.size()-1]
        typeList.each {
            pathStr += '/'
            pathStr += data.lowerCamelCase.call(it.name)
            if (it!=lastElem) {
                pathStr += "/{${data.lowerCamelCase.call(it.name)}_id}"
            }
        }
        def lastItem = typeList[typeList.size()-1]
        def summary = lastItem.description ? lastItem.description : '???'
        def parameterStr = getParameterStr(typeList,false,false)
        def parameterStrGetList = getParameterStr(typeList,false,true)
        def descriptionExtension = ''
        if (typeList.size==1) {
            descriptionExtension += ", contains optional query paramter for defining offset, limit, object filter and object order"
        }
        def ret = """
  ${pathStr}:
${printListOptionsBlock(pathStr,lastItem,parameterStr)}
    get:
${printTags(lastItem)}
      summary: "${summary}"
      description: "returns object list${descriptionExtension}"
      operationId: "${printOperationId('get',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
${parameterStrGetList}
${printListResponse(lastItem.name,typeList.size!=1)}
    post:
${printTags(lastItem)}
      summary: "add a new ${lastItem.name}"
      description: ""
      operationId: "${printOperationId('add',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
      consumes:
        - "application/xml"
        - "application/json"
${printParametersSectionForPost(parameterStr)}
${printAdditionalParametersForPostAndPut(lastItem)}
${printPutPatchPostItemResponse(lastItem)}
"""
        return ret
    }

    /**
     * Print path for List URLs for arrays
     */
    def printListPathJoined = { List typeList ->
        def pathStr=''
        def lastElem = typeList[typeList.size()-1]
        typeList.each {
            pathStr += '/'
            pathStr += data.lowerCamelCase.call(it.name)
            if (it!=lastElem) {
                pathStr += "/{${data.lowerCamelCase.call(it.name)}_id}"
            }
        }
        def lastItem = typeList[typeList.size()-1]
        def summary = lastItem.description ? lastItem.description : '???'
        def parameterStr = getParameterStr(typeList,false,false)
        def parameterStrGetList = getParameterStr(typeList,false,true)
        def descriptionExtension = ''
        if (typeList.size==1) {
            descriptionExtension += ", contains optional query paramter for defining offset, limit, object filter and object order"
        }
        def ret = """
  ${pathStr}:
${printListOptionsBlock(pathStr,lastItem,parameterStr)}
    get:
${printTags(lastItem)}
      summary: "${summary}"
      description: "returns object list${descriptionExtension}"
      operationId: "${printOperationId('get',pathStr)}"
      produces:
        - "application/xml"
        - "application/json"
${parameterStrGetList}
${printListResponse(lastItem.name,typeList.size!=1)}
"""
        return ret
    }

    /**
     * Print path for List URLs that are no arrays, functional fasade
     */
    def printListPath = { List typeList, boolean array = false ->
        if (array) {
            printListPath_array (typeList)
        }
        else {
            printListPath_noArray (typeList)
        }
    }

    private void executeForModel() {
        println 'Add stuff!'

        def var = 'have it my way'
        println "with embedded logic: ${ -> var.startsWith('ha') ? 'yes' : 'no' } done!"

        Map extraParam = [:]

        /*
        def hostLine = extraParam.host ? /host: "${extraParam.host}"/ :  'host: "please.change.com"'
        $hostLine
        */

        /*
        def basePathLine
        if (extraParam.basePath) {
            if (extraParam.appendVersionToPath) {
                basePathLine = $/basePath: "${extraParam.basePath}/v${model.version}"/$
            } else {
                basePathLine = /basePath: "${extraParam.basePath}"/
            }
        } else {
            basePathLine = $/basePath: "/v${model.version}"/$
        }
        $basePathLine
        */


        String part1 = $/
swagger: "2.0"
info:
title: "${model.title}"
description: "${model.description}"
version: "${model.version}"
host: "${ -> extraParam.host ? extraParam.host : 'please.change.com' }"
schemes:
- "http"
- "https"
basePath: "${ ->
            if (extraParam.basePath) {
                if (extraParam.appendVersionToPath) {
                    "${extraParam.basePath}/v${model.version}"
                } else {
                    "${extraParam.basePath}"
                }
            } else {
                "/v${model.version}"
            }
        }"
paths:
/$
        println part1

        //// search for all types that should provide entry points
        // model.types.each{ println "name=$it.name  tags=$it.tags isMainType=${ -> it.isMainType('junction')}" }

        model.types.findAll { return (it.hasTag('mainType')) && (it.hasTag('rest')) && (!it.hasTag('joinedType')) }.each { type ->
            println type.name
            println printListPath([type], true)
            println printIDPath([type])
        }


////// search for all types that should provide entry points
//        <% model.types.findAll { return (it.hasTag('mainType')) && (it.hasTag('rest')) && (!it.hasTag('joinedType')) }.each { type -> %>
//            ${printListPath([type],true)}
//            ${printIDPath([type])}
//            <% } %>
//
//
//                <% model.types.findAll { return (it.hasTag('mainType')) && (it.hasTag('rest')) && (!it.hasTag('joinedType')) }.each { type -> %>
//            //// properties that are Sub-Types should be rendered as sub paths
//            <% type.properties.findAll{ ((it.type instanceof de.lisaplus.atlas.model.RefType) || (it.type instanceof de.lisaplus.atlas.model.ComplexType)) &&
//                    (!(['number','name'].contains(it.name)))  && (it.type.type.name!='ListEntry') }.each { prop -> %>
//                ${printListPath([type,prop.type.type],prop.type.isArray)}
//                //// ID functions for subpaths are only needed in case of array elements
//                <% if (prop.type.isArray) { %>
//                    ${printIDPath([type,prop.type.type])}
//                    <% } %>
//                <% } %>
//                    <% } %>
//
//                <% model.types.findAll { return (it.hasTag('mainType')) && (it.hasTag('rest')) && (!it.hasTag('joinedType')) }.each { type -> %>
//            //// properties that are Sub-Types should be rendered as sub paths
//            <% type.properties.findAll{ ((it.type instanceof de.lisaplus.atlas.model.RefType) || (it.type instanceof de.lisaplus.atlas.model.ComplexType)) &&
//                    (!(['number','name'].contains(it.name))) && (it.type.type.name!='ListEntry') }.each { prop -> %>
//                //// ID functions for subpaths are only needed in case of array elements
//                <% def idProp = prop.type.type.properties.find{ it -> it.name=='guid' || it.name=='entryId' } %>
//                        //// sub-level 3: properties that are Sub-Types should be rendered as sub paths
//                        <% if (!['dummy'].contains(prop.name)) { %>
//                    <% prop.type.type.properties.findAll{ ((it.type instanceof de.lisaplus.atlas.model.RefType) || (it.type instanceof de.lisaplus.atlas.model.ComplexType)) &&
//                            (!(['contact','area','center','route'].contains(it.name))) && (it.type.type.name!='ListEntry') }.each { prop2 -> %>
//                        <% if (idProp) { %>
//                            ${printListPath([type,prop.type.type,prop2.type.type],prop2.type.isArray)}
//                            <% } else { %>
//                            ${printListPath([type,prop2.type.type],prop2.type.isArray)}
//                            <% } %>
//                        //// ID functions for subpaths are only needed in case of array elements
//                        <% if (prop2.type.isArray) { %>
//                            <% if (idProp) { %>
//                                //// need to handle post/put problem
//                                ${printIDPath([type,prop.type.type,prop2.type.type])}
//                                <% } else { %>
//                                ${printIDPath([type,prop2.type.type])}
//                                <% } %>
//                            <% } %>
//                        <% } %>
//                            <% } %>
//                <% } %>
//                    <% } %>
//
//
//                <% model.types.findAll { it.hasTag('joinedType') && it.hasTag('rest') && it.hasTag('mainType') }.each { type -> %>
//            ${printListPathJoined([type])}
//            ${printIDPathJoined([type])}
//            <% } %>
////// mix in an optional file with additional files
//                ${ includeAdditionalPaths.call() }
//        definitions:
//        <% model.types.each { type -> %>
//            ${data.upperCamelCase.call(type.name)}:
//            type: object
//            properties:
//            <% type.properties.each { prop -> %>
//                ${prop.name}:
//                <% if (prop.type.isArray) { %>
//                    type: array
//                    items:
//                    <% if ((prop.isRefTypeOrComplexType())) { %>
//                        ${DOLLAR}ref: "#/definitions/${data.upperCamelCase.call(prop.type.type.name)}"
//                        <% } else { %>
//                        <% if (prop.description) { %>
//                            description: "${prop.description}"
//                            <% } %>
//                        type: "${typeToSwagger.call(prop.type)}"
//                        <% if (typeFormatToSwagger.call(prop.type)) { %>
//                            format: "${typeFormatToSwagger.call(prop.type)}"
//                            <% } %>
//                        <% } %>
//                    <% } else { %>
//                    <% if (prop.isRefTypeOrComplexType()) { %>
//                        ${DOLLAR}ref: "#/definitions/${data.upperCamelCase.call(prop.type.type.name)}"
//                        <% } else { %>
//                        <% if (prop.description) { %>
//                            description: "${prop.description}"
//                            <% } %>
//                        type: "${typeToSwagger.call(prop.type)}"
//                        <% if (typeFormatToSwagger.call(prop.type)) { %>
//                            format: "${typeFormatToSwagger.call(prop.type)}"
//                            <% } %>
//                        <% } %>
//                    <% } %>
//                <% } %>
//                    <% } %>
////// mix in an optional file with additional files
//                ${ includeAdditionalTypes.call() }
    }
}
